package com.aldinson.offlinepasswordwallet.crypto

import com.aldinson.offlinepasswordwallet.data.model.EncryptedBlobDto

/**
 * In-memory pairing of an AES-GCM IV with its ciphertext (GCM tag appended to
 * [ciphertext]). Kept separate from the serializable [EncryptedBlobDto] so crypto
 * code never depends on the storage/serialization layer.
 */
class EncryptedBlob(
    val iv: ByteArray,
    val ciphertext: ByteArray,
) {
    fun toDto(): EncryptedBlobDto =
        EncryptedBlobDto(
            ivB64 = Base64Util.encode(iv),
            ciphertextB64 = Base64Util.encode(ciphertext),
        )

    companion object {
        fun fromDto(dto: EncryptedBlobDto): EncryptedBlob =
            EncryptedBlob(
                iv = Base64Util.decode(dto.ivB64),
                ciphertext = Base64Util.decode(dto.ciphertextB64),
            )
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is EncryptedBlob) return false
        return iv.contentEquals(other.iv) && ciphertext.contentEquals(other.ciphertext)
    }

    override fun hashCode(): Int = 31 * iv.contentHashCode() + ciphertext.contentHashCode()
}
