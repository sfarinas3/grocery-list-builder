package com.grocerylistbuilder.android.export

import com.grocerylistbuilder.core.models.GroceryItem
import com.grocerylistbuilder.core.util.formatQuantity

/** The current (edited) list as CSV (mirrors app.py `grocery_csv`). */
object CsvExporter {

    private val HEADER = listOf("Done", "Ingredient", "Quantity", "Unit", "Category", "Recipe(s)", "Notes")

    fun export(items: List<GroceryItem>, recipeLinks: List<Pair<String, String>>): String {
        val rows = mutableListOf(HEADER)
        rows += items.map { item ->
            listOf(
                item.checked.toString(),
                item.name,
                formatQuantity(item.quantity),
                item.unit.orEmpty(),
                item.category,
                item.sources.joinToString(" / "),
                item.notes,
            )
        }
        val body = rows.joinToString("\n") { row -> row.joinToString(",") { quoteCsvField(it) } }

        if (recipeLinks.isEmpty()) return body
        val linkRows = listOf(listOf("Recipe", "Link")) + recipeLinks.map { (name, url) -> listOf(name, url) }
        val linkBody = linkRows.joinToString("\n") { row -> row.joinToString(",") { quoteCsvField(it) } }
        return "$body\n\n$linkBody"
    }

    /** RFC4180-ish: quote a field containing a comma, quote, or newline; double embedded quotes. */
    private fun quoteCsvField(field: String): String =
        if (field.any { it == ',' || it == '"' || it == '\n' }) "\"${field.replace("\"", "\"\"")}\"" else field
}
