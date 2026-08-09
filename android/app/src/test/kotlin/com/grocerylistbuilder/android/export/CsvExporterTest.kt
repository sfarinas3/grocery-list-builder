package com.grocerylistbuilder.android.export

import com.grocerylistbuilder.core.models.GroceryItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CsvExporterTest {

    @Test
    fun `quotes a field containing a comma`() {
        val items = listOf(GroceryItem(name = "garlic, minced", sources = listOf("Recipe A")))
        val csv = CsvExporter.export(items, recipeLinks = emptyList())
        assertTrue(csv.contains("\"garlic, minced\""))
    }

    @Test
    fun `appends a recipe-links table when links are present`() {
        val items = listOf(GroceryItem(name = "garlic"))
        val csv = CsvExporter.export(items, recipeLinks = listOf("Recipe A" to "https://example.com/a"))
        assertTrue(csv.contains("Recipe,Link"))
        assertTrue(csv.contains("Recipe A,https://example.com/a"))
    }

    @Test
    fun `header omits the internal Review column`() {
        val csv = CsvExporter.export(emptyList(), recipeLinks = emptyList())
        assertEquals("Done,Ingredient,Quantity,Unit,Category,Recipe(s),Notes", csv.lineSequence().first())
    }
}
