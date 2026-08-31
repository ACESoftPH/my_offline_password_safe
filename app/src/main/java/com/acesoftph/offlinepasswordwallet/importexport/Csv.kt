package com.acesoftph.offlinepasswordwallet.importexport

/**
 * A small, correct, dependency-free CSV engine (§37).
 *
 * NOT a `split(";")`. It is a character-level state machine implementing
 * RFC-4180 semantics with a configurable delimiter (default `;` for the supplied
 * PasswordSafe template) and handles:
 *  - quoted fields;
 *  - the delimiter inside quoted fields;
 *  - quote characters inside quoted fields, escaped by doubling (`""`);
 *  - newlines (LF, CR, CRLF) inside quoted fields;
 *  - mixed / non-standard line endings between records;
 *  - empty fields and empty trailing lines;
 *  - a leading UTF-8 BOM;
 *  - arbitrary Unicode content.
 */
object Csv {

    const val DEFAULT_DELIMITER = ';'
    private const val QUOTE = '"'
    private const val CR = '\r'
    private const val LF = '\n'
    private val BOM: Char = 0xFEFF.toChar() // UTF-8/UTF-16 byte-order mark

    /** Parses [text] into rows of string fields. Blank lines produce no row. */
    fun parse(text: String, delimiter: Char = DEFAULT_DELIMITER): List<List<String>> {
        val rows = ArrayList<List<String>>()
        var row = ArrayList<String>()
        val field = StringBuilder()
        var inQuotes = false
        var fieldStarted = false // did this record contain any content/field at all?
        // Per RFC 4180 a quote only opens a quoted section at the START of a
        // field. Once the field has any content, a quote is ordinary data. Without
        // this flag a stray `"` inside an unquoted value (e.g. the password
        // `pa"ss`) would swallow every following delimiter and newline and merge
        // the rest of the file into one field.
        var fieldHasContent = false

        var i = 0
        val n = text.length
        if (n > 0 && text[0] == BOM) i = 1

        fun endField() {
            row.add(field.toString())
            field.setLength(0)
            fieldStarted = true
            fieldHasContent = false
        }

        fun endRecord() {
            // Emit the record only if it's non-empty (has fields or content).
            if (fieldStarted || field.isNotEmpty() || row.isNotEmpty()) {
                endField()
                rows.add(row)
            }
            row = ArrayList()
            fieldStarted = false
            fieldHasContent = false
        }

        while (i < n) {
            val c = text[i]
            if (inQuotes) {
                when (c) {
                    QUOTE -> {
                        if (i + 1 < n && text[i + 1] == QUOTE) {
                            field.append(QUOTE)
                            i++
                        } else {
                            inQuotes = false
                        }
                    }
                    else -> field.append(c)
                }
            } else {
                when (c) {
                    QUOTE -> {
                        if (fieldHasContent) {
                            // Mid-field quote: literal data, not a quoted section.
                            field.append(c)
                        } else {
                            inQuotes = true
                            fieldStarted = true
                            fieldHasContent = true
                        }
                    }
                    delimiter -> endField()
                    CR -> {
                        endRecord()
                        if (i + 1 < n && text[i + 1] == LF) i++
                    }
                    LF -> endRecord()
                    else -> {
                        field.append(c)
                        fieldStarted = true
                        fieldHasContent = true
                    }
                }
            }
            i++
        }
        // Flush the final record if the file didn't end with a newline.
        if (field.isNotEmpty() || row.isNotEmpty() || fieldStarted) {
            endField()
            rows.add(row)
        }
        return rows
    }

    /**
     * Serializes [rows] back to CSV text. A field is quoted when it contains the
     * delimiter, a quote, CR or LF; embedded quotes are doubled. Records are
     * separated with CRLF for maximum spreadsheet compatibility.
     */
    fun write(rows: List<List<String>>, delimiter: Char = DEFAULT_DELIMITER): String {
        val sb = StringBuilder()
        for ((index, row) in rows.withIndex()) {
            if (index > 0) sb.append(CR).append(LF)
            for ((col, raw) in row.withIndex()) {
                if (col > 0) sb.append(delimiter)
                sb.append(encodeField(raw, delimiter))
            }
        }
        return sb.toString()
    }

    /**
     * Leading characters that make Excel / LibreOffice / Sheets treat a cell as a
     * formula rather than text (CSV injection, OWASP "Formula Injection").
     */
    private val FORMULA_TRIGGERS = charArrayOf('=', '+', '-', '@', '\t', '\r')

    private fun startsWithFormulaTrigger(value: String): Boolean =
        value.isNotEmpty() && value[0] in FORMULA_TRIGGERS

    /**
     * Neutralizes a value that a spreadsheet would otherwise execute, by prefixing
     * an apostrophe (the standard "treat as text" marker). Applied on export only;
     * [unescapeFormula] reverses it on import, so an Offline Password Wallet
     * export → import round trip is lossless.
     */
    fun escapeFormula(value: String): String =
        if (startsWithFormulaTrigger(value)) "'$value" else value

    /** Inverse of [escapeFormula]. Leaves any other leading apostrophe alone. */
    fun unescapeFormula(value: String): String =
        if (value.length >= 2 && value[0] == '\'' && value[1] in FORMULA_TRIGGERS) {
            value.substring(1)
        } else {
            value
        }

    private fun encodeField(value: String, delimiter: Char): String {
        val mustQuote = value.any { it == delimiter || it == QUOTE || it == CR || it == LF }
        if (!mustQuote) return value
        val escaped = value.replace("\"", "\"\"")
        return "\"$escaped\""
    }
}
