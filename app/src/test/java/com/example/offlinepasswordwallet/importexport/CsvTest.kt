package com.example.offlinepasswordwallet.importexport

import com.example.offlinepasswordwallet.data.model.DefaultFields
import com.example.offlinepasswordwallet.data.model.VaultEntry
import com.example.offlinepasswordwallet.data.model.VaultField
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CsvTest {

    @Test
    fun `parses semicolon-delimited rows`() {
        val rows = Csv.parse("a;b;c\n1;2;3")
        assertEquals(listOf(listOf("a", "b", "c"), listOf("1", "2", "3")), rows)
    }

    @Test
    fun `does not split on comma`() {
        val rows = Csv.parse("a,b;c")
        assertEquals(listOf(listOf("a,b", "c")), rows)
    }

    @Test
    fun `header extraction via importer`() {
        val preview = CsvImporter.parse("Title;Category;Username;Password;Website;Comments\nx;y;z;p;w;c")
        assertEquals(DefaultFields.ALL, preview.fieldNames)
        assertEquals(1, preview.entryCount)
    }

    @Test
    fun `preserves empty fields`() {
        val preview = CsvImporter.parse("Title;Category;Username\nOnlyTitle;;")
        val entry = preview.entries.single()
        assertEquals("OnlyTitle", entry.value("Title"))
        assertEquals("", entry.value("Category"))
        assertEquals("", entry.value("Username"))
    }

    @Test
    fun `extra headers become custom fields`() {
        val preview = CsvImporter.parse(
            "Title;Category;Username;Password;Website;Comments;PIN;Account Number\n" +
                "t;c;u;p;w;m;1234;000-1",
        )
        assertTrue("PIN" in preview.fieldNames)
        val entry = preview.entries.single()
        val pin = entry.fields.first { it.name == "PIN" }
        assertTrue(pin.custom)
        assertEquals("1234", pin.value)
    }

    @Test
    fun `multiple rows`() {
        val preview = CsvImporter.parse("Title\nA\nB\nC")
        assertEquals(3, preview.entryCount)
        assertEquals(listOf("A", "B", "C"), preview.entries.map { it.value("Title") })
    }

    @Test
    fun `quoted field containing the delimiter`() {
        val rows = Csv.parse("\"a;b\";c")
        assertEquals(listOf(listOf("a;b", "c")), rows)
    }

    @Test
    fun `escaped quotes inside quoted field`() {
        val rows = Csv.parse("\"she said \"\"hi\"\"\";x")
        assertEquals(listOf(listOf("she said \"hi\"", "x")), rows)
    }

    @Test
    fun `newline inside quoted field`() {
        val rows = Csv.parse("\"line1\nline2\";next")
        assertEquals(listOf(listOf("line1\nline2", "next")), rows)
    }

    @Test
    fun `handles CRLF and lone CR line endings`() {
        assertEquals(
            listOf(listOf("a", "b"), listOf("c", "d")),
            Csv.parse("a;b\r\nc;d"),
        )
        assertEquals(
            listOf(listOf("a"), listOf("b")),
            Csv.parse("a\rb"),
        )
    }

    @Test
    fun `unicode content survives`() {
        val preview = CsvImporter.parse("Title;Comments\nПароль;naïve café — 日本語 🔐")
        assertEquals("naïve café — 日本語 🔐", preview.entries.single().value("Comments"))
    }

    @Test
    fun `strips leading BOM`() {
        val rows = Csv.parse("﻿Title;Category\nx;y")
        assertEquals("Title", rows.first().first())
    }

    @Test
    fun `export writes union of default and custom fields`() {
        val entries = listOf(
            VaultEntry(fields = DefaultFields.ALL.map { VaultField(it, "d_$it") } +
                VaultField("PIN", "999", custom = true)),
            VaultEntry(fields = DefaultFields.ALL.map { VaultField(it, "e2") }),
        )
        val csv = CsvExporter.export(entries)
        val rows = Csv.parse(csv)
        assertEquals(DefaultFields.ALL + "PIN", rows.first())
        // second entry has empty PIN column
        assertEquals("", rows[2].last())
    }

    @Test
    fun `export quotes values containing the delimiter or quotes or newline`() {
        val entries = listOf(
            VaultEntry(fields = listOf(VaultField("Title", "a;b"), VaultField("Comments", "he said \"hi\"\nbye"))),
        )
        val csv = CsvExporter.export(entries)
        assertTrue(csv.contains("\"a;b\""))
        assertTrue(csv.contains("\"he said \"\"hi\"\"\nbye\""))
    }

    @Test
    fun `export then import round trip preserves data`() {
        val original = listOf(
            VaultEntry(fields = listOf(
                VaultField("Title", "Acct;1"),
                VaultField("Category", ""),
                VaultField("Username", "user \"quote\""),
                VaultField("Password", "p@ss;word"),
                VaultField("Website", "https://x.example"),
                VaultField("Comments", "multi\nline"),
                VaultField("PIN", "0000", custom = true),
            )),
        )
        val csv = CsvExporter.export(original)
        val back = CsvImporter.parse(csv).entries

        assertEquals(1, back.size)
        val a = back.single()
        assertEquals("Acct;1", a.value("Title"))
        assertEquals("user \"quote\"", a.value("Username"))
        assertEquals("p@ss;word", a.value("Password"))
        assertEquals("multi\nline", a.value("Comments"))
        assertEquals("0000", a.value("PIN"))
    }

    @Test
    fun `duplicate header names are disambiguated`() {
        val preview = CsvImporter.parse("Title;Title;title\na;b;c")
        assertEquals(listOf("Title", "Title (2)", "title (3)"), preview.fieldNames)
    }

    @Test
    fun `blank header cells are named`() {
        val preview = CsvImporter.parse("Title;;Website\na;b;c")
        assertEquals(listOf("Title", "Column 2", "Website"), preview.fieldNames)
    }

    // --- regression: a quote may only OPEN a quoted section at field start -----

    @Test
    fun `a lone quote inside an unquoted field is literal data`() {
        // Previously this switched the parser into quoted mode and swallowed every
        // following delimiter and newline, merging the rest of the file into one field.
        val rows = Csv.parse("site;pa\"ss;note\nrow2a;row2b;row2c")
        assertEquals(listOf("site", "pa\"ss", "note"), rows[0])
        assertEquals(listOf("row2a", "row2b", "row2c"), rows[1])
        assertEquals(2, rows.size)
    }

    @Test
    fun `unterminated mid-field quote does not eat the rest of the file`() {
        val preview = CsvImporter.parse("Title;Password\nGmail;pa\"ss\nBank;other")
        assertEquals(2, preview.entryCount)
        assertEquals("pa\"ss", preview.entries[0].value("Password"))
        assertEquals("other", preview.entries[1].value("Password"))
    }

    @Test
    fun `a quote at field start still opens a quoted section`() {
        assertEquals(listOf(listOf("a;b", "c")), Csv.parse("\"a;b\";c"))
    }

    @Test
    fun `characters after a closing quote are appended, not re-quoted`() {
        assertEquals(listOf(listOf("abtail", "next")), Csv.parse("\"ab\"tail;next"))
    }

    // --- regression: spreadsheet formula injection ----------------------------

    @Test
    fun `export neutralizes values a spreadsheet would execute`() {
        val entries = listOf(
            VaultEntry(fields = listOf(
                VaultField("Title", "=1+1"),
                VaultField("Comments", "@SUM(A1)"),
                VaultField("Username", "+cmd"),
                VaultField("Website", "-2+3"),
            )),
        )
        val rows = Csv.parse(CsvExporter.export(entries))
        val header = rows.first()
        val data = rows[1]
        assertEquals("'=1+1", data[header.indexOf("Title")])
        assertEquals("'@SUM(A1)", data[header.indexOf("Comments")])
        assertEquals("'+cmd", data[header.indexOf("Username")])
        assertEquals("'-2+3", data[header.indexOf("Website")])
    }

    @Test
    fun `formula neutralization round trips losslessly through our own importer`() {
        val entries = listOf(
            VaultEntry(fields = listOf(
                VaultField("Title", "=1+1"),
                VaultField("Password", "-secret-"),
                VaultField("Comments", "not a formula"),
            )),
        )
        val back = CsvImporter.parse(CsvExporter.export(entries)).entries.single()
        assertEquals("=1+1", back.value("Title"))
        assertEquals("-secret-", back.value("Password"))
        assertEquals("not a formula", back.value("Comments"))
    }

    @Test
    fun `an ordinary leading apostrophe is preserved`() {
        assertEquals("'quoted", Csv.unescapeFormula("'quoted"))
        assertEquals("'", Csv.unescapeFormula("'"))
        val entries = listOf(VaultEntry(fields = listOf(VaultField("Title", "'apostrophe"))))
        assertEquals(
            "'apostrophe",
            CsvImporter.parse(CsvExporter.export(entries)).entries.single().value("Title"),
        )
    }
}
