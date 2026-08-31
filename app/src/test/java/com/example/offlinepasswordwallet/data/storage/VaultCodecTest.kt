package com.example.offlinepasswordwallet.data.storage

import com.example.offlinepasswordwallet.crypto.AeadDecryptionException
import com.example.offlinepasswordwallet.crypto.Base64Util
import com.example.offlinepasswordwallet.crypto.VaultFormatException
import com.example.offlinepasswordwallet.data.model.DefaultFields
import com.example.offlinepasswordwallet.data.model.EncryptedBlobDto
import com.example.offlinepasswordwallet.data.model.VaultDocument
import com.example.offlinepasswordwallet.data.model.VaultEntry
import com.example.offlinepasswordwallet.data.model.VaultField
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class VaultCodecTest {

    private val codec = VaultCodec()
    private val master = "correct horse battery staple".toCharArray()
    private val answers = listOf("First School", "Rex", "Smith", "Danger", "2009")

    private fun docWith(vararg titles: String) = VaultDocument(
        entries = titles.map { t ->
            VaultEntry(fields = listOf(VaultField(DefaultFields.TITLE, t)))
        },
    )

    @Test
    fun `create then unlock with master`() {
        val file = codec.createVault(master.copyOf(), answers, docWith("Gmail", "Bank"))
        val unlocked = codec.unlockWithMaster(file, master.copyOf())
        assertEquals(listOf("Gmail", "Bank"), unlocked.document.entries.map { it.value("Title") })
    }

    @Test
    fun `unlock with recovery answers`() {
        val file = codec.createVault(master.copyOf(), answers, docWith("X"))
        val unlocked = codec.unlockWithRecovery(file, answers)
        assertEquals("X", unlocked.document.entries.single().value("Title"))
    }

    @Test
    fun `recovery answers are case and whitespace insensitive`() {
        val file = codec.createVault(master.copyOf(), answers, docWith("X"))
        val messy = listOf("  first school ", "REX", "smith", "  danger", "2009 ")
        assertEquals("X", codec.unlockWithRecovery(file, messy).document.entries.single().value("Title"))
    }

    @Test
    fun `wrong master password fails`() {
        val file = codec.createVault(master.copyOf(), answers, docWith("X"))
        assertThrows(AeadDecryptionException::class.java) {
            codec.unlockWithMaster(file, "wrong password".toCharArray())
        }
    }

    @Test
    fun `wrong recovery answers fail`() {
        val file = codec.createVault(master.copyOf(), answers, docWith("X"))
        assertThrows(AeadDecryptionException::class.java) {
            codec.unlockWithRecovery(file, listOf("a", "b", "c", "d", "e"))
        }
    }

    @Test
    fun `partial recovery answers fail`() {
        val file = codec.createVault(master.copyOf(), answers, docWith("X"))
        val partial = listOf("First School", "Rex", "Smith", "Danger", "wrong-year")
        assertThrows(AeadDecryptionException::class.java) {
            codec.unlockWithRecovery(file, partial)
        }
    }

    @Test
    fun `rewrap master - old password stops working, new works, entries preserved`() {
        var file = codec.createVault(master.copyOf(), answers, docWith("Keep me"))
        val dek = codec.unlockWithMaster(file, master.copyOf()).dek

        val newMaster = "a brand new much longer master phrase".toCharArray()
        file = codec.rewrapMaster(file, dek, newMaster.copyOf())

        assertThrows(AeadDecryptionException::class.java) {
            codec.unlockWithMaster(file, master.copyOf())
        }
        val unlocked = codec.unlockWithMaster(file, newMaster.copyOf())
        assertEquals("Keep me", unlocked.document.entries.single().value("Title"))
        // recovery still works after master change
        assertEquals("Keep me", codec.unlockWithRecovery(file, answers).document.entries.single().value("Title"))
    }

    @Test
    fun `rewrap recovery - old answers stop working, new answers work`() {
        var file = codec.createVault(master.copyOf(), answers, docWith("Data"))
        val dek = codec.unlockWithMaster(file, master.copyOf()).dek
        val newAnswers = listOf("Other", "Milo", "Jones", "Quiet", "2015")
        file = codec.rewrapRecovery(file, dek, newAnswers)

        assertThrows(AeadDecryptionException::class.java) { codec.unlockWithRecovery(file, answers) }
        assertEquals("Data", codec.unlockWithRecovery(file, newAnswers).document.entries.single().value("Title"))
        // master still works
        assertEquals("Data", codec.unlockWithMaster(file, master.copyOf()).document.entries.single().value("Title"))
    }

    @Test
    fun `updateDocument re-seals payload with a fresh iv`() {
        var file = codec.createVault(master.copyOf(), answers, docWith("One"))
        val iv1 = file.payload.ivB64
        val dek = codec.unlockWithMaster(file, master.copyOf()).dek
        file = codec.updateDocument(file, dek, docWith("One", "Two"))
        assertNotEquals(iv1, file.payload.ivB64)
        assertEquals(
            listOf("One", "Two"),
            codec.unlockWithMaster(file, master.copyOf()).document.entries.map { it.value("Title") },
        )
    }

    @Test
    fun `empty vault round trips`() {
        val file = codec.createVault(master.copyOf(), answers, VaultDocument())
        assertTrue(codec.unlockWithMaster(file, master.copyOf()).document.entries.isEmpty())
    }

    @Test
    fun `large vault round trips`() {
        val big = VaultDocument(entries = (1..1_000).map {
            VaultEntry(fields = listOf(
                VaultField(DefaultFields.TITLE, "Entry $it"),
                VaultField(DefaultFields.PASSWORD, "p".repeat(64)),
                VaultField("Note $it", "x".repeat(200), custom = true),
            ))
        })
        val file = codec.createVault(master.copyOf(), answers, big)
        val out = codec.unlockWithMaster(file, master.copyOf()).document
        assertEquals(1_000, out.entries.size)
        assertEquals("Entry 1000", out.entries.last().value("Title"))
    }

    @Test
    fun `corrupted payload is detected, not silently ignored`() {
        val file = codec.createVault(master.copyOf(), answers, docWith("Safe"))
        val raw = Base64Util.decode(file.payload.ciphertextB64)
        raw[raw.size / 2] = (raw[raw.size / 2] + 1).toByte()
        val corrupted = file.copy(
            payload = EncryptedBlobDto(file.payload.ivB64, Base64Util.encode(raw)),
        )
        assertThrows(AeadDecryptionException::class.java) {
            codec.unlockWithMaster(corrupted, master.copyOf())
        }
    }

    @Test
    fun `unparseable bytes throw VaultFormatException`() {
        assertThrows(VaultFormatException::class.java) {
            codec.decodeFromBytes("not json at all".toByteArray())
        }
    }

    @Test
    fun `encode decode envelope round trips`() {
        val file = codec.createVault(master.copyOf(), answers, docWith("Rt"))
        val restored = codec.decodeFromBytes(codec.encodeToBytes(file))
        assertEquals(file.vaultId, restored.vaultId)
        assertArrayEquals(
            Base64Util.decode(file.payload.ciphertextB64),
            Base64Util.decode(restored.payload.ciphertextB64),
        )
        assertEquals("Rt", codec.unlockWithMaster(restored, master.copyOf()).document.entries.single().value("Title"))
    }
}
