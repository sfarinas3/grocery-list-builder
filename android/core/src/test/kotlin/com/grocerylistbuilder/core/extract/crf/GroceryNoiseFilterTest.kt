package com.grocerylistbuilder.core.extract.crf

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GroceryNoiseFilterTest {

    @Test
    fun `flags phone status-bar and app chrome captured alongside a recipe photo`() {
        assertTrue(GroceryNoiseFilter.isLikelyNoise("Partly Sunny"))
        assertTrue(GroceryNoiseFilter.isLikelyNoise("Q Search"))
        assertTrue(GroceryNoiseFilter.isLikelyNoise("Search"))
        assertTrue(GroceryNoiseFilter.isLikelyNoise("Settings"))
        assertTrue(GroceryNoiseFilter.isLikelyNoise("Wifi"))
    }

    @Test
    fun `flags cooking temperatures`() {
        assertTrue(GroceryNoiseFilter.isLikelyNoise("82 degrees celsius"))
        assertTrue(GroceryNoiseFilter.isLikelyNoise("82°C"))
        assertTrue(GroceryNoiseFilter.isLikelyNoise("350 degrees F"))
        assertTrue(GroceryNoiseFilter.isLikelyNoise("Internal temperature reaches 165°F"))
    }

    @Test
    fun `does not flag quantities that happen to use single-letter units`() {
        assertFalse(GroceryNoiseFilter.isLikelyNoise("2 c flour"))
        assertFalse(GroceryNoiseFilter.isLikelyNoise("6 oz cream cheese"))
    }

    @Test
    fun `flags recipe-page metadata`() {
        assertTrue(GroceryNoiseFilter.isLikelyNoise("Prep Time: 10 minutes"))
        assertTrue(GroceryNoiseFilter.isLikelyNoise("Servings: 4"))
        assertTrue(GroceryNoiseFilter.isLikelyNoise("Jump to Recipe"))
    }

    @Test
    fun `does not flag real ingredient lines`() {
        assertFalse(GroceryNoiseFilter.isLikelyNoise("2 cups flour"))
        assertFalse(GroceryNoiseFilter.isLikelyNoise("salt to taste"))
        assertFalse(GroceryNoiseFilter.isLikelyNoise("1 onion, chopped"))
        assertFalse(GroceryNoiseFilter.isLikelyNoise("fresh basil leaves"))
        assertFalse(GroceryNoiseFilter.isLikelyNoise("2 tbsp olive oil"))
    }
}
