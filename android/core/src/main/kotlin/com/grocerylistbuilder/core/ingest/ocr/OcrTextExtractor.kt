package com.grocerylistbuilder.core.ingest.ocr

/**
 * Read the text out of a recipe photo — mirrors grocery/extract/vision.py's job, but split into
 * its own OCR step (ML Kit) feeding the same [com.grocerylistbuilder.core.extract.Extractor]
 * text pipeline everything else uses, rather than a single vision-LLM call reading the image
 * directly (see note-to-self.md for why: OCR is free/fast/broadly-supported on Android, unlike
 * bundling a second multimodal model).
 *
 * Interface lives in `core` so [com.grocerylistbuilder.core.pipeline.Pipeline] stays
 * Android-free; the implementation (ML Kit Text Recognition) lives in `:app`, same split as
 * [com.grocerylistbuilder.core.ingest.documents.DocumentTextExtractor].
 */
fun interface OcrTextExtractor {
    /** Return the recognized text in [imageBytes] (raw photo bytes), or "" if none is found. */
    suspend fun recognizeText(imageBytes: ByteArray): String
}
