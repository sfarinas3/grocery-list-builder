package com.grocerylistbuilder.core.aggregate

import com.grocerylistbuilder.core.config.Config
import com.grocerylistbuilder.core.models.DEFAULT_CATEGORY
import com.grocerylistbuilder.core.models.GroceryItem
import com.grocerylistbuilder.core.models.GroceryList
import com.grocerylistbuilder.core.models.Ingredient
import kotlin.math.roundToLong

/**
 * Merge ingredients from several recipes into one grocery list (mirrors
 * grocery/aggregate/combine.py).
 *
 * The rule, kept deliberately simple (correctness over cleverness):
 *   - One line per ingredient name (case-insensitive).
 *   - Amounts are summed when the contributions share a unit (folding trivial plurals, so
 *     "clove" + "cloves" combine). Units are NOT converted (cups<->grams etc.); a genuinely
 *     mixed set of units is shown as a breakdown in the notes and flagged for review rather than
 *     guessed.
 *   - Each line records the recipe(s) it came from.
 *
 * Also applies the deterministic sanity flags on the assembled list.
 *
 * Phase 1 has no CRF port (see [Ingredient]'s doc), so every real [Ingredient.quantity]/
 * [Ingredient.unit] is null and [Ingredient.name] is a full raw line rather than a canonical
 * name — meaning the amount-summing logic below is exercised in tests but rarely fires on real
 * data yet, and cross-recipe merging only happens when two lines are byte-identical. It's kept
 * as a faithful port so Phase 2's LLM-structured ingredients slot in with zero changes here.
 */
object Combiner {

    fun combine(recipes: List<Pair<String, List<Ingredient>>>): GroceryList {
        val groups = LinkedHashMap<String, MutableList<Pair<String, Ingredient>>>()
        for ((recipeName, ingredients) in recipes) {
            for (ingredient in ingredients) {
                val key = ingredient.name.trim().lowercase().ifEmpty { "(unnamed)" }
                if (isExcluded(key)) continue // e.g. plain/pasta water — not a thing you buy
                groups.getOrPut(key) { mutableListOf() }.add(recipeName to ingredient)
            }
        }

        val items = groups.values.map { merge(it) }
        return GroceryList(items = applySanityChecks(items))
    }

    /**
     * Exact match against [Config.EXCLUDE_FROM_LIST], same as Python. `key` is a whole raw line
     * in Phase 1 ("1 cup water"), not a canonical name, so this rarely fires until Phase 2's
     * structuring gives us clean names — but a looser whole-word/substring match was tried and
     * rejected: it also matches "coconut water" and "rose water" against "water", silently
     * dropping real ingredients the original Python design explicitly protects (see
     * [Config.EXCLUDE_FROM_LIST]'s doc). A known Phase 1 miss beats that false positive.
     */
    private fun isExcluded(key: String): Boolean = key in Config.EXCLUDE_FROM_LIST

    /** Collapse all contributions of one ingredient into a single line. */
    private fun merge(entries: List<Pair<String, Ingredient>>): GroceryItem {
        val ingredients = entries.map { it.second }
        val (quantity, unit, amountNote, mixedUnits) = combineAmounts(ingredients)

        // Keep the first recipe's spelling of the name; category from the first contribution
        // that actually got categorized.
        val name = ingredients.first().name
        val category = ingredients.firstOrNull { it.category != DEFAULT_CATEGORY }?.category ?: DEFAULT_CATEGORY

        val prepNotes = unique(ingredients.map { it.notes }.filter { it.isNotEmpty() })
        val notes = (listOfNotNull(amountNote.ifEmpty { null }) + prepNotes).joinToString("; ")

        val flags = unique(ingredients.flatMap { it.flags }).toMutableList()
        if (mixedUnits) flags += "mixed_units"

        return GroceryItem(
            name = name,
            quantity = quantity,
            unit = unit,
            category = category,
            notes = notes,
            sources = unique(entries.map { it.first }),
            flags = flags,
        )
    }

    private data class CombinedAmount(val quantity: Double?, val unit: String?, val amountNote: String, val mixedUnits: Boolean)

    /**
     * Contributions with no amount at all (e.g. "to taste") don't affect the unit decision —
     * their wording is preserved in the prep notes instead.
     */
    private fun combineAmounts(ingredients: List<Ingredient>): CombinedAmount {
        val amountful = ingredients.filter { it.quantity != null || !it.unit.isNullOrBlank() }
        if (amountful.isEmpty()) return CombinedAmount(null, null, "", false)

        val byUnit = LinkedHashMap<String, MutableList<Ingredient>>()
        for (ing in amountful) {
            byUnit.getOrPut(normalizeUnit(ing.unit)) { mutableListOf() }.add(ing)
        }

        if (byUnit.size == 1) { // all one unit -> sum
            val members = byUnit.values.first()
            val quantities = members.mapNotNull { it.quantity }
            val total = if (quantities.isNotEmpty()) quantities.sum() else null
            return CombinedAmount(total, displayUnit(members), "", false)
        }

        // Mixed units: don't fabricate a conversion — show the breakdown, flag it.
        val parts = byUnit.values.flatten().mapNotNull { formatAmount(it.quantity, it.unit).ifEmpty { null } }
        val note = if (parts.isNotEmpty()) "amounts: " + parts.joinToString(" + ") else ""
        return CombinedAmount(null, null, note, true)
    }

    /** Lowercase and fold a single trailing 's' so "cloves" and "clove" match. */
    private fun normalizeUnit(unit: String?): String = (unit ?: "").trim().lowercase().removeSuffix("s")

    /** Use the first non-empty original unit spelling for display. */
    private fun displayUnit(members: List<Ingredient>): String? = members.firstOrNull { !it.unit.isNullOrBlank() }?.unit

    private fun formatAmount(quantity: Double?, unit: String?): String {
        val qty = formatQuantity(quantity)
        return if (!unit.isNullOrEmpty()) "$qty $unit".trim() else qty
    }

    private fun formatQuantity(quantity: Double?): String {
        if (quantity == null) return ""
        return if (quantity == quantity.roundToLong().toDouble()) quantity.roundToLong().toString() else quantity.toString()
    }

    /** De-duplicate while preserving first-seen order. */
    private fun unique(values: List<String>): List<String> {
        val seen = LinkedHashSet<String>()
        seen.addAll(values)
        return seen.toList()
    }

    // --- Sanity checks: flag obviously-wrong lines so the UI surfaces them for review. -------

    /** GroceryItem is immutable, so (unlike Python's in-place mutation) this returns a new list. */
    private fun applySanityChecks(items: List<GroceryItem>): List<GroceryItem> = items.map { item ->
        var flagged = item
        if (item.name.isBlank()) flagged = flag(flagged, "empty_name")
        item.quantity?.let { quantity ->
            if (quantity <= 0) {
                flagged = flag(flagged, "bad_quantity")
            } else if (quantity > Config.SANITY_MAX_QUANTITY) {
                flagged = flag(flagged, "absurd_quantity")
            }
        }
        if (Config.CATEGORIES.none { it == item.category }) flagged = flag(flagged, "needs_category")
        flagged
    }

    private fun flag(item: GroceryItem, flagName: String): GroceryItem =
        if (flagName in item.flags) item else item.copy(flags = item.flags + flagName)
}
