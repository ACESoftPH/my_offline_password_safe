package com.acesoft.offlinepasswordwallet.importexport

import com.acesoft.offlinepasswordwallet.data.model.VaultEntry
import com.acesoft.offlinepasswordwallet.data.model.VaultField

/** Result of parsing a CSV, shown on the confirmation screen before committing. */
data class CsvImportPreview(
    val fieldNames: List<String>,
    val entryCount: Int,
    val entries: List<VaultEntry>,
) {
    val fieldCount: Int get() = fieldNames.size
}

/**
 * Converts semicolon-delimited CSV text into [VaultEntry] objects (§16, §51).
 *
 *  - Row 1 is always the header = field definitions.
 *  - Every data row becomes one entry; every header column becomes a field on
 *    that entry (empty values preserved).
 *  - Columns beyond the six template fields automatically become custom fields
 *    (see [VaultField.custom], derived from the name).
 *  - Blank header cells are named `Column N`; duplicate header names are
 *    disambiguated with ` (2)`, ` (3)`, … so a single entry never carries two
 *    fields with the same name.
 *
 * The importer is pure: it does not touch the vault. The caller confirms, then
 * asks [com.acesoft.offlinepasswordwallet.data.repository.VaultRepository] to
 * persist — which immediately re-encrypts (§18).
 */
object CsvImporter {

    fun parse(content: String, delimiter: Char = Csv.DEFAULT_DELIMITER): CsvImportPreview {
        val rows = Csv.parse(content, delimiter)
        if (rows.isEmpty()) return CsvImportPreview(emptyList(), 0, emptyList())

        val header = normalizeHeader(rows.first())
        val entries = rows.drop(1)
            .filterNot { row -> row.all { it.isEmpty() } } // skip fully blank lines
            .map { row ->
                val fields = header.mapIndexed { i, name ->
                    // Reverses the spreadsheet formula-injection guard applied by
                    // CsvExporter, so our own export -> import round trip is lossless.
                    VaultField(name = name, value = Csv.unescapeFormula(row.getOrElse(i) { "" }))
                }
                VaultEntry(fields = fields)
            }

        return CsvImportPreview(header, entries.size, entries)
    }

    private fun normalizeHeader(raw: List<String>): List<String> {
        val seen = HashMap<String, Int>()
        return raw.mapIndexed { index, cellRaw ->
            val base = Csv.unescapeFormula(cellRaw).trim().ifEmpty { "Column ${index + 1}" }
            val count = seen.getOrDefault(base.lowercase(), 0) + 1
            seen[base.lowercase()] = count
            if (count == 1) base else "$base ($count)"
        }
    }
}
