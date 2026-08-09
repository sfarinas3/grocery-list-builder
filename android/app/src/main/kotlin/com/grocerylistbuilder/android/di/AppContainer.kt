package com.grocerylistbuilder.android.di

import android.content.Context
import com.grocerylistbuilder.android.ingest.documents.AndroidDocumentTextExtractor
import com.grocerylistbuilder.android.ingest.ocr.MlKitTextRecognizer
import com.grocerylistbuilder.core.extract.CrfExtractor
import com.grocerylistbuilder.core.extract.Extractor
import com.grocerylistbuilder.core.ingest.documents.DocumentTextExtractor
import com.grocerylistbuilder.core.ingest.ocr.OcrTextExtractor
import com.grocerylistbuilder.core.ingest.web.WebFetcher

/**
 * Manual dependency container — no Hilt/Koin. The dependency graph here is small and static
 * (mirrors `app.py`'s module-level `get_extractor()`/`get_vision_extractor()` cached resources),
 * so a DI framework would be pure ceremony (YAGNI, per docs/design-principles.md).
 *
 * [extractor] is unconditionally a [CrfExtractor] — no download, no model-readiness branching.
 * (An earlier on-device-LLM backend needed exactly that kind of branching; it was dropped after
 * on-device testing found it spiked RSS to ~3GB and got the app OOM-killed on 2-4GB-RAM devices.)
 */
class AppContainer(context: Context) {
    val webFetcher: WebFetcher = WebFetcher()
    val documentTextExtractor: DocumentTextExtractor = AndroidDocumentTextExtractor()
    val ocrTextExtractor: OcrTextExtractor = MlKitTextRecognizer()

    val extractor: Extractor = CrfExtractor()
}
