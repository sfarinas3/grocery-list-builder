package com.grocerylistbuilder.core.extract.crf

/** A token's CRF feature dict — values are always `String` or `Boolean`, mirroring Python's
 * `FeatureDict = dict[str, str | bool]`. */
typealias FeatureDict = Map<String, Any>

/** Converts a [FeatureDict] into the set of feature strings the CRF model actually scores,
 * mirroring `NumpyCRFInference._convert_features` exactly: a `true` boolean becomes just its key;
 * a `false` boolean is dropped entirely; every other value (including an empty string) becomes
 * `"key:value"`. */
fun convertFeatures(features: FeatureDict): Set<String> =
    features.entries.mapNotNull { (key, value) ->
        when (value) {
            false -> null
            true -> key
            else -> "$key:$value"
        }
    }.toSet()

/** Ports the feature-generation half of `en/preprocess.py`'s `PreProcessor` (everything from
 * `_common_features` down to `sentence_features`) plus the token-context predicates
 * (`_is_dimension`, `_is_length_unit`, `_is_inside_parentheses`, `_follows_comma`,
 * `_follows_plus`, `_sentence_length_bucket`) that only make sense with the full token list in
 * hand. Tokenization/normalization itself lives in [Preprocessor]; this operates on its output. */
class FeatureExtractor(private val tokens: List<Token>) {
    private val structure = StructureFeatures(tokens)

    fun sentenceFeatures(): List<FeatureDict> = tokens.map { tokenFeatures(it) }

    private fun isDimension(featText: String): Boolean = featText.lowercase() in Constants.DIMENSIONS

    private fun isLengthUnit(index: Int): Boolean {
        val token = tokens[index].featText
        if (token == "in") {
            return index > 0 && tokens[index - 1].featText == "!num"
        }
        return token.lowercase() in Constants.LENGTH_UNITS
    }

    private fun followsComma(index: Int): Boolean = tokens.subList(0, index).any { it.featText == "," }

    private fun followsPlus(index: Int): Boolean = tokens.subList(0, index).any { it.featText == "plus" }

    private fun isInsideParentheses(index: Int): Boolean {
        if (tokens[index].featText in setOf("(", ")", "[", "]")) return true
        val openParens = mutableListOf<Int>()
        val closedParens = mutableListOf<Int>()
        for ((i, t) in tokens.withIndex()) {
            if (t.featText == "(" || t.featText == "[") openParens.add(i)
            else if (t.featText == ")" || t.featText == "]") closedParens.add(i)
        }
        for (k in 0 until minOf(openParens.size, closedParens.size)) {
            if (openParens[k] < index && index < closedParens[k]) return true
        }
        return false
    }

    private fun sentenceLengthBucket(): Int {
        val length = tokens.size
        var bucket = 1
        for (b in listOf(2, 4, 8, 12, 16, 20, 32, 64)) if (length >= b) bucket = b
        return bucket
    }

    private fun commonFeatures(index: Int, prefix: String): FeatureDict {
        val t = tokens[index]
        val struct = structure.tokenFeatures(index)
        return mapOf(
            "${prefix}is_capitalised" to t.isCapitalised,
            "${prefix}is_unit" to t.isUnit,
            "${prefix}is_punc" to t.isPunc,
            "${prefix}is_ambiguous" to t.isAmbiguousUnit,
            "${prefix}is_in_parens" to isInsideParentheses(index),
            "${prefix}is_after_comma" to followsComma(index),
            "${prefix}is_after_plus" to followsPlus(index),
            "${prefix}word_shape" to t.shape,
            "${prefix}is_length_unit" to isLengthUnit(index),
            "${prefix}is_dimension" to isDimension(t.featText),
        ) + structurePrefixed(struct, prefix)
    }

    private fun structurePrefixed(s: StructureTokenFeatures, prefix: String): FeatureDict = mapOf(
        "${prefix}mip_start" to s.mipStart,
        "${prefix}mip_end" to s.mipEnd,
        "${prefix}after_sentence_split" to s.afterSentenceSplit,
        "${prefix}example_phrase" to s.examplePhrase,
        "${prefix}dimensional_phrase" to s.dimensionalPhrase,
    )

    private fun ngramFeatures(token: String, prefix: String): FeatureDict {
        if (token == "!num") return emptyMap()
        val features = mutableMapOf<String, Any>()
        if (token.length >= 4) {
            features["${prefix}prefix_3"] = token.substring(0, 3)
            features["${prefix}suffix_3"] = token.substring(token.length - 3)
        }
        if (token.length >= 5) {
            features["${prefix}prefix_4"] = token.substring(0, 4)
            features["${prefix}suffix_4"] = token.substring(token.length - 4)
        }
        if (token.length >= 6) {
            features["${prefix}prefix_5"] = token.substring(0, 5)
            features["${prefix}suffix_5"] = token.substring(token.length - 5)
        }
        return features
    }

    private fun tokenFeatures(token: Token): FeatureDict {
        val index = token.index
        val features = mutableMapOf<String, Any>()

        features["bias"] = ""
        features["sentence_length"] = sentenceLengthBucket().toString()

        features["pos"] = token.posTag
        features["stem"] = token.stem
        if (token.featText != token.stem) features["token"] = token.featText

        features += commonFeatures(index, "")
        features += ngramFeatures(token.featText, "")

        if (index > 0) {
            val prev = tokens[index - 1]
            features["prev_stem"] = prev.stem
            features["prev_pos_ngram"] = "${prev.posTag}+${token.posTag}"
            features["prev_pos"] = prev.posTag
            features += commonFeatures(index - 1, "prev_")
        }
        if (index > 1) {
            val prev2 = tokens[index - 2]
            val prev1 = tokens[index - 1]
            features["prev2_stem"] = prev2.stem
            features["prev2_pos_ngram"] = "${prev2.posTag}+${prev1.posTag}+${token.posTag}"
            features["prev2_pos"] = prev2.posTag
            features += commonFeatures(index - 2, "prev2_")
        }
        if (index > 2) {
            val prev3 = tokens[index - 3]
            val prev2 = tokens[index - 2]
            val prev1 = tokens[index - 1]
            features["prev3_stem"] = prev3.stem
            features["prev3_pos_ngram"] = "${prev3.posTag}+${prev2.posTag}+${prev1.posTag}+${token.posTag}"
            features["prev3_pos"] = prev3.posTag
            features += commonFeatures(index - 3, "prev3_")
        }
        if (index < tokens.size - 1) {
            val next = tokens[index + 1]
            features["next_stem"] = next.stem
            features["next_pos_ngram"] = "${token.posTag}+${next.posTag}"
            features["next_pos"] = next.posTag
            features += commonFeatures(index + 1, "next_")
        }
        if (index < tokens.size - 2) {
            val next2 = tokens[index + 2]
            val next1 = tokens[index + 1]
            features["next2_stem"] = next2.stem
            features["next2_pos_ngram"] = "${token.posTag}+${next1.posTag}+${next2.posTag}"
            features["next2_pos"] = next2.posTag
            features += commonFeatures(index + 2, "next2_")
        }
        if (index < tokens.size - 3) {
            val next3 = tokens[index + 3]
            val next2 = tokens[index + 2]
            val next1 = tokens[index + 1]
            features["next3_stem"] = next3.stem
            features["next3_pos_ngram"] = "${token.posTag}+${next1.posTag}+${next2.posTag}+${next3.posTag}"
            features["next3_pos"] = next3.posTag
            features += commonFeatures(index + 3, "next3_")
        }

        return features
    }
}
