package com.grocerylistbuilder.android.export

import com.grocerylistbuilder.core.config.Config
import com.grocerylistbuilder.core.models.DEFAULT_CATEGORY
import com.grocerylistbuilder.core.models.GroceryItem
import com.grocerylistbuilder.core.util.formatQuantity

/** The current (edited) list as a readable, aisle-grouped shopping list (mirrors app.py `grocery_text`). */
object TextExporter {

    fun export(items: List<GroceryItem>, recipeLinks: List<Pair<String, String>>): String {
        val order = Config.CATEGORIES.withIndex().associate { (i, c) -> c to i }
        val rows = items.filter { it.name.isNotBlank() }
            .sortedWith(compareBy({ order[it.category] ?: order.size }, { it.name.lowercase() }))

        val lines = mutableListOf("Grocery List")
        var currentCategory: String? = null
        for (item in rows) {
            val category = item.category.ifEmpty { DEFAULT_CATEGORY }
            if (category != currentCategory) {
                lines += ""
                lines += category.uppercase()
                currentCategory = category
            }
            val amount = "${formatQuantity(item.quantity)} ${item.unit.orEmpty()}".trim()
            var line = if (amount.isNotEmpty()) "$amount ${item.name}".trim() else item.name
            if (item.notes.isNotBlank()) line += " (${item.notes})"
            lines += "  ${if (item.checked) "[x]" else "[ ]"} $line"
        }

        if (recipeLinks.isNotEmpty()) {
            lines += ""
            lines += "Recipes:"
            lines += recipeLinks.map { (name, url) -> "  - $name: $url" }
        }
        return lines.joinToString("\n")
    }
}
