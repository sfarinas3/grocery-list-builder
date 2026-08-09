package com.grocerylistbuilder.core.extract.crf

import java.text.Normalizer

/** Ports `en/preprocess.py`'s `PreProcessor._normalise()` and `_calculate_tokens()` — the
 * deterministic text-cleanup and tokenization pipeline that runs before feature extraction. */
object Preprocessor {
    private val consecutiveSpaces = Regex("""\s+""")

    /** A curated subset of HTML entity decoding (numeric refs + the handful of named entities
     * relevant to quantities/fractions) rather than a full HTML5 entity table — every real text
     * source feeding this app (Jsoup-extracted web text, OCR, PDF/DOCX extraction, JSON strings)
     * already arrives HTML-decoded; this only guards against raw entities slipping through. */
    private val namedEntities = mapOf(
        "amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "nbsp" to " ", "deg" to "°",
        "frac12" to "½", "frac13" to "⅓", "frac14" to "¼", "frac15" to "⅕",
        "frac16" to "⅙", "frac18" to "⅛", "frac23" to "⅔", "frac25" to "⅖",
        "frac34" to "¾", "frac35" to "⅗", "frac38" to "⅜", "frac45" to "⅘",
        "frac56" to "⅚", "frac58" to "⅝", "frac78" to "⅞",
    )
    private val htmlEntity = Regex("""&(#x[0-9a-fA-F]+|#\d+|[a-zA-Z0-9]+);""")

    fun normalise(input: String): String {
        var s = input
        s = Patterns.CURRENCY_PATTERN.replace(s, "")
        s = s.replace("–", "-").replace("—", " - ")
        s = unescapeHtml(s)
        for ((from, to) in Constants.UNICODE_FRACTIONS) s = s.replace(from, to)
        s = combineQuantitiesSplitByAnd(s)
        s = identifyFractions(s)
        s = splitQuantityAndUnits(s)
        s = removeUnitTrailingPeriod(s)
        s = replaceStringRange(s)
        s = replaceDupeUnitsRanges(s)
        s = Patterns.QUANTITY_X_PATTERN.replace(s) { "${it.groupValues[1]}x " }
        s = Patterns.EXPANDED_RANGE.replace(s) { "${it.groupValues[1]}-${it.groupValues[2]}" }
        return s.trim()
    }

    private fun unescapeHtml(s: String): String = htmlEntity.replace(s) { m ->
        val body = m.groupValues[1]
        when {
            body.startsWith("#x") || body.startsWith("#X") -> body.drop(2).toIntOrNull(16)?.let { String(Character.toChars(it)) } ?: m.value
            body.startsWith("#") -> body.drop(1).toIntOrNull()?.let { String(Character.toChars(it)) } ?: m.value
            else -> namedEntities[body] ?: m.value
        }
    }

    private fun combineQuantitiesSplitByAnd(text: String): String {
        var s = text
        for (m in Patterns.FRACTION_SPLIT_AND_PATTERN.findAll(text)) {
            val whole = m.groupValues[1]
            val replacement = m.groupValues[2] + "#" + m.groupValues[3].replace("/", "$")
            s = s.replace(whole, replacement)
        }
        return s
    }

    private fun identifyFractions(input: String): String {
        var sentence = input.replace("⁄", "/")
        val matches = Patterns.FRACTION_PARTS_PATTERN.findAll(sentence).map { it.groupValues[1].trim() }.toList()
        if (matches.isEmpty()) return sentence

        for (match in matches.sortedByDescending { it.length }) {
            if (" " !in match) {
                val parts = match.split("/")
                if (parts.size == 2) {
                    val n = parts[0].toIntOrNull()
                    val d = parts[1].toIntOrNull()
                    if (n != null && d != null && n + d == 100) continue
                }
            }
            var replacement = match.replace("/", "$")
            replacement = if (" " in replacement) consecutiveSpaces.replace(replacement, "#") else "#$replacement"
            sentence = sentence.replace(match, replacement)
        }
        return sentence
    }

    private fun splitQuantityAndUnits(input: String): String {
        var s = input
        s = Patterns.QUANTITY_UNITS_PATTERN.replace(s) { "${it.groupValues[1]} ${it.groupValues[2]}" }
        s = Patterns.UNITS_QUANTITY_PATTERN.replace(s) { "${it.groupValues[1]} ${it.groupValues[2]}" }
        s = Patterns.UNITS_HYPHEN_QUANTITY_PATTERN.replace(s) { "${it.groupValues[1]} - ${it.groupValues[2]}" }
        s = Patterns.STRING_QUANTITY_HYPHEN_PATTERN.replace(s) { "${it.groupValues[1]} ${it.groupValues[2]}" }
        return s
    }

    private val unitTrailingPeriodUnits: List<String> = run {
        val base = listOf("tsp.", "tsps.", "tbsp.", "tbsps.", "tbs.", "tb.", "lb.", "lbs.", "oz.")
        base + base.map { it.replaceFirstChar { c -> c.uppercase() } }
    }

    private fun removeUnitTrailingPeriod(input: String): String {
        var s = input
        for (unit in unitTrailingPeriodUnits) s = s.replace(unit, unit.replace(".", ""))
        return s
    }

    private fun replaceStringRange(s: String): String =
        Patterns.STRING_RANGE_PATTERN.replace(s) { "${it.groupValues[1]}-${it.groupValues[5]}" }

    private fun replaceDupeUnitsRanges(input: String): String {
        var sentence = input
        for (m in Patterns.DUPE_UNIT_RANGES_PATTERN.findAll(input)) {
            val fullMatch = m.groupValues[1]
            val q1 = m.groupValues[2]
            val u1 = m.groupValues[3]
            val q2 = m.groupValues[4]
            val u2 = m.groupValues[5]
            if (u1 != u2 && !isUnitSynonym(u1, u2)) continue
            if (u1 !in Constants.FLATTENED_UNITS_LIST && u1 !in Constants.LENGTH_UNITS) continue
            sentence = sentence.replace(fullMatch, "$q1-$q2 $u1")
        }
        return sentence
    }

    private fun isUnitSynonym(unit1raw: String, unit2raw: String): Boolean {
        if (unit1raw !in Constants.FLATTENED_UNITS_LIST || unit2raw !in Constants.FLATTENED_UNITS_LIST) return false
        val unit1 = Constants.UNITS[unit1raw] ?: unit1raw
        val unit2 = Constants.UNITS[unit2raw] ?: unit2raw
        return Constants.UNIT_SYNONYMS.any { unit1 in it && unit2 in it }
    }

    // --- Tokenization + per-token attribute computation (`_calculate_tokens`) ---

    /** [singularisedIndices] mirrors `PreProcessor.singularised_indices` — token indices where a
     * plural unit word was replaced with its singular form; feeds `LabelledToken.plural` once the
     * CRF has labelled the sentence (see `IngredientLineParser`). */
    data class TokenizationResult(val tokens: List<Token>, val singularisedIndices: Set<Int>)

    fun calculateTokens(sentence: String, units: Map<String, String> = Constants.UNITS): List<Token> =
        calculateTokensWithMetadata(sentence, units).tokens

    fun calculateTokensWithMetadata(sentence: String, units: Map<String, String> = Constants.UNITS): TokenizationResult {
        val tagged = PosTagger.tag(Tokenizer.tokenize(sentence))
        val tokens = ArrayList<Token>(tagged.size)
        val singularisedIndices = mutableSetOf<Int>()

        for ((i, pair) in tagged.withIndex()) {
            val rawText = pair.first
            var posTag = pair.second

            val singular = units[rawText]
            val text: String
            val featText: String
            if (singular != null) {
                singularisedIndices.add(i)
                text = singular
                featText = singular
            } else if (isNumeric(rawText)) {
                text = rawText
                featText = "!num"
            } else {
                text = rawText
                featText = rawText
            }

            posTag = when {
                isNumeric(text) -> "CD"
                text.lowercase() == "c" || text.lowercase() == "g" -> "NN"
                text.lowercase() in setOf("and/or", "or", "and") -> "CC"
                text.lowercase() == "e.g." -> "IN"
                text.lowercase() == "/" -> "SYM"
                text == "in" && i > 0 && tokens[i - 1].featText == "!num" -> "NN"
                else -> posTag
            }

            tokens.add(
                Token(
                    index = i,
                    text = text,
                    featText = featText,
                    posTag = posTag,
                    stem = Stemmer.stem(featText),
                    shape = wordShape(featText),
                    isCapitalised = Patterns.CAPITALISED_PATTERN.containsMatchIn(featText),
                    isUnit = featText.lowercase() in units.values && featText.lowercase() !in Constants.LENGTH_UNITS,
                    isPunc = isPunctuation(featText),
                    isAmbiguousUnit = featText in Constants.AMBIGUOUS_UNITS,
                ),
            )
        }
        return TokenizationResult(tokens, singularisedIndices)
    }

    private val pythonPunctuation = "!\"#$%&'()*+,-./:;<=>?@[\\]^_`{|}~"

    /** Mirrors Python's `token in string.punctuation` (substring containment, not "one-of") —
     * in practice `feat_text` tokens are always empty, single punctuation chars, words, or "--",
     * so length-based matching is equivalent to substring containment for every real input. */
    private fun isPunctuation(token: String): Boolean =
        token.isEmpty() || (token.length == 1 && token[0] in pythonPunctuation) || token == "--"

    fun isNumeric(token: String): Boolean {
        if (token == "00") return false
        if (Patterns.FRACTION_TOKEN_PATTERN.matches(token)) return true
        if (token.lowercase() in Constants.STRING_NUMBERS.keys) return true
        if ("-" in token) return token.split("-").all { isNumeric(it) }
        if (token == "dozen") return true
        if (token.endsWith("x")) return token.dropLast(1).toDoubleOrNull() != null
        return token.toDoubleOrNull() != null
    }

    private fun wordShape(token: String): String {
        val normalised = removeAccents(token)
        val sb = StringBuilder(normalised.length)
        for (c in normalised) {
            sb.append(
                when {
                    c in 'a'..'z' -> 'x'
                    c in 'A'..'Z' -> 'X'
                    c in '0'..'9' -> 'd'
                    else -> c
                },
            )
        }
        return sb.toString()
    }

    private fun removeAccents(token: String): String {
        val normalized = Normalizer.normalize(token, Normalizer.Form.NFD)
        return normalized.filter { Character.getType(it) != Character.NON_SPACING_MARK.toInt() }
    }
}
