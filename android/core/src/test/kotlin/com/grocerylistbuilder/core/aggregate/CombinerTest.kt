package com.grocerylistbuilder.core.aggregate

import com.grocerylistbuilder.core.models.Ingredient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CombinerTest {

    @Test
    fun `two recipes with byte-identical lines merge into one row with both sources`() {
        val recipes = listOf(
            "Recipe A" to listOf(Ingredient(name = "2 cloves garlic, minced")),
            "Recipe B" to listOf(Ingredient(name = "2 cloves garlic, minced")),
        )
        val list = Combiner.combine(recipes)
        assertEquals(1, list.items.size)
        assertEquals(listOf("Recipe A", "Recipe B"), list.items.single().sources)
    }

    @Test
    fun `differently worded lines for the same ingredient do not merge in Phase 1`() {
        val recipes = listOf(
            "Recipe A" to listOf(Ingredient(name = "2 cloves garlic, minced")),
            "Recipe B" to listOf(Ingredient(name = "1 clove garlic")),
        )
        val list = Combiner.combine(recipes)
        assertEquals(2, list.items.size) // known Phase 1 limitation, not a bug — see Combiner doc
    }

    @Test
    fun `a line that is exactly an excluded name is dropped`() {
        val recipes = listOf("Recipe A" to listOf(Ingredient(name = "water"), Ingredient(name = "flour")))
        val list = Combiner.combine(recipes)
        assertEquals(listOf("flour"), list.items.map { it.name })
    }

    @Test
    fun `a raw line like '1 cup water' is not excluded in Phase 1 (no canonical name yet)`() {
        val recipes = listOf("Recipe A" to listOf(Ingredient(name = "1 cup water")))
        val list = Combiner.combine(recipes)
        assertEquals(1, list.items.size) // known Phase 1 limitation — see Combiner.isExcluded doc
    }

    @Test
    fun `a real product containing 'water' as a substring is never excluded`() {
        val recipes = listOf("Recipe A" to listOf(Ingredient(name = "1 cup coconut water")))
        val list = Combiner.combine(recipes)
        assertEquals(1, list.items.size)
    }

    @Test
    fun `an ingredient still uncategorized after aggregation is flagged`() {
        val recipes = listOf("Recipe A" to listOf(Ingredient(name = "2 cups unobtainium")))
        val list = Combiner.combine(recipes)
        assertTrue("needs_category" in list.items.single().flags)
    }
}
