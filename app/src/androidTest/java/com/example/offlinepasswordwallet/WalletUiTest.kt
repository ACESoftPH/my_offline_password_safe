package com.example.offlinepasswordwallet

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.offlinepasswordwallet.di.ServiceLocator
import com.example.offlinepasswordwallet.ui.MainActivity
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Instrumented UI tests (§36). Require a connected device/emulator:
 *   ./gradlew connectedDebugAndroidTest
 *
 * Every test starts from a wiped vault directory, so the app opens on first-run
 * setup.
 */
@RunWith(AndroidJUnit4::class)
class WalletUiTest {

    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    @Before
    fun clearVault() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        File(ctx.filesDir, "vault").deleteRecursively()
        ServiceLocator.vaultRepository.lock()
    }

    private fun visible(tag: String): Boolean =
        runCatching { rule.onNodeWithTag(tag).assertIsDisplayed(); true }.getOrDefault(false)

    private fun completeSetup(master: String = "Test-Master-Pass-1") {
        rule.onNodeWithTag("master_password").performTextInput(master)
        rule.onNodeWithTag("confirm_password").performTextInput(master)
        rule.onNodeWithTag("setup_next").performClick()
        for (i in 0 until 5) rule.onNodeWithTag("answer_$i").performTextInput("answer $i")
        rule.onNodeWithTag("create_vault_button").performClick()
        rule.waitUntil(10_000) { visible("add_entry_fab") }
    }

    @Test
    fun firstRunSetup_showsVaultList() {
        completeSetup()
        rule.onNodeWithTag("add_entry_fab").assertIsDisplayed()
    }

    @Test
    fun createEntry_thenViewIt_passwordCensoredByDefault() {
        completeSetup()
        rule.onNodeWithTag("add_entry_fab").performClick()
        rule.onNodeWithTag("field_Title").performTextInput("Facebook Account")
        rule.onNodeWithTag("field_Password").performTextInput("MyActualPassword123!")
        rule.onNodeWithTag("save_entry").performClick()

        rule.waitUntil(5_000) {
            runCatching { rule.onNodeWithText("Facebook Account").assertIsDisplayed(); true }
                .getOrDefault(false)
        }
        rule.onNodeWithText("Facebook Account").performClick()
        // On the detail screen the password row exposes a SHOW toggle => censored.
        rule.waitUntil(5_000) {
            runCatching { rule.onNodeWithText("SHOW").assertIsDisplayed(); true }.getOrDefault(false)
        }
        rule.onNodeWithTag("field_row_Password").assertIsDisplayed()
    }

    @Test
    fun passwordGenerator_producesAPasswordAndRegenerates() {
        completeSetup()
        rule.onNodeWithContentDescription("More").performClick()
        rule.onNodeWithText("Password generator").performClick()
        rule.onNodeWithTag("generated_password").assertIsDisplayed()
        rule.onNodeWithTag("generate_again").performClick()
        rule.onNodeWithTag("generated_password").assertIsDisplayed()
    }

    @Test
    fun exportEncryptedBackup_reachableFromMenu() {
        completeSetup()
        rule.onNodeWithContentDescription("More").performClick()
        rule.onNodeWithText("Import / Export → Export encrypted backup").performClick()
        rule.waitUntil(5_000) { visible("backup_passphrase") }
        rule.onNodeWithTag("backup_passphrase").performTextInput("Backup-Pass-Phrase-1")
        rule.onNodeWithTag("backup_passphrase_confirm").performTextInput("Backup-Pass-Phrase-1")
        rule.onNodeWithTag("do_export_backup").assertIsDisplayed()
    }

    @Test
    fun restoreFromEncryptedBackup_reachableFromUnlockScreen() {
        completeSetup()
        rule.onNodeWithContentDescription("Lock now").performClick()
        rule.waitUntil(5_000) { visible("unlock_password") }
        rule.onNodeWithTag("unlock_restore_backup").performClick()
        rule.waitUntil(5_000) { visible("choose_backup_file") }
        rule.onNodeWithTag("restore_passphrase").assertIsDisplayed()
    }

    @Test
    fun lockThenUnlockWithMasterPassword() {
        completeSetup()
        rule.onNodeWithContentDescription("Lock now").performClick()
        rule.waitUntil(5_000) { visible("unlock_password") }
        rule.onNodeWithTag("unlock_password").performTextInput("Test-Master-Pass-1")
        rule.onNodeWithTag("unlock_button").performClick()
        rule.waitUntil(10_000) { visible("add_entry_fab") }
    }
}
