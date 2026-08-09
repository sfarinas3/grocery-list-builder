package com.grocerylistbuilder.core.extract.crf

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class IngredientLineFinderTest {

    @Test
    fun `finds lines under an Ingredients heading, stopping at Instructions`() {
        val text = """
            Grandma's Chocolate Chip Cookies

            Ingredients:
            2 cups all-purpose flour
            1 cup butter, softened
            2 large eggs
            1 tsp vanilla extract

            Instructions:
            1. Preheat oven to 350 degrees.
            2. Mix dry ingredients together.
        """.trimIndent()

        val lines = IngredientLineFinder.findCandidateLines(text)

        assertEquals(
            listOf("2 cups all-purpose flour", "1 cup butter, softened", "2 large eggs", "1 tsp vanilla extract"),
            lines,
        )
    }

    @Test
    fun `strips bullet characters from heading-scoped lines`() {
        val text = """
            What you'll need
            - 2 cloves garlic, minced
            * 1 onion, diced

            Directions
            Cook until golden.
        """.trimIndent()

        val lines = IngredientLineFinder.findCandidateLines(text)

        assertEquals(listOf("2 cloves garlic, minced", "1 onion, diced"), lines)
    }

    @Test
    fun `falls back to a quantity-unit heuristic when there is no heading`() {
        val text = """
            Best pancakes ever
            2 cups flour
            1 tablespoon sugar
            Preheat the griddle over medium heat.
            Whisk everything together until smooth.
            3 eggs
        """.trimIndent()

        val lines = IngredientLineFinder.findCandidateLines(text)

        assertEquals(listOf("2 cups flour", "1 tablespoon sugar", "3 eggs"), lines)
    }

    @Test
    fun `fallback filter rejects instruction-verb lines even with a leading number`() {
        val text = """
            1 cup rice
            1. Preheat oven to 400 degrees
            2 tbsp olive oil
        """.trimIndent()

        val lines = IngredientLineFinder.findCandidateLines(text)

        assertEquals(listOf("1 cup rice", "2 tbsp olive oil"), lines)
    }

    @Test
    fun `structureFromText structures real ingredient lines and never surfaces an unparsed-name result`() {
        val text = """
            Ingredients:
            2 cups flour
            1 tsp salt
        """.trimIndent()

        val ingredients = IngredientLineFinder.structureFromText(text)

        assertEquals(listOf("flour", "salt"), ingredients.map { it.name })
        assertTrue(ingredients.none { "unparsed_name" in it.flags })
    }

    @Test
    fun `handles messy OCR text with no clear heading or formatting`() {
        val text = """
            Sarah's Famous Chili
            serves 6

            2 lbs ground beef
            1 can black beans drained
            1 onion chopped
            2 tsp chili powder
            Brown the beef in a large pot over medium heat then add the onion
            Stir in beans and chili powder and simmer for 20 minutes
        """.trimIndent()

        val lines = IngredientLineFinder.findCandidateLines(text)

        assertEquals(
            listOf("2 lbs ground beef", "1 can black beans drained", "1 onion chopped", "2 tsp chili powder"),
            lines,
        )
    }
}
