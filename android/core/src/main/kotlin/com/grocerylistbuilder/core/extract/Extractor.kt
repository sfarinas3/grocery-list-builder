package com.grocerylistbuilder.core.extract

import com.grocerylistbuilder.core.models.Ingredient
import com.grocerylistbuilder.core.models.RecipeContent

/**
 * The swappable extraction-backend seam (mirrors grocery/extract/base.py `Extractor` Protocol).
 *
 * Keeping this behind an interface means [com.grocerylistbuilder.core.pipeline.Pipeline] never
 * knows *how* ingredients were obtained. The only current implementation, [CrfExtractor], also
 * structures raw text via a Kotlin port of the same CRF model the Python backend uses
 * (`ingredient_parser_nlp`, see [com.grocerylistbuilder.core.extract.crf.IngredientLineParser]) —
 * this replaced an earlier on-device-LLM backend that spiked memory too high to run reliably on
 * real devices.
 *
 * All methods are `suspend` for API stability even though every current implementation is
 * synchronous.
 */
interface Extractor {

    /**
     * Return the ingredient lines for [content]. Implementations should prefer
     * `content.ingredientLines` when present (the JSON-LD path — clean, no model needed) and
     * ignore `content.rawText` here; raw text goes through [structureFromText] instead.
     */
    suspend fun ingredientLines(content: RecipeContent): List<String>

    /**
     * Extract AND structure ingredients out of messy raw text (OCR output, document text, or a
     * non-JSON-LD web page) in one pass. Empty input returns an empty list; never throws.
     */
    suspend fun structureFromText(rawText: String): List<Ingredient>

    /**
     * Map each ingredient name to one of [categories]. Called only for names
     * [com.grocerylistbuilder.core.extract.Categorizer]'s keyword lookup couldn't place, in a
     * single batched request.
     */
    suspend fun categorize(names: List<String>, categories: List<String>): Map<String, String>
}
