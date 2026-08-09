package com.grocerylistbuilder.core.extract.crf

import com.grocerylistbuilder.core.models.Ingredient

/**
 * The public entry point for the CRF port: parses one already-isolated ingredient line into an
 * [Ingredient], mirroring `en/parser.py:parse_ingredient_en()`'s orchestration (PreProcessor ->
 * features -> CRF tag -> `guess_ingredient_name` fallback -> PostProcessor) followed by exactly
 * what `grocery/extract/parse.py:parse_line()` reads off the result: names joined with ", ",
 * the *first* amount's quantity/unit, preparation+comment joined into notes, and the first name's
 * confidence (0.0, flagged "unparsed_name", if no name was found at all).
 */
object IngredientLineParser {
    private val NAME_LABELS = listOf("B_NAME_TOK", "I_NAME_TOK", "NAME_VAR", "NAME_MOD", "NAME_SEP")
    private const val MIN_GUESS_SCORE = 0.2

    fun parseIngredientLine(line: String): Ingredient {
        val normalised = Preprocessor.normalise(line)
        val (tokens, singularisedIndices) = Preprocessor.calculateTokensWithMetadata(normalised)

        if (tokens.isEmpty()) {
            return Ingredient(name = line.trim(), confidence = 0.0, flags = listOf("unparsed_name"))
        }

        val features = FeatureExtractor(tokens).sentenceFeatures()
        val tagging = CrfModel.tagFromFeatures(features)
        val labels = tagging.labels.toMutableList()

        if (labels.none { "NAME" in it.label }) {
            guessIngredientName(labels, tagging.marginals)
        }

        val labelledTokens = tokens.mapIndexed { i, t ->
            LabelledToken(
                index = t.index,
                text = t.text,
                posTag = t.posTag,
                label = labels[i].label,
                score = labels[i].confidence,
                plural = i in singularisedIndices,
            )
        }

        val parsed = PostProcessor(labelledTokens).parsed()
        return toIngredient(line, parsed)
    }

    /** Mirrors `parser.py:guess_ingredient_name` — only called when no token was labelled NAME.
     * Finds the most likely *NAME label (by marginal probability) at every position, keeps only
     * those above [MIN_GUESS_SCORE], and relabels the *longest run of consecutive* such positions. */
    private fun guessIngredientName(labels: MutableList<CrfLabel>, marginals: Array<DoubleArray>) {
        val candidates = mutableMapOf<Int, Pair<Double, String>>()
        for (i in labels.indices) {
            val best = NAME_LABELS.map { label -> CrfModel.marginal(marginals, label, i) to label }.maxByOrNull { it.first }!!
            if (best.first > MIN_GUESS_SCORE) candidates[i] = best
        }
        if (candidates.isEmpty()) return

        val groups = PostProcessor.groupConsecutive(candidates.keys.sorted())
        val longest = groups.maxByOrNull { it.size } ?: return
        for (idx in longest) {
            val (score, label) = candidates.getValue(idx)
            labels[idx] = CrfLabel(label, score)
        }
    }

    private fun toIngredient(originalLine: String, parsed: ParsedIngredientResult): Ingredient {
        val name = parsed.names.joinToString(", ") { it.text }.trim()
        val flags = mutableListOf<String>()
        val finalName: String
        val confidence: Double
        if (name.isEmpty()) {
            finalName = originalLine.trim()
            flags.add("unparsed_name")
            confidence = 0.0
        } else {
            finalName = name
            confidence = round4(parsed.names.first().confidence)
        }

        val (quantity, unit) = firstAmount(parsed.amounts)
        val notes = listOfNotNull(
            parsed.preparation?.text?.trim()?.takeIf { it.isNotEmpty() },
            parsed.comment?.text?.trim()?.takeIf { it.isNotEmpty() },
        ).joinToString(", ")

        return Ingredient(name = finalName, quantity = quantity, unit = unit, notes = notes, confidence = confidence, flags = flags)
    }

    /** Mirrors `grocery/extract/parse.py:_first_amount` — takes only the first parsed amount.
     * If it's a composite amount (e.g. "1 lb 2 oz"), the Python original would crash trying to
     * read `.quantity`/`.unit` off it; this port degrades to "no amount" instead (see
     * [AmountResult.Composite]'s doc comment). */
    private fun firstAmount(amounts: List<AmountResult>): Pair<Double?, String?> {
        val first = amounts.firstOrNull() ?: return null to null
        return when (first) {
            is AmountResult.Single -> first.amount.quantity to first.amount.unit
            is AmountResult.Composite -> null to null
        }
    }

    private fun round4(value: Double): Double {
        val factor = 10_000.0
        return Math.round(value * factor) / factor
    }
}
