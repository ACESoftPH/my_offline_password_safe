package com.example.offlinepasswordwallet.importexport

import com.example.offlinepasswordwallet.data.model.DefaultFields
import com.example.offlinepasswordwallet.data.model.VaultEntry

/**
 * Serializes the whole vault to semicolon-delimited CSV (§17).
 *
 * The column set is the UNION of all field names across all entries: the six
 * template fields first (fixed order), then every custom field in first-seen
 * order. Entries missing a column export an empty value for it.
 *
 * The output is intentionally PLAINTEXT — CSV interoperability requires it. The
 * UI forces an explicit "this file is not encrypted" confirmation before this is
 * ever called, and the bytes are handed straight to a user-chosen SAF URI; the
 * app keeps no copy and never transmits it (§18).
 */
object CsvExporter {

    fun export(entries: List<VaultEntry>, delimiter: Char = Csv.DEFAULT_DELIMITER): String {
        val columns = LinkedHashSet<String>().apply {
            addAll(DefaultFields.ALL)
            entries.forEach { entry -> entry.fields.forEach { add(it.name) } }
        }.toList()

        val rows = ArrayList<List<String>>(entries.size + 1)
        rows.add(columns)
        for (entry in entries) {
            rows.add(columns.map { name -> entry.value(name) ?: "" })
        }
        return Csv.write(rows, delimiter)
    }
}
