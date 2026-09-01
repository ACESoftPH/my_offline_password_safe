package com.acesoftph.offlinepasswordwallet.ui

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
import com.acesoftph.offlinepasswordwallet.data.repository.VaultState
import com.acesoftph.offlinepasswordwallet.di.ServiceLocator
import com.acesoftph.offlinepasswordwallet.ui.components.WalletBottomBar
import com.acesoftph.offlinepasswordwallet.ui.components.WalletTab
import com.acesoftph.offlinepasswordwallet.ui.navigation.Dest
import com.acesoftph.offlinepasswordwallet.ui.screens.ChangeMasterPasswordScreen
import com.acesoftph.offlinepasswordwallet.ui.screens.ChangeSecurityAnswersScreen
import com.acesoftph.offlinepasswordwallet.ui.screens.EntryDetailScreen
import com.acesoftph.offlinepasswordwallet.ui.screens.EntryEditScreen
import com.acesoftph.offlinepasswordwallet.ui.screens.ExportBackupScreen
import com.acesoftph.offlinepasswordwallet.ui.screens.ExportCsvScreen
import com.acesoftph.offlinepasswordwallet.ui.screens.ImportCsvScreen
import com.acesoftph.offlinepasswordwallet.ui.screens.PasswordGeneratorScreen
import com.acesoftph.offlinepasswordwallet.ui.screens.RecoveryScreen
import com.acesoftph.offlinepasswordwallet.ui.screens.RestoreBackupScreen
import com.acesoftph.offlinepasswordwallet.ui.screens.SettingsScreen
import com.acesoftph.offlinepasswordwallet.ui.screens.SetupScreen
import com.acesoftph.offlinepasswordwallet.ui.screens.UpgradeScreen
import com.acesoftph.offlinepasswordwallet.ui.screens.UnlockScreen
import com.acesoftph.offlinepasswordwallet.ui.screens.VaultListScreen

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

/** The three destinations the bottom bar switches between. */
private val TAB_ROUTES = mapOf(
    WalletTab.ENTRIES to Dest.LIST,
    WalletTab.GENERATOR to Dest.GENERATOR,
    WalletTab.SETTINGS to Dest.SETTINGS,
)

private fun NavHostController.resetTo(route: String) {
    if (currentDestination?.route == route) return
    navigate(route) {
        popUpTo(graph.id) { inclusive = true }
        launchSingleTop = true
    }
}

/**
 * Switches top-level tab, keeping LIST as the root of the vault section.
 *
 * Everything above LIST is popped and the new tab pushed, so the stack is at most
 * [LIST, tab] and the system back button returns to Entries rather than walking
 * a history of tab presses.
 *
 * Deliberately no saveState/restoreState. Those only behave when popping to the
 * graph's start destination, which here is an auth route we must never return to.
 * Popping LIST *inclusively* while saving its state and then navigating straight
 * back to it left the NavHost with no current destination at all — a blank white
 * screen that only an app restart cleared.
 */
private fun NavHostController.switchTab(route: String) {
    if (currentDestination?.route == route) return
    navigate(route) {
        popUpTo(Dest.LIST) { inclusive = false }
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

    @Composable
    fun bar(selected: WalletTab) {
        WalletBottomBar(
            selected = selected,
            onSelect = { tab -> TAB_ROUTES[tab]?.let { navController.switchTab(it) } },
        )
    }

    composable(Dest.LIST) {
        VaultListScreen(
            onOpenEntry = { id -> navController.navigate(Dest.detail(id)) },
            onAddEntry = { navController.navigate(Dest.edit(Dest.NEW_ENTRY_ID)) },
            onUpgrade = { navController.navigate(Dest.UPGRADE) },
            // Leaving via back is a clear signal the user is done, so drop the
            // decrypted vault before the activity goes away. Without this the
            // process can survive and a relaunch inside the auto-lock window
            // would walk straight back into decrypted data with no auth.
            onLockAndExit = {
                ServiceLocator.vaultRepository.lock()
                activity.finish()
            },
            bottomBar = { bar(WalletTab.ENTRIES) },
        )
    }
    composable(Dest.GENERATOR) {
        PasswordGeneratorScreen(
            onBack = null,
            onUse = null,
            bottomBar = { bar(WalletTab.GENERATOR) },
        )
    }
    composable(Dest.SETTINGS) {
        SettingsScreen(
            activity = activity,
            onChangeMaster = { navController.navigate(Dest.CHANGE_MASTER) },
            onChangeAnswers = { navController.navigate(Dest.CHANGE_ANSWERS) },
            onImport = { navController.navigate(Dest.IMPORT_CSV) },
            onExport = { navController.navigate(Dest.EXPORT_CSV) },
            onExportBackup = { navController.navigate(Dest.EXPORT_BACKUP) },
            onRestoreBackup = { navController.navigate(Dest.RESTORE_BACKUP) },
            onUpgrade = { navController.navigate(Dest.UPGRADE) },
            bottomBar = { bar(WalletTab.SETTINGS) },
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
    composable(Dest.UPGRADE) {
        UpgradeScreen(activity = activity, onBack = { navController.popBackStack() })
    }
}
