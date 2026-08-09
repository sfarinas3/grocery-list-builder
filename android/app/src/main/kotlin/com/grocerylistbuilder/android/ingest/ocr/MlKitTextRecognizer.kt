package com.grocerylistbuilder.android.ingest.ocr

import android.graphics.BitmapFactory
import android.util.Log
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.grocerylistbuilder.core.ingest.ocr.OcrTextExtractor
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

private const val TAG = "MlKitTextRecognizer"

/**
 * Reads text out of a recipe photo with ML Kit Text Recognition v2 — bundled/on-device (no
 * network, no Play Services model download at runtime), consistent with this app's "runs
 * entirely on your device" claim. A small [suspendCancellableCoroutine] wrapper adapts ML Kit's
 * Task-based API to a suspend function rather than pulling in `kotlinx-coroutines-play-services`
 * for this one call site (per this project's "avoid unnecessary dependencies" habit — see the
 * DOCX extractor for the same reasoning).
 */
class MlKitTextRecognizer : OcrTextExtractor {

    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    override suspend fun recognizeText(imageBytes: ByteArray): String = withContext(Dispatchers.Default) {
        val bitmap = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
        if (bitmap == null) {
            Log.w(TAG, "recognizeText: could not decode image bytes as a bitmap")
            return@withContext ""
        }
        val image = InputImage.fromBitmap(bitmap, 0)
        suspendCancellableCoroutine { continuation ->
            recognizer.process(image)
                .addOnSuccessListener { visionText -> continuation.resume(visionText.text) }
                .addOnFailureListener { e ->
                    Log.w(TAG, "recognizeText failed", e)
                    continuation.resume("") // degrade to "no text found", never throw
                }
        }
    }
}
