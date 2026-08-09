package com.grocerylistbuilder.core.pipeline

import com.grocerylistbuilder.core.config.Config
import com.grocerylistbuilder.core.extract.Categorizer
import com.grocerylistbuilder.core.extract.Extractor
import com.grocerylistbuilder.core.ingest.documents.DocumentTextExtractor
import com.grocerylistbuilder.core.ingest.ocr.OcrTextExtractor
import com.grocerylistbuilder.core.ingest.web.WebFetcher
import com.grocerylistbuilder.core.models.Ingredient
import com.grocerylistbuilder.core.models.RecipeContent

/**
 * The end-to-end extraction pipeline (mirrors grocery/pipeline.py). Free of any UI so it stays
 * testable and reusable, chaining the stages:
 *
 *     fetch/read (ingest) -> ingredients (extract) -> categorize
 */
object Pipeline {

    /** Fetch and process one recipe URL into categorized ingredients. May throw
     * [com.grocerylistbuilder.core.ingest.web.IngestError]. */
    suspend fun processUrl(url: String, webFetcher: WebFetcher, extractor: Extractor): Pair<RecipeContent, List<Ingredient>> {
        val content = webFetcher.fetch(url)
        val categorized = Categorizer.categorize(extractIngredients(content, extractor), Config.CATEGORIES, extractor)
        return content to categorized
    }

    /** Process an uploaded recipe document (PDF/Word/text) the same way as a non-JSON-LD URL. */
    suspend fun processDocument(
        data: ByteArray,
        name: String,
        documentTextExtractor: DocumentTextExtractor,
        extractor: Extractor,
    ): Pair<RecipeContent, List<Ingredient>> {
        val content = RecipeContent(title = name, rawText = documentTextExtractor.extractText(data, name))
        val categorized = Categorizer.categorize(extractIngredients(content, extractor), Config.CATEGORIES, extractor)
        return content to categorized
    }

    /** Process a recipe photo. A photo never has JSON-LD, so it always goes straight through
     * the CRF's combined extract+structure pass — there's no fast path to skip here. */
    suspend fun processImage(
        imageBytes: ByteArray,
        name: String,
        ocrTextExtractor: OcrTextExtractor,
        extractor: Extractor,
    ): Pair<RecipeContent, List<Ingredient>> {
        val text = ocrTextExtractor.recognizeText(imageBytes)
        val content = RecipeContent(title = name, rawText = text)
        val categorized = Categorizer.categorize(extractor.structureFromText(text), Config.CATEGORIES, extractor)
        return content to categorized
    }

    /**
     * JSON-LD lines: wrapped verbatim, zero added latency (unchanged since Phase 1). Raw text
     * (OCR/document/non-JSON-LD page): the CRF's combined extract+structure pass. Deliberate
     * default — JSON-LD is the common case (most recipe sites have it) and is already clean and
     * free; running it through the line-finder too would risk mangling already-clean names for no
     * benefit. [Combiner]'s exact-name matching still merges JSON-LD ingredients fine without
     * splitting them.
     */
    private suspend fun extractIngredients(content: RecipeContent, extractor: Extractor): List<Ingredient> {
        val lines = extractor.ingredientLines(content)
        if (lines.isNotEmpty()) return lines.map { line -> Ingredient(name = line, sourceUrl = content.sourceUrl) }
        val text = content.rawText.trim()
        return if (text.isEmpty()) emptyList() else extractor.structureFromText(text)
    }
}
