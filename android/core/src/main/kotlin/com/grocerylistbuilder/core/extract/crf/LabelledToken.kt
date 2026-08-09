package com.grocerylistbuilder.core.extract.crf

/** Mirrors Python's `LabelledToken` dataclass. Mutable (`text`/`label`/`score`) because
 * `PostProcessor._convert_string_number_qty` rewrites QTY tokens in place, same as the Python
 * version does. */
class LabelledToken(
    val index: Int,
    var text: String,
    val posTag: String,
    var label: String,
    var score: Double,
    val plural: Boolean,
)

/** Mirrors `IngredientText` — a run of tokens sharing one label, joined into text. */
data class IngredientText(val text: String, val confidence: Double, val startingIndex: Int)

/**
 * A trimmed-down `IngredientAmount`: only what `grocery/extract/parse.py` actually reads
 * (`.quantity` coerced to float, `.unit` as a canonicalized string — see
 * [UnitCanonicalization]). Dropped vs. the Python original: `quantity_max`, `text`, and the
 * RANGE/MULTIPLIER/APPROXIMATE/SINGULAR/PREPARED_INGREDIENT flags — none of them are read by
 * `parse.py`, though the *logic* that would have set them still runs (see [PostProcessor]) since
 * it affects which token indices get consumed, which in turn affects name/comment/preparation
 * extraction correctness.
 */
data class ParsedAmount(val quantity: Double?, val unit: String?, val confidence: Double, val startingIndex: Int)

/** A composite amount (e.g. "1 lb 2 oz") is never read by `parse.py` beyond knowing where it
 * starts (for sort order) — if it happens to be the *first* amount in the sentence, `parse.py`'s
 * Python original would crash (`CompositeIngredientAmount` has no `.quantity`/`.unit`); this port
 * degrades gracefully instead, treating it as "no amount" rather than reproducing that crash. */
sealed class AmountResult {
    abstract val startingIndex: Int

    data class Single(val amount: ParsedAmount) : AmountResult() {
        override val startingIndex get() = amount.startingIndex
    }

    data class Composite(override val startingIndex: Int) : AmountResult()
}

/** Trimmed-down `ParsedIngredient` — `size`/`purpose`/`foundation_foods` are never read by
 * `parse.py`, so they're omitted (though `size`'s *extraction* still runs for its consumed-token
 * side effects — see [PostProcessor.parsed]). */
data class ParsedIngredientResult(
    val names: List<IngredientText>,
    val amounts: List<AmountResult>,
    val preparation: IngredientText?,
    val comment: IngredientText?,
)
