package com.grocerylistbuilder.core.extract

import com.grocerylistbuilder.core.models.Ingredient

/**
 * Assign each ingredient a shopping aisle (mirrors grocery/extract/categorize.py).
 *
 * The keyword lookup (below) is free, instant, and covers the common case. An [Extractor] can
 * optionally be passed for whatever it misses, in one batched call constrained to the valid
 * categories — [CrfExtractor] doesn't implement this (no model to ask), so in practice the
 * keyword lookup is the whole story today; the seam stays in case a future backend wants it.
 *
 * Anything still unassigned keeps its existing category (the [com.grocerylistbuilder.core.models.DEFAULT_CATEGORY]
 * sentinel, for a freshly parsed ingredient) and gets a "needs_category" flag so the UI surfaces
 * it for review.
 */
object Categorizer {

    // The tweakable knob: which words map an ingredient to which aisle. Keywords are matched
    // whole-word and longest-first, so "black pepper" (spices) wins over "pepper", and "olive
    // oil" (condiments) wins over "oil". Add staples freely — this table only needs to cover the
    // common case; the LLM fallback and human review handle the rest.
    private val CATEGORY_KEYWORDS: Map<String, List<String>> = mapOf(
        "produce" to listOf(
            "garlic", "onion", "scallion", "green onion", "shallot", "tomato", "lettuce",
            "carrot", "potato", "bell pepper", "jalapeno", "spinach", "kale", "lemon",
            "lime", "apple", "banana", "avocado", "celery", "cucumber", "mushroom",
            "broccoli", "cauliflower", "ginger", "parsley", "cilantro", "basil",
            "zucchini", "corn", "cabbage", "eggplant", "sweet potato", "lime juice",
            "lemon juice",
        ),
        "meat & seafood" to listOf(
            "chicken", "beef", "ground beef", "pork", "bacon", "sausage", "turkey",
            "lamb", "shrimp", "salmon", "fish", "tuna", "steak", "ham", "prosciutto",
        ),
        "dairy & eggs" to listOf(
            "milk", "butter", "cheese", "egg", "eggs", "cream", "heavy cream", "yogurt",
            "parmesan", "mozzarella", "cheddar", "feta", "sour cream", "cream cheese",
        ),
        "bakery" to listOf("bread", "tortilla", "bun", "baguette", "roll", "pita", "naan"),
        "pantry" to listOf(
            "flour", "all-purpose flour", "rice", "pasta", "spaghetti", "noodle",
            "oats", "quinoa", "lentil", "bean", "chickpea", "breadcrumb", "cornmeal",
        ),
        "canned & jarred" to listOf(
            "canned", "crushed tomatoes", "diced tomatoes", "tomato paste",
            "tomato sauce", "broth", "stock", "coconut milk",
        ),
        "condiments & sauces" to listOf(
            "oil", "olive oil", "vegetable oil", "sesame oil", "soy sauce", "vinegar",
            "ketchup", "mustard", "mayonnaise", "mayo", "honey", "oyster sauce",
            "hot sauce", "fish sauce", "worcestershire", "salsa", "syrup", "maple syrup",
        ),
        "spices & seasonings" to listOf(
            "salt", "pepper", "black pepper", "red pepper", "cumin", "paprika",
            "cinnamon", "oregano", "thyme", "rosemary", "chili", "chili powder",
            "garlic powder", "onion powder", "bay leaf", "nutmeg", "turmeric", "curry",
            "cayenne", "seasoning", "red pepper flakes",
        ),
        "baking" to listOf(
            "sugar", "brown sugar", "powdered sugar", "baking powder", "baking soda",
            "yeast", "vanilla", "cocoa", "chocolate", "chocolate chip", "cornstarch",
        ),
        "beverages" to listOf("wine", "juice", "coffee", "tea", "beer"),
    )

    // Flattened to (compiled whole-word regex, category), longest keyword first so a more
    // specific match wins.
    private val LOOKUP: List<Pair<Regex, String>> = CATEGORY_KEYWORDS
        .flatMap { (category, keywords) -> keywords.map { it to category } }
        .sortedByDescending { it.first.length }
        .map { (keyword, category) -> Regex("\\b${Regex.escape(keyword)}\\b", RegexOption.IGNORE_CASE) to category }

    /** Return the aisle for an ingredient name via the keyword table, or null. */
    fun lookupCategory(name: String): String? = LOOKUP.firstOrNull { (pattern, _) -> pattern.containsMatchIn(name) }?.second

    /** Set `category` on each ingredient, returning a new list (Ingredient is immutable here). */
    suspend fun categorize(
        ingredients: List<Ingredient>,
        categories: List<String>,
        extractor: Extractor? = null,
    ): List<Ingredient> {
        val result = ingredients.toMutableList()
        val unresolved = mutableListOf<Int>()
        for (i in ingredients.indices) {
            val category = lookupCategory(ingredients[i].name)
            if (category != null) {
                result[i] = result[i].copy(category = category)
            } else {
                unresolved += i
            }
        }

        if (unresolved.isNotEmpty() && extractor != null) {
            // One call for all unique unknown names, constrained to valid categories.
            val names = unresolved.map { result[it].name }.distinct()
            val mapping = extractor.categorize(names, categories)
            for (i in unresolved) {
                val category = mapping[result[i].name]
                if (category != null && category in categories) {
                    result[i] = result[i].copy(category = category)
                }
            }
        }

        // Anything still unassigned (lookup missed it and the LLM didn't place it) keeps the
        // default sentinel — flag it for human review.
        for (i in unresolved) {
            if (result[i].category !in categories) {
                result[i] = result[i].copy(flags = result[i].flags + "needs_category")
            }
        }

        return result
    }
}
