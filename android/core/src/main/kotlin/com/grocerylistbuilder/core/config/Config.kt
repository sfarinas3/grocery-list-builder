package com.grocerylistbuilder.core.config

/**
 * Central settings — mirrors grocery/config.py. Put the knobs someone will actually want to
 * tweak (categories, sanity thresholds, excluded ingredients) in one obvious place.
 */
object Config {

    /**
     * The canonical set of aisles the list is grouped by. Reorder to match a store's layout, or
     * add/remove aisles. [com.grocerylistbuilder.core.models.DEFAULT_CATEGORY]
     * ("uncategorized") is the sentinel for "not yet assigned" and is intentionally NOT in this
     * list, same as Python's CATEGORIES.
     */
    val CATEGORIES: List<String> = listOf(
        "produce",
        "meat & seafood",
        "dairy & eggs",
        "bakery",
        "pantry", // flour, rice, pasta, grains, dried beans
        "canned & jarred",
        "condiments & sauces", // oils, vinegars, soy/fish sauce, honey, syrup
        "spices & seasonings",
        "baking", // sugar, leaveners, vanilla, cocoa
        "frozen",
        "beverages",
        "other",
    )

    /**
     * A merged quantity above this is almost certainly a parse error (e.g. a year or a stray
     * page number read as an amount) — flag it for review.
     */
    const val SANITY_MAX_QUANTITY: Double = 1000.0

    /**
     * Ingredients recipes list but you don't actually buy — dropped from the combined grocery
     * list (still shown in the per-recipe breakdown). Matched on the *exact* normalized
     * (lowercased) ingredient name, so real products like "coconut water" or "rose water" are
     * NOT affected — [com.grocerylistbuilder.core.aggregate.Combiner] deliberately keeps this
     * exact match rather than a looser substring one, even though Phase 1's un-structured names
     * (raw lines like "1 cup water") mean it rarely fires yet; see Combiner's doc. Add entries
     * as you notice them.
     */
    val EXCLUDE_FROM_LIST: Set<String> = setOf(
        "water",
        "pasta water",
        "cold water",
        "warm water",
        "hot water",
        "ice water",
        "boiling water",
        "cooking water",
        "reserved pasta water",
        "lukewarm water",
        "room temperature water",
        "tap water",
        "filtered water",
    )
}
