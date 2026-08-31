package com.example.offlinepasswordwallet.data.backup

import com.example.offlinepasswordwallet.crypto.BackupDecryptionException
import com.example.offlinepasswordwallet.crypto.BackupFormatException
import com.example.offlinepasswordwallet.crypto.Base64Util
import com.example.offlinepasswordwallet.data.model.DefaultFields
import com.example.offlinepasswordwallet.data.model.EncryptedBlobDto
import com.example.offlinepasswordwallet.data.model.VaultDocument
import com.example.offlinepasswordwallet.data.model.VaultEntry
import com.example.offlinepasswordwallet.data.model.VaultField
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupCodecTest {

    private val codec = BackupCodec()
    private val pass = "backup-passphrase-9".toCharArray()

    private fun doc(vararg titles: String) = VaultDocument(
        entries = titles.map { VaultEntry(fields = listOf(VaultField(DefaultFields.TITLE, it))) },
    )

    @Test
    fun `round trips the document`() {
        val bytes = codec.create(pass.copyOf(), doc("Gmail", "Bank"), "1.0.0")
        val file = codec.decode(bytes)
        val restored = codec.open(file, pass.copyOf())
        assertEquals(listOf("Gmail", "Bank"), restored.entries.map { it.value("Title") })
        assertEquals(2, file.entryCount)
        assertEquals(BackupCodec.MAGIC, file.magic)
    }

    @Test
    fun `wrong passphrase fails`() {
        val bytes = codec.create(pass.copyOf(), doc("X"), "1.0.0")
        val file = codec.decode(bytes)
        assertThrows(BackupDecryptionException::class.java) {
            codec.open(file, "not-the-passphrase".toCharArray())
        }
    }

    @Test
    fun `tampered payload fails authentication`() {
        val bytes = codec.create(pass.copyOf(), doc("X"), "1.0.0")
        val file = codec.decode(bytes)
        val raw = Base64Util.decode(file.payload.ciphertextB64)
        raw[0] = (raw[0] + 1).toByte()
        val tampered = file.copy(payload = EncryptedBlobDto(file.payload.ivB64, Base64Util.encode(raw)))
        assertThrows(BackupDecryptionException::class.java) { codec.open(tampered, pass.copyOf()) }
    }

    @Test
    fun `non-backup bytes are rejected`() {
        assertThrows(BackupFormatException::class.java) { codec.decode("hello world".toByteArray()) }
    }

    @Test
    fun `wrong magic is rejected`() {
        val bytes = codec.create(pass.copyOf(), doc("X"), "1.0.0")
        val swapped = String(bytes).replace(BackupCodec.MAGIC, "SOME-OTHER-APP").toByteArray()
        assertThrows(BackupFormatException::class.java) { codec.decode(swapped) }
    }

    @Test
    fun `each export uses a fresh salt and iv`() {
        val salts = HashSet<String>()
        val ivs = HashSet<String>()
        repeat(25) {
            val f = codec.decode(codec.create(pass.copyOf(), doc("X"), "1.0.0"))
            salts.add(f.saltB64)
            ivs.add(f.payload.ivB64)
        }
        assertEquals(25, salts.size)
        assertEquals(25, ivs.size)
    }

    @Test
    fun `empty and large vaults round trip`() {
        val empty = codec.open(codec.decode(codec.create(pass.copyOf(), VaultDocument(), "1")), pass.copyOf())
        assertTrue(empty.entries.isEmpty())

        val big = VaultDocument(
            entries = (1..500).map {
                VaultEntry(fields = listOf(VaultField(DefaultFields.TITLE, "E$it"),
                    VaultField(DefaultFields.PASSWORD, "p".repeat(40))))
            },
        )
        val backBig = codec.open(codec.decode(codec.create(pass.copyOf(), big, "1")), pass.copyOf())
        assertEquals(500, backBig.entries.size)
    }

    @Test
    fun `backup ciphertext does not contain a known plaintext value`() {
        val bytes = codec.create(pass.copyOf(), doc("SECRET-TITLE-MARKER"), "1.0.0")
        assertNotEquals(-1, bytes.size)
        assertTrue(!String(bytes, Charsets.ISO_8859_1).contains("SECRET-TITLE-MARKER"))
    }
}
