package com.acesoft.offlinepasswordwallet.crypto

import com.acesoft.offlinepasswordwallet.data.model.VaultDocument
import javax.crypto.SecretKey

/**
 * The result of a successful unlock: the recovered Data Encryption Key plus the
 * decrypted [VaultDocument]. Both are memory-only and must be dropped when the
 * app locks.
 */
class UnlockedVault(
    val dek: SecretKey,
    val document: VaultDocument,
)
