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
