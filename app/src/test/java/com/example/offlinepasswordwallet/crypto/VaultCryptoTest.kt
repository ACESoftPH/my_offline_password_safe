package com.example.offlinepasswordwallet.crypto

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import javax.crypto.spec.SecretKeySpec

class VaultCryptoTest {

    private val crypto = VaultCrypto()
    private fun key(byte: Int) = SecretKeySpec(ByteArray(32) { byte.toByte() }, "AES")

    @Test
    fun `encrypt then decrypt round trips`() {
        val k = crypto.generateDek()
        val plaintext = "top secret ☃ 数据".toByteArray()
        val blob = crypto.encrypt(k, plaintext)
        assertArrayEquals(plaintext, crypto.decrypt(k, blob))
    }

    @Test
    fun `wrong key fails authentication`() {
        val blob = crypto.encrypt(key(1), "data".toByteArray())
        assertThrows(AeadDecryptionException::class.java) { crypto.decrypt(key(2), blob) }
    }

    @Test
    fun `modified ciphertext fails authentication`() {
        val k = key(7)
        val blob = crypto.encrypt(k, "important".toByteArray())
        val tampered = blob.ciphertext.copyOf().also { it[0] = (it[0] + 1).toByte() }
        assertThrows(AeadDecryptionException::class.java) {
            crypto.decrypt(k, EncryptedBlob(blob.iv, tampered))
        }
    }

    @Test
    fun `modified associated data fails authentication`() {
        val k = key(9)
        val blob = crypto.encrypt(k, "payload".toByteArray(), associatedData = "aad-v1".toByteArray())
        assertArrayEquals("payload".toByteArray(),
            crypto.decrypt(k, blob, associatedData = "aad-v1".toByteArray()))
        assertThrows(AeadDecryptionException::class.java) {
            crypto.decrypt(k, blob, associatedData = "aad-v2".toByteArray())
        }
    }

    @Test
    fun `each encryption uses a unique iv`() {
        val k = key(3)
        val ivs = (1..200).map { Base64Util.encode(crypto.encrypt(k, "x".toByteArray()).iv) }.toSet()
        assertEquals(200, ivs.size)
    }

    @Test
    fun `pbkdf2 is deterministic for same inputs and differs across salts`() {
        val salt = ByteArray(16) { 1 }
        val a = crypto.deriveKey("password".toCharArray(), salt, 50_000).encoded
        val b = crypto.deriveKey("password".toCharArray(), salt, 50_000).encoded
        val c = crypto.deriveKey("password".toCharArray(), ByteArray(16) { 2 }, 50_000).encoded
        assertArrayEquals(a, b)
        assertFalse(a.contentEquals(c))
        assertEquals(32, a.size)
    }

    @Test
    fun `wrap and unwrap dek round trips, wrong wrapping key fails`() {
        val dek = crypto.generateDek()
        val kek = key(42)
        val wrapped = crypto.wrapDek(kek, dek)
        assertArrayEquals(dek.encoded, crypto.unwrapDek(kek, wrapped).encoded)
        assertThrows(AeadDecryptionException::class.java) { crypto.unwrapDek(key(43), wrapped) }
    }

    @Test
    fun `empty plaintext round trips`() {
        val k = crypto.generateDek()
        assertArrayEquals(ByteArray(0), crypto.decrypt(k, crypto.encrypt(k, ByteArray(0))))
    }

    @Test
    fun `large plaintext round trips`() {
        val k = crypto.generateDek()
        val big = ByteArray(2_000_000) { (it % 251).toByte() }
        assertArrayEquals(big, crypto.decrypt(k, crypto.encrypt(k, big)))
    }

    @Test
    fun `ciphertext does not contain plaintext`() {
        val k = crypto.generateDek()
        val secret = "SUPERSECRETVALUE".toByteArray()
        val ct = crypto.encrypt(k, secret).ciphertext
        assertNotEquals(-1, ct.size)
        assertFalse(String(ct, Charsets.ISO_8859_1).contains("SUPERSECRETVALUE"))
    }
}
