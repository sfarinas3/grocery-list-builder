package com.grocerylistbuilder.core.ingest.documents

/**
 * Extract text from an uploaded recipe document (PDF, Word, or plain text) — mirrors
 * grocery/ingest/documents.py `extract_text`.
 *
 * Interface lives in `core` so [com.grocerylistbuilder.core.pipeline.Pipeline] stays
 * Android-free; the implementation lives in `:app` because the only Android-capable PDF/DOCX
 * libraries (`pdfbox-android`, `android.util.Xml`) need an Android runtime, unlike Python's
 * `pypdf`/`python-docx`, which have no such constraint.
 */
fun interface DocumentTextExtractor {
    /** Return the text content of a document, dispatched by [filename]'s extension. */
    suspend fun extractText(data: ByteArray, filename: String): String
}
