package com.grocerylistbuilder.android.ingest.documents

import android.util.Xml
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.util.zip.ZipInputStream
import org.xmlpull.v1.XmlPullParser

/**
 * DOCX -> text (mirrors the `python-docx` branch of grocery/ingest/documents.py `extract_text`).
 * No Android port of `python-docx` exists, and Apache POI is heavy with known ART friction, so
 * this hand-rolls the two things a .docx actually needs here: it's a zip containing
 * `word/document.xml`, an OOXML document whose paragraphs/text runs are `<w:p>`/`<w:t>`
 * elements — walked with the SDK-bundled [Xml.newPullParser], no extra dependency.
 */
object DocxTextExtractor {

    fun extractText(data: ByteArray): String {
        ZipInputStream(ByteArrayInputStream(data)).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                if (entry.name == "word/document.xml") return parseParagraphs(zip)
                entry = zip.nextEntry
            }
        }
        return ""
    }

    /**
     * Not namespace-aware (Android's default `Xml.newPullParser()`), so tag names may arrive
     * prefixed ("w:p") — normalized via `substringAfter(':')` so this works whether or not the
     * parser strips prefixes.
     */
    private fun parseParagraphs(input: InputStream): String {
        val parser = Xml.newPullParser()
        parser.setInput(input, "UTF-8")
        val paragraphs = mutableListOf<StringBuilder>()
        var current: StringBuilder? = null
        var inTextRun = false

        var eventType = parser.eventType
        while (eventType != XmlPullParser.END_DOCUMENT) {
            when (eventType) {
                XmlPullParser.START_TAG -> when (parser.name.substringAfter(':')) {
                    "p" -> current = StringBuilder().also { paragraphs.add(it) }
                    "t" -> inTextRun = true
                }
                XmlPullParser.TEXT -> if (inTextRun) current?.append(parser.text)
                XmlPullParser.END_TAG -> if (parser.name.substringAfter(':') == "t") inTextRun = false
            }
            eventType = parser.next()
        }
        return paragraphs.joinToString("\n") { it.toString() }
    }
}
