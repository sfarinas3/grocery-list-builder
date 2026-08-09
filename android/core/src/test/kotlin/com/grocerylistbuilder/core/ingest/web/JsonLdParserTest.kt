package com.grocerylistbuilder.core.ingest.web

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class JsonLdParserTest {

    private fun fixture(name: String): String =
        checkNotNull(javaClass.classLoader.getResourceAsStream("recipes/$name")) { "missing fixture $name" }
            .bufferedReader()
            .readText()

    private fun findRecipe(fixtureName: String) =
        checkNotNull(JsonLdParser.findRecipeInHtml(fixture(fixtureName))) { "expected a Recipe node in $fixtureName" }

    @Test
    fun `finds a bare Recipe object`() {
        val recipe = findRecipe("bare_object.html")
        assertEquals("Garlic Noodles & Shrimp", RecipeFields.asText(recipe["name"]))
        assertEquals(
            listOf("2 cloves garlic, minced", "1/2 cup soy sauce", "8 oz noodles"),
            RecipeFields.cleanLines(recipe["recipeIngredient"]),
        )
        assertEquals(4, RecipeFields.parseServings(recipe["recipeYield"]))
    }

    @Test
    fun `finds a Recipe inside a top-level array, skipping non-Recipe nodes`() {
        val recipe = findRecipe("array.html")
        assertEquals("Spaghetti Aglio e Olio", RecipeFields.asText(recipe["name"]))
        assertEquals(2, RecipeFields.parseServings(recipe["recipeYield"]))
        assertEquals(
            listOf("1/2 cup olive oil", "6 cloves garlic, sliced", "½ teaspoon red pepper flakes"),
            RecipeFields.cleanLines(recipe["recipeIngredient"]),
        )
    }

    @Test
    fun `finds a Recipe wrapped in @graph, with @type as a list`() {
        val recipe = findRecipe("graph.html")
        assertEquals("Budget Bytes Garlic Noodles", RecipeFields.asText(recipe["name"]))
        assertEquals(6, RecipeFields.parseServings(recipe["recipeYield"]))
        assertEquals(listOf("3 tbsp butter", "1 tbsp soy sauce"), RecipeFields.cleanLines(recipe["recipeIngredient"]))
    }

    @Test
    fun `returns null when there is no JSON-LD at all`() {
        assertNull(JsonLdParser.findRecipeInHtml(fixture("no_jsonld.html")))
    }

    @Test
    fun `skips malformed JSON-LD rather than throwing`() {
        assertNull(JsonLdParser.findRecipeInHtml(fixture("malformed_jsonld.html")))
    }
}
