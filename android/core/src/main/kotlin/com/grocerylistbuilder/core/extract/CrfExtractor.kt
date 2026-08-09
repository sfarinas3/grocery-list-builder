package com.grocerylistbuilder.core.extract

import com.grocerylistbuilder.core.extract.crf.IngredientLineFinder
import com.grocerylistbuilder.core.models.Ingredient
import com.grocerylistbuilder.core.models.RecipeContent

/**
 * The Kotlin CRF port's [Extractor] — replaces the on-device LLM (Phase 2), which spiked RSS to
 * ~3GB during inference and got the app OOM-killed on 2-4GB-RAM devices. Structuring is now a
 * ported version of the same CRF model the Python backend uses (`ingredient_parser_nlp`), and
 * line-finding (deciding *which* lines in messy text are ingredients — a job the LLM used to do
 * too) is a plain heuristic (see [IngredientLineFinder]) since no Python equivalent exists to
 * port. No download, no background model load — this is unconditionally available.
 */
class CrfExtractor : Extractor {

    override suspend fun ingredientLines(content: RecipeContent): List<String> = content.ingredientLines

    override suspend fun structureFromText(rawText: String): List<Ingredient> =
        IngredientLineFinder.structureFromText(rawText)

    /** No LLM fallback anymore — the keyword lookup in [Categorizer] is the whole story now;
     * anything it misses gets flagged "needs_category" for human review instead of a model guess. */
    override suspend fun categorize(names: List<String>, categories: List<String>): Map<String, String> = emptyMap()
}
