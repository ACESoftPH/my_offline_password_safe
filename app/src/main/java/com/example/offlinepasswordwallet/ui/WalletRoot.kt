package com.example.offlinepasswordwallet.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.example.offlinepasswordwallet.data.repository.VaultState
import com.example.offlinepasswordwallet.di.ServiceLocator
import com.example.offlinepasswordwallet.ui.navigation.Dest
import com.example.offlinepasswordwallet.ui.screens.ChangeMasterPasswordScreen
import com.example.offlinepasswordwallet.ui.screens.ChangeSecurityAnswersScreen
import com.example.offlinepasswordwallet.ui.screens.EntryDetailScreen
import com.example.offlinepasswordwallet.ui.screens.EntryEditScreen
import com.example.offlinepasswordwallet.ui.screens.ExportBackupScreen
import com.example.offlinepasswordwallet.ui.screens.ExportCsvScreen
import com.example.offlinepasswordwallet.ui.screens.ImportCsvScreen
import com.example.offlinepasswordwallet.ui.screens.PasswordGeneratorScreen
import com.example.offlinepasswordwallet.ui.screens.RecoveryScreen
import com.example.offlinepasswordwallet.ui.screens.RestoreBackupScreen
import com.example.offlinepasswordwallet.ui.screens.SettingsScreen
import com.example.offlinepasswordwallet.ui.screens.SetupScreen
import com.example.offlinepasswordwallet.ui.screens.UnlockScreen
import com.example.offlinepasswordwallet.ui.screens.VaultListScreen

/**
 * Top-level navigation host + auth-state routing.
 *
 * The vault's [VaultState] is the source of truth: whenever it flips to
 * `Locked` / `Uninitialized` (e.g. auto-lock fired, or the app returned from the
 * background after the timeout), the back stack is wiped and the user is sent to
 * an auth screen. Nothing sensitive can sit behind the lock screen.
 */
@Composable
fun WalletRoot(activity: FragmentActivity) {
    val navController = rememberNavController()
    val vaultState by ServiceLocator.vaultRepository.state.collectAsStateWithLifecycle()

    val startDestination = when (vaultState) {
        is VaultState.Uninitialized -> Dest.SETUP
        else -> Dest.UNLOCK
    }

    LaunchedEffect(vaultState) {
        val current = navController.currentDestination?.route
        when (vaultState) {
            // Never yank the user out of a multi-step auth flow they are mid-way
            // through (recovery reset, backup restore) — those screens finish by
            // navigating themselves.
            is VaultState.Uninitialized ->
                if (current !in MULTI_STEP_AUTH_ROUTES) navController.resetTo(Dest.SETUP)

            is VaultState.Locked ->
                if (current !in MULTI_STEP_AUTH_ROUTES) navController.resetTo(Dest.UNLOCK)

            is VaultState.Unlocked -> {
                // Only the terminal auth screens hand off automatically. RECOVERY
                // still has a mandatory "choose a new master password" step after
                // the recovery unlock succeeds — jumping to LIST here would destroy
                // that screen and leave the DEK wrapped only under the forgotten
                // password. RESTORE_BACKUP is the same shape.
                if (current == null || current in HANDOFF_ROUTES) {
                    navController.resetTo(Dest.LIST)
                }
            }
        }
    }

    NavHost(navController = navController, startDestination = startDestination) {
        authGraph(navController)
        vaultGraph(navController, activity)
    }
}

/** Auth screens that are a single step and can hand off as soon as we are unlocked. */
private val HANDOFF_ROUTES = setOf(Dest.SETUP, Dest.UNLOCK)

/** Auth screens that own their own completion and must not be navigated away from. */
private val MULTI_STEP_AUTH_ROUTES = setOf(Dest.RECOVERY, Dest.RESTORE_BACKUP)

private fun NavHostController.resetTo(route: String) {
    if (currentDestination?.route == route) return
    navigate(route) {
        popUpTo(graph.id) { inclusive = true }
        launchSingleTop = true
    }
}

private fun NavGraphBuilder.authGraph(navController: NavHostController) {
    composable(Dest.SETUP) {
        SetupScreen(onRestoreBackup = { navController.navigate(Dest.RESTORE_BACKUP) })
    }
    composable(Dest.UNLOCK) {
        UnlockScreen(
            onForgotMasterPassword = { navController.navigate(Dest.RECOVERY) },
            onRestoreBackup = { navController.navigate(Dest.RESTORE_BACKUP) },
        )
    }
    composable(Dest.RECOVERY) {
        RecoveryScreen(
            // The vault is already unlocked AND re-keyed at this point, so go
            // straight to the vault rather than back to the lock screen.
            onDone = { navController.resetTo(Dest.LIST) },
            onCancel = {
                // Answers may have unlocked the vault without a new master password
                // being set. Re-lock so we never leave the vault open behind a
                // half-finished reset.
                ServiceLocator.vaultRepository.lock()
                navController.resetTo(Dest.UNLOCK)
            },
        )
    }
    composable(Dest.RESTORE_BACKUP) {
        RestoreBackupScreen(
            onDone = { navController.resetTo(Dest.LIST) },
            onCancel = { navController.popBackStack() },
        )
    }
}

private fun NavGraphBuilder.vaultGraph(navController: NavHostController, activity: FragmentActivity) {
    composable(Dest.LIST) {
        VaultListScreen(
            onOpenEntry = { id -> navController.navigate(Dest.detail(id)) },
            onAddEntry = { navController.navigate(Dest.edit(Dest.NEW_ENTRY_ID)) },
            onOpenSettings = { navController.navigate(Dest.SETTINGS) },
            onOpenGenerator = { navController.navigate(Dest.GENERATOR) },
            onImport = { navController.navigate(Dest.IMPORT_CSV) },
            onExport = { navController.navigate(Dest.EXPORT_CSV) },
            onExportBackup = { navController.navigate(Dest.EXPORT_BACKUP) },
            onRestoreBackup = { navController.navigate(Dest.RESTORE_BACKUP) },
        )
    }
    composable(Dest.DETAIL) { backStackEntry ->
        val id = backStackEntry.arguments?.getString("entryId").orEmpty()
        EntryDetailScreen(
            entryId = id,
            onBack = { navController.popBackStack() },
            onEdit = { navController.navigate(Dest.edit(id)) },
        )
    }
    composable(Dest.EDIT) { backStackEntry ->
        val id = backStackEntry.arguments?.getString("entryId").orEmpty()
        EntryEditScreen(
            entryId = id,
            onBack = { navController.popBackStack() },
            onSaved = { navController.popBackStack() },
        )
    }
    composable(Dest.GENERATOR) {
        PasswordGeneratorScreen(onBack = { navController.popBackStack() }, onUse = null)
    }
    composable(Dest.SETTINGS) {
        SettingsScreen(
            activity = activity,
            onBack = { navController.popBackStack() },
            onChangeMaster = { navController.navigate(Dest.CHANGE_MASTER) },
            onChangeAnswers = { navController.navigate(Dest.CHANGE_ANSWERS) },
            onImport = { navController.navigate(Dest.IMPORT_CSV) },
            onExport = { navController.navigate(Dest.EXPORT_CSV) },
            onExportBackup = { navController.navigate(Dest.EXPORT_BACKUP) },
            onRestoreBackup = { navController.navigate(Dest.RESTORE_BACKUP) },
        )
    }
    composable(Dest.CHANGE_MASTER) {
        ChangeMasterPasswordScreen(onBack = { navController.popBackStack() })
    }
    composable(Dest.CHANGE_ANSWERS) {
        ChangeSecurityAnswersScreen(onBack = { navController.popBackStack() })
    }
    composable(Dest.IMPORT_CSV) {
        ImportCsvScreen(onBack = { navController.popBackStack() })
    }
    composable(Dest.EXPORT_CSV) {
        ExportCsvScreen(onBack = { navController.popBackStack() })
    }
    composable(Dest.EXPORT_BACKUP) {
        ExportBackupScreen(onBack = { navController.popBackStack() })
    }
}
