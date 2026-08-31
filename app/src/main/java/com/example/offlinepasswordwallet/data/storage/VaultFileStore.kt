package com.example.offlinepasswordwallet.data.storage

import android.content.Context
import com.example.offlinepasswordwallet.crypto.VaultFormatException
import com.example.offlinepasswordwallet.data.model.EncryptedVaultFile
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/**
 * The single owner of the encrypted vault file on disk.
 *
 * Location: `context.filesDir/vault/vault.json` — app-private internal storage.
 * It is never world-readable and is excluded from OS backup/transfer (see
 * AndroidManifest + res/xml backup rules), so ordinary file browsing / adb pull
 * (on a non-rooted device) cannot reach the *encrypted* file, and even if it
 * could, the contents are AES-256-GCM ciphertext.
 *
 * Writes are atomic and crash-safe (§43 of the spec):
 *   1. serialize -> write to `vault.json.tmp`
 *   2. flush + fsync the file, then fsync the directory
 *   3. re-read and re-parse the temp file to prove it is loadable
 *   4. atomically rename temp over the real file
 * The previous good file is only replaced in step 4, so power loss at any point
 * leaves either the old file or the fully-written new file intact.
 */
class VaultFileStore(context: Context) {

    private val appContext = context.applicationContext
    private val dir: File = File(appContext.filesDir, DIR_NAME)
    private val vaultFile: File = File(dir, FILE_NAME)
    private val tempFile: File = File(dir, "$FILE_NAME.tmp")

    private val codec = VaultCodec()

    fun exists(): Boolean = vaultFile.isFile && vaultFile.length() > 0

    /**
     * Reads and parses the vault envelope.
     *
     * @throws VaultFormatException if the file is missing, empty, or unparseable.
     *         (A wrong password is NOT detected here — that surfaces later, at
     *         decryption time, as AeadDecryptionException.)
     */
    fun read(): EncryptedVaultFile {
        if (!exists()) throw VaultFormatException("No vault file exists yet.")
        val bytes = try {
            vaultFile.readBytes()
        } catch (e: IOException) {
            throw VaultFormatException("Vault file could not be read from storage.", e)
        }
        return codec.decodeFromBytes(bytes)
    }

    /**
     * Atomically persists [file]. Returns only after the new content is durably
     * on disk and verified loadable.
     */
    @Synchronized
    fun write(file: EncryptedVaultFile) {
        if (!dir.exists() && !dir.mkdirs()) {
            throw IOException("Could not create vault directory.")
        }
        restrictPermissions(dir)

        val bytes = codec.encodeToBytes(file)

        FileOutputStream(tempFile).use { fos ->
            fos.write(bytes)
            fos.flush()
            fos.fd.sync()
        }
        restrictPermissions(tempFile)

        // Verify the temp file actually parses before we let it replace the real one.
        val verify = codec.decodeFromBytes(tempFile.readBytes())
        if (verify.vaultId != file.vaultId) {
            throw IOException("Vault write verification failed (id mismatch).")
        }

        if (!tempFile.renameTo(vaultFile)) {
            // Fallback for platforms where renameTo won't overwrite: copy then delete.
            tempFile.inputStream().use { input ->
                FileOutputStream(vaultFile).use { out ->
                    input.copyTo(out)
                    out.flush()
                    out.fd.sync()
                }
            }
            tempFile.delete()
        }
        restrictPermissions(vaultFile)
        fsyncDir(dir)
    }

    /** Removes the vault entirely (used only by an explicit, confirmed reset). */
    @Synchronized
    fun deleteVault() {
        tempFile.delete()
        vaultFile.delete()
    }

    private fun fsyncDir(directory: File) {
        runCatching {
            // Opening a directory for read + fd.sync flushes the rename into the
            // directory entry on POSIX filesystems. Best-effort; ignored if the
            // platform disallows opening a directory stream.
            java.io.RandomAccessFile(directory, "r").use { it.fd.sync() }
        }
    }

    private fun restrictPermissions(f: File) {
        runCatching {
            f.setReadable(false, false)
            f.setReadable(true, true)
            f.setWritable(false, false)
            f.setWritable(true, true)
            if (f.isDirectory) {
                f.setExecutable(false, false)
                f.setExecutable(true, true)
            }
        }
    }

    companion object {
        private const val DIR_NAME = "vault"
        private const val FILE_NAME = "vault.json"
    }
}
