package com.example.offlinepasswordwallet.data.model

import kotlinx.serialization.Serializable
import java.util.UUID

/**
 * The decrypted vault. This object only ever exists in memory while the vault is
 * unlocked; it is serialized to JSON, encrypted, and the plaintext JSON/bytes are
 * cleared as soon as possible (see VaultRepository / VaultCrypto).
 */
@Serializable
data class VaultDocument(
    val formatVersion: Int = 1,
    val entries: List<VaultEntry> = emptyList(),
)

@Serializable
data class VaultEntry(
    val id: String = UUID.randomUUID().toString(),
    val fields: List<VaultField> = emptyList(),
    val createdAtEpochMillis: Long = System.currentTimeMillis(),
    val updatedAtEpochMillis: Long = System.currentTimeMillis(),
) {
    fun value(fieldName: String): String? =
        fields.firstOrNull { it.name.equals(fieldName, ignoreCase = true) }?.value

    /** Best identifying label for list rows: Title, else first non-blank field, else "(no title)". */
    fun displayTitle(): String {
        val title = value(DefaultFields.TITLE)?.takeIf { it.isNotBlank() }
        if (title != null) return title
        return fields.firstOrNull { it.value.isNotBlank() }?.value ?: "(no title)"
    }

    fun displaySubtitle(): String? =
        value(DefaultFields.USERNAME)?.takeIf { it.isNotBlank() }
            ?: value(DefaultFields.CATEGORY)?.takeIf { it.isNotBlank() }
}

@Serializable
data class VaultField(
    val name: String,
    val value: String,
    /**
     * Marks a field whose value should be masked in the UI and cleared from the
     * clipboard aggressively. Any field named "Password" is sensitive; custom
     * fields can opt in too (e.g. "PIN", "Security Code").
     */
    val sensitive: Boolean = name.equals(DefaultFields.PASSWORD, ignoreCase = true),
    /** false for the six template fields, true for user-added custom fields. */
    val custom: Boolean = !DefaultFields.ALL.any { it.equals(name, ignoreCase = true) },
)

/** The six fields defined by the supplied PasswordSafe_template.csv header. */
object DefaultFields {
    const val TITLE = "Title"
    const val CATEGORY = "Category"
    const val USERNAME = "Username"
    const val PASSWORD = "Password"
    const val WEBSITE = "Website"
    const val COMMENTS = "Comments"

    /** Order matters: this is the canonical column order for CSV export. */
    val ALL: List<String> = listOf(TITLE, CATEGORY, USERNAME, PASSWORD, WEBSITE, COMMENTS)

    fun newEntryTemplate(): List<VaultField> = ALL.map { VaultField(name = it, value = "") }
}
