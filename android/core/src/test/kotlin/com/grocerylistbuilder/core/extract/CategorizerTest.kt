package com.grocerylistbuilder.core.extract

import com.grocerylistbuilder.core.config.Config
import com.grocerylistbuilder.core.models.DEFAULT_CATEGORY
import com.grocerylistbuilder.core.models.Ingredient
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CategorizerTest {

    @Test
    fun `longer keyword wins over its substring`() {
        assertEquals("spices & seasonings", Categorizer.lookupCategory("2 tsp black pepper"))
        assertEquals("condiments & sauces", Categorizer.lookupCategory("1/4 cup olive oil"))
    }

    @Test
    fun `unknown ingredient has no match`() {
        assertNull(Categorizer.lookupCategory("2 cups unobtainium"))
    }

    @Test
    fun `plural ingredient names match their singular keyword`() {
        // A real false-"uncategorized" report: "green onion" (the keyword) didn't match "green
        // onions" (how the ingredient is actually written) because a bare \bKEYWORD\b has no
        // word-break between "onion" and the plural "s".
        assertEquals("produce", Categorizer.lookupCategory("2 green onions, chopped"))
        assertEquals("produce", Categorizer.lookupCategory("3 tomatoes, diced"))
        assertEquals("produce", Categorizer.lookupCategory("2 carrots"))
    }

    @Test
    fun `sake and miso are categorized`() {
        assertEquals("beverages", Categorizer.lookupCategory("1 cup sake"))
        assertEquals("condiments & sauces", Categorizer.lookupCategory("2 tbsp red awase miso paste"))
    }

    @Test
    fun `categorize resolves via keyword lookup with no extractor`() = runTest {
        val ingredients = listOf(Ingredient(name = "2 cloves garlic, minced"))
        val result = Categorizer.categorize(ingredients, Config.CATEGORIES, extractor = null)
        assertEquals("produce", result.single().category)
    }

    @Test
    fun `categorize flags unresolved ingredients when there is no extractor`() = runTest {
        val ingredients = listOf(Ingredient(name = "2 cups unobtainium"))
        val result = Categorizer.categorize(ingredients, Config.CATEGORIES, extractor = null)
        assertEquals(DEFAULT_CATEGORY, result.single().category)
        assertTrue("needs_category" in result.single().flags)
    }
}
