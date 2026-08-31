package com.acesoft.offlinepasswordwallet.data.storage

import androidx.test.core.app.ApplicationProvider
import com.acesoft.offlinepasswordwallet.crypto.AeadDecryptionException
import com.acesoft.offlinepasswordwallet.crypto.CryptoConstants
import com.acesoft.offlinepasswordwallet.crypto.VaultFormatException
import com.acesoft.offlinepasswordwallet.crypto.VaultRollbackException
import com.acesoft.offlinepasswordwallet.data.model.DefaultFields
import com.acesoft.offlinepasswordwallet.data.model.VaultDocument
import com.acesoft.offlinepasswordwallet.data.model.VaultEntry
import com.acesoft.offlinepasswordwallet.data.model.VaultField
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Regression tests for the vault-format hardening the review asked for:
 * associated data + a revision counter on the payload (rollback detection), and
 * bounds on the KDF parameters that are read out of the file itself.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class VaultHardeningTest {

    private val codec = VaultCodec()
    private val master = "hardening-master-pass-1".toCharArray()
    private val answers = listOf("s", "p", "m", "d", "2001")

    private lateinit var ctx: android.content.Context

    @Before
    fun setUp() {
        ctx = ApplicationProvider.getApplicationContext()
        File(ctx.filesDir, "vault").deleteRecursively()
    }

    private fun doc(vararg titles: String) = VaultDocument(
        entries = titles.map { VaultEntry(fields = listOf(VaultField(DefaultFields.TITLE, it))) },
    )

    // --- AAD / rollback -------------------------------------------------------

    @Test
    fun `new vaults are written at the current format version with a revision`() {
        val file = codec.createVault(master.copyOf(), answers, doc("A"))
        assertEquals(CryptoConstants.VAULT_FORMAT_VERSION, file.formatVersion)
        assertEquals(1L, file.revision)
    }

    @Test
    fun `every write bumps the revision`() {
        var file = codec.createVault(master.copyOf(), answers, doc("A"))
        val dek = codec.unlockWithMaster(file, master.copyOf()).dek
        file = codec.updateDocument(file, dek, doc("A", "B"))
        assertEquals(2L, file.revision)
        file = codec.updateDocument(file, dek, doc("A", "B", "C"))
        assertEquals(3L, file.revision)
    }

    @Test
    fun `an older payload pasted into a newer header no longer authenticates`() {
        val v1 = codec.createVault(master.copyOf(), answers, doc("Original"))
        val dek = codec.unlockWithMaster(v1, master.copyOf()).dek
        val v2 = codec.updateDocument(v1, dek, doc("Rotated"))

        // Attacker keeps the current header but restores the previous payload blob.
        val spliced = v2.copy(payload = v1.payload)
        assertThrows(AeadDecryptionException::class.java) {
            codec.unlockWithMaster(spliced, master.copyOf())
        }
    }

    @Test
    fun `editing the revision alone breaks authentication`() {
        val file = codec.createVault(master.copyOf(), answers, doc("A"))
        assertThrows(AeadDecryptionException::class.java) {
            codec.unlockWithMaster(file.copy(revision = 99L), master.copyOf())
        }
    }

    @Test
    fun `editing the vault id alone breaks authentication`() {
        val file = codec.createVault(master.copyOf(), answers, doc("A"))
        assertThrows(AeadDecryptionException::class.java) {
            codec.unlockWithMaster(file.copy(vaultId = "someone-elses-vault"), master.copyOf())
        }
    }

    @Test
    fun `store refuses a vault file older than the last recorded write`() = runTest {
        val store = VaultFileStore(ctx)
        var file = codec.createVault(master.copyOf(), answers, doc("A"))
        store.write(file)
        val dek = codec.unlockWithMaster(file, master.copyOf()).dek

        file = codec.updateDocument(file, dek, doc("A", "B"))
        store.write(file) // revision 2 recorded

        // Put the revision-1 file back on disk, as a stale copy would.
        val stale = codec.createVault(master.copyOf(), answers, doc("A"))
        val rolledBack = stale.copy(vaultId = file.vaultId, revision = 1L)
        File(File(ctx.filesDir, "vault"), "vault.json")
            .writeBytes(codec.encodeToBytes(rolledBack))

        val error = assertThrows(VaultRollbackException::class.java) { store.read() }
        assertEquals(2L, error.storedRevision)
        assertEquals(1L, error.fileRevision)
    }

    // --- KDF parameter bounds -------------------------------------------------

    @Test
    fun `absurd iteration counts are rejected instead of pinning the cpu`() {
        val file = codec.createVault(master.copyOf(), answers, doc("A"))
        val hostile = file.copy(kdf = file.kdf.copy(masterIterations = Int.MAX_VALUE))
        assertThrows(VaultFormatException::class.java) {
            codec.unlockWithMaster(hostile, master.copyOf())
        }
    }

    @Test
    fun `iteration counts below the documented floor are rejected`() {
        val file = codec.createVault(master.copyOf(), answers, doc("A"))
        assertThrows(VaultFormatException::class.java) {
            codec.unlockWithMaster(file.copy(kdf = file.kdf.copy(masterIterations = 1)), master.copyOf())
        }
        assertThrows(VaultFormatException::class.java) {
            codec.unlockWithRecovery(file.copy(kdf = file.kdf.copy(recoveryIterations = 10)), answers)
        }
    }

    @Test
    fun `zero or negative iteration counts are a clean format error, not a crash`() {
        val file = codec.createVault(master.copyOf(), answers, doc("A"))
        assertThrows(VaultFormatException::class.java) {
            codec.unlockWithMaster(file.copy(kdf = file.kdf.copy(masterIterations = 0)), master.copyOf())
        }
        assertThrows(VaultFormatException::class.java) {
            codec.unlockWithMaster(file.copy(kdf = file.kdf.copy(masterIterations = -5)), master.copyOf())
        }
    }

    @Test
    fun `unknown kdf algorithm and key length are rejected`() {
        val file = codec.createVault(master.copyOf(), answers, doc("A"))
        assertThrows(VaultFormatException::class.java) {
            codec.unlockWithMaster(file.copy(kdf = file.kdf.copy(algorithm = "PBKDF2WithHmacMD5")), master.copyOf())
        }
        assertThrows(VaultFormatException::class.java) {
            codec.unlockWithMaster(file.copy(kdf = file.kdf.copy(keyLengthBits = 64)), master.copyOf())
        }
    }

    @Test
    fun `malformed salt is rejected`() {
        val file = codec.createVault(master.copyOf(), answers, doc("A"))
        assertThrows(VaultFormatException::class.java) {
            codec.unlockWithMaster(file.copy(masterSaltB64 = ""), master.copyOf())
        }
    }

    // --- KDF params stay in sync with the wrapper they describe ---------------

    @Test
    fun `rewrapping master refreshes only the master kdf params`() {
        val file = codec.createVault(master.copyOf(), answers, doc("A"))
        val dek = codec.unlockWithMaster(file, master.copyOf()).dek
        val stale = file.copy(
            kdf = file.kdf.copy(masterIterations = 250_000, recoveryIterations = 250_000),
        )
        val rewrapped = codec.rewrapMaster(stale, dek, "brand-new-master-2".toCharArray())

        assertEquals(CryptoConstants.PBKDF2_ITERATIONS_MASTER, rewrapped.kdf.masterIterations)
        assertEquals(250_000, rewrapped.kdf.recoveryIterations) // untouched wrapper
        assertTrue(
            codec.unlockWithMaster(rewrapped, "brand-new-master-2".toCharArray())
                .document.entries.isNotEmpty(),
        )
    }

    @Test
    fun `rewrapping recovery refreshes only the recovery kdf params`() {
        val file = codec.createVault(master.copyOf(), answers, doc("A"))
        val dek = codec.unlockWithMaster(file, master.copyOf()).dek
        val stale = file.copy(
            kdf = file.kdf.copy(masterIterations = 250_000, recoveryIterations = 250_000),
        )
        val newAnswers = listOf("q", "w", "e", "r", "1999")
        val rewrapped = codec.rewrapRecovery(stale, dek, newAnswers)

        assertEquals(250_000, rewrapped.kdf.masterIterations) // untouched wrapper
        assertEquals(CryptoConstants.PBKDF2_ITERATIONS_RECOVERY, rewrapped.kdf.recoveryIterations)
        assertTrue(codec.unlockWithRecovery(rewrapped, newAnswers).document.entries.isNotEmpty())
    }
}
