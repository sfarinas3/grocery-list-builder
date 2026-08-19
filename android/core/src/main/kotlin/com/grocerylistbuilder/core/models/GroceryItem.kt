package com.grocerylistbuilder.core.models

/**
 * One line on the final grocery list — an [Ingredient] merged across recipes (mirrors
 * grocery/models.py GroceryItem). Differs from Ingredient: can come from several recipes
 * ([sources]), and carries UI state ([checked]).
 */
data class GroceryItem(
    val name: String,
    val quantity: Double? = null,
    val unit: String? = null,
    val category: String = DEFAULT_CATEGORY,
    val notes: String = "",
    val checked: Boolean = false,
    val sources: List<String> = emptyList(),
    val flags: List<String> = emptyList(),
)
