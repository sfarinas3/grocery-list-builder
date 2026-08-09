package com.grocerylistbuilder.android.ingest.documents

import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import java.io.ByteArrayInputStream

/**
 * PDF -> text via `pdfbox-android` (mirrors the `pypdf` branch of
 * grocery/ingest/documents.py `extract_text`). No Android port of `pypdf` exists; this is the
 * maintained ART-compatible PDFBox fork (needs `PDFBoxResourceLoader.init()` once at app
 * startup — see [com.grocerylistbuilder.android.GroceryApp]).
 */
object PdfTextExtractor {
    fun extractText(data: ByteArray): String =
        PDDocument.load(ByteArrayInputStream(data)).use { document -> PDFTextStripper().getText(document) }
}
