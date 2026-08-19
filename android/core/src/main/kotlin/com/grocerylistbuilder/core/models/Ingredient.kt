package com.grocerylistbuilder.core.models

/**
 * A single ingredient from one recipe (mirrors grocery/models.py Ingredient).
 *
 * [name]/[quantity]/[unit]/[notes] are populated by structuring raw text (see
 * `com.grocerylistbuilder.core.extract.crf.IngredientLineParser`, a Kotlin port of the Python
 * CRF parser `ingredient_parser`) whenever a line didn't already arrive as clean JSON-LD.
 */
data class Ingredient(
    val name: String,
    val quantity: Double? = null,
    val unit: String? = null,
    val category: String = DEFAULT_CATEGORY,
    val notes: String = "",
    val sourceUrl: String? = null,
    val confidence: Double = 1.0,
    val flags: List<String> = emptyList(),
)
