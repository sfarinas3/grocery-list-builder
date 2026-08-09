package com.grocerylistbuilder.core.extract.crf

import com.grocerylistbuilder.core.models.Ingredient

/**
 * Finds candidate ingredient lines within raw, unstructured text (OCR output, document text, a
 * non-JSON-LD web page) — the job the on-device LLM used to do. Unlike the LLM, this is a plain
 * heuristic with no model behind it: no Python equivalent exists to port (even the reference
 * Python backend uses an LLM for this step). Deliberately a first cut, not tuned against a large
 * corpus — expected to need iteration once tested against real photos/documents.
 */
object IngredientLineFinder {
    private val headingPattern = Regex(
        """^\s*(ingredients?|what you('| wi)ll need|shopping list)\s*:?\s*$""",
        RegexOption.IGNORE_CASE,
    )
    private val nextSectionPattern = Regex(
        """^\s*(instructions?|directions?|method|steps?|preparation|notes?|nutrition)\s*:?\s*$""",
        RegexOption.IGNORE_CASE,
    )

    private val instructionVerbs = setOf(
        "preheat", "mix", "add", "bake", "stir", "combine", "pour", "whisk", "heat", "cook",
        "serve", "remove", "place", "cover", "season", "cut", "chop", "let", "transfer",
        "reduce", "simmer", "drain", "spread", "sprinkle", "garnish", "repeat", "continue",
        "meanwhile", "once", "using", "brown", "boil", "roast", "grill", "melt", "beat",
        "fold", "knead", "chill", "freeze", "marinate", "toss", "arrange", "line", "grease",
    )

    /** Below this, [structureFromText]'s CRF-informed correction pass drops the result even if it
     * produced a non-empty name — the CRF's "guess a name anyway" fallback (see
     * [IngredientLineParser.guessIngredientName]) almost always finds *something* above its own
     * low 0.2 threshold, so an empty name alone is too weak a signal for genuinely non-ingredient
     * text (e.g. OCR noise) that slipped past the line-level heuristic. */
    private const val MIN_CONFIDENCE = 0.3

    private val leadingQuantityPattern = Regex(
        """^\s*(\d|[¼½¾⅓⅔⅕⅖⅗⅘⅙⅚⅛⅜⅝⅞]|(${Constants.STRING_NUMBERS.keys.joinToString("|")})\b)""",
        RegexOption.IGNORE_CASE,
    )
    private val bulletPattern = Regex("""^\s*[-*•]\s*""")

    /** Extracts and structures ingredient lines from [text] in one pass: find candidate lines,
     * then run each through [IngredientLineParser.parseIngredientLine], dropping lines the CRF
     * couldn't find a name for at all (a second, model-informed correction on top of the
     * line-level heuristic). */
    fun structureFromText(text: String): List<Ingredient> {
        val candidates = findCandidateLines(text.trim())
        return candidates
            .map { IngredientLineParser.parseIngredientLine(it) }
            .filter { "unparsed_name" !in it.flags && it.confidence >= MIN_CONFIDENCE }
    }

    fun findCandidateLines(text: String): List<String> {
        val lines = text.lines()
        val headingIdx = lines.indexOfFirst { headingPattern.matches(it) }
        if (headingIdx != -1) {
            val section = mutableListOf<String>()
            for (line in lines.drop(headingIdx + 1)) {
                if (nextSectionPattern.matches(line)) break
                if (line.isBlank()) continue
                section.add(stripBullet(line).trim())
            }
            return section
        }

        return lines
            .map { stripBullet(it).trim() }
            .filter { it.isNotEmpty() && isCandidateLine(it) }
    }

    private fun stripBullet(line: String): String = bulletPattern.replace(line, "")

    private fun isCandidateLine(line: String): Boolean {
        val words = line.trim().split(Regex("""\s+"""))
        if (words.isEmpty()) return false

        // Reject if either of the first two words is an instruction verb -- catches both
        // "Preheat the oven..." and numbered/bulleted steps like "1. Preheat the oven...".
        val leadingWords = words.take(2).map { it.trim(':', '.', ',').lowercase() }
        if (leadingWords.any { it in instructionVerbs }) return false

        if (leadingQuantityPattern.containsMatchIn(line)) return true

        // A unit word near the start suggests "<qty> <unit> ...". Checking only nearby words
        // (not the whole line) avoids false positives from ordinary words that double as unit
        // names showing up deep in an instruction sentence (e.g. "brown the beef in a large pot").
        return words.take(4).any { it.trim(',', '.', ':', ';').lowercase() in Constants.FLATTENED_UNITS_LIST }
    }
}
