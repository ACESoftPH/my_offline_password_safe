package com.example.offlinepasswordwallet.data.model

/** How a CSV import is merged into the existing vault (§16). */
enum class ImportMode {
    /** Append imported entries; keep everything already in the vault. */
    ADD,

    /** Discard all current entries and replace them with the imported set. */
    REPLACE,
}
