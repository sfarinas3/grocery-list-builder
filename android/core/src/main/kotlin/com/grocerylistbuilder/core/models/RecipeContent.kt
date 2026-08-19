package com.grocerylistbuilder.core.models

/**
 * One recipe as it comes out of the ingest stage, before any parsing.
 *
 * Populated one of two ways (mirrors grocery/models.py RecipeContent):
 *   - JSON-LD path  -> [ingredientLines] filled with clean lines, [rawText] empty.
 *   - text fallback -> [rawText] filled with readable page/document text; the Kotlin CRF port
 *     (see [com.grocerylistbuilder.core.extract.CrfExtractor]) pulls the lines out of it.
 */
data class RecipeContent(
    val sourceUrl: String? = null,
    val title: String = "",
    val servings: Int? = null,
    val ingredientLines: List<String> = emptyList(),
    val rawText: String = "",
)
