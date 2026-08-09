package com.grocerylistbuilder.android.ingest.documents

import com.grocerylistbuilder.core.ingest.documents.DocumentTextExtractor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Dispatches by file extension (mirrors grocery/ingest/documents.py `extract_text`). */
class AndroidDocumentTextExtractor : DocumentTextExtractor {

    override suspend fun extractText(data: ByteArray, filename: String): String = withContext(Dispatchers.IO) {
        when (filename.substringAfterLast('.', "").lowercase()) {
            "pdf" -> PdfTextExtractor.extractText(data)
            "docx" -> DocxTextExtractor.extractText(data)
            // .txt, .md, or anything else: best-effort decode as UTF-8 (String(bytes, Charsets.UTF_8)
            // already substitutes malformed sequences with U+FFFD, matching Python's errors="replace").
            else -> String(data, Charsets.UTF_8)
        }
    }
}
