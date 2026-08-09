package com.grocerylistbuilder.core.extract.crf

/** Boolean structure-features for one token, mirrors `SentenceStrucureFeatures.token_features`'s
 * returned dict shape (a missing/false key has the same effect downstream once these are folded
 * into the CRF feature set — see [FeatureExtractor] — so there's no need to distinguish "absent"
 * from "false" here). */
data class StructureTokenFeatures(
    val mipStart: Boolean = false,
    val mipEnd: Boolean = false,
    val afterSentenceSplit: Boolean = false,
    val examplePhrase: Boolean = false,
    val dimensionalPhrase: Boolean = false,
)

/**
 * Ports `en/_structure_features.py`'s `SentenceStrucureFeatures`. Detects multi-ingredient
 * phrases, compound-sentence subject splits, "such as"-style example phrases, and dimensional
 * phrases ("2 inch thick"), using [TagPatternMatcher] to replicate the real `nltk.RegexpParser`
 * grammars (verified stage-by-stage against the actual NLTK objects — each `LABEL: {pattern}` is
 * its own sequential stage, not a single combined pass).
 */
class StructureFeatures(private val tokens: List<Token>) {
    private val mipPhrases: List<List<Int>>
    private val sentenceSplits: List<Int>
    private val examplePhrases: List<List<Int>>
    private val dimensionalPhrases: List<List<Int>>

    init {
        mipPhrases = detectMipPhrases()
        sentenceSplits = detectSentenceSplits()
        examplePhrases = detectExamples()
        dimensionalPhrases = detectDimensionalPhrases()
    }

    private fun ccIsNotOr(textPos: List<Pair<String, String>>, indices: List<Int>): Boolean {
        val ccIndex = indices.indexOfFirst { textPos[it].second == "CC" }
        if (ccIndex == -1) return false
        return textPos[indices[ccIndex]].first.lowercase() != "or"
    }

    private fun leafSegments(tags: List<String>): List<Segment> = tags.mapIndexed { i, t -> Segment(t, listOf(i)) }

    /** Runs [stages] (label, pattern pairs) in sequence, each on the previous stage's output, then
     * returns the FINAL segment list's entries in left-to-right order whose tag is one of [labels]
     * — mirrors `_get_subtree_indices(parsed, labels)` iterating the final tree's children in order. */
    private fun runStages(initial: List<Segment>, stages: List<Pair<String, List<Elem>>>, labels: Set<String>): List<List<Int>> {
        var segments = initial
        for ((label, pattern) in stages) {
            segments = TagPatternMatcher.applyStage(segments, pattern, label).first
        }
        return segments.filter { it.tag in labels }.map { it.indices }
    }

    private fun detectMipPhrases(): List<List<Int>> {
        val textPos = tokens.map { it.text to it.posTag }
        val phrases = runStages(leafSegments(tokens.map { it.posTag }), listOf("EMIP" to Grammars.EMIP, "MIP" to Grammars.MIP), setOf("EMIP", "MIP"))

        val result = mutableListOf<List<Int>>()
        for (raw in phrases) {
            if (ccIsNotOr(textPos, raw)) continue

            var indices = raw
            val discard = Constants.FLATTENED_UNITS_LIST + Constants.SIZES
            while (indices.isNotEmpty() && tokens[indices[0]].text.lowercase() in discard) {
                indices = indices.drop(1)
            }
            if (indices.isEmpty()) continue
            if (tokens[indices[0]].posTag == "CC") continue

            result.add(indices)
        }
        return result
    }

    private fun detectSentenceSplits(): List<Int> {
        val textPos = tokens.map { t ->
            val tag = when {
                t.text.lowercase() in Constants.FLATTENED_UNITS_LIST -> "UNIT"
                t.text.lowercase() in Constants.SIZES -> "SIZE"
                t.text.lowercase() == "half" -> "HALF"
                else -> t.posTag
            }
            t.featText to tag
        }
        val phrases = runStages(
            leafSegments(textPos.map { it.second }),
            listOf("CS_WU" to Grammars.CS_WU, "CS_NU" to Grammars.CS_NU, "CS_HALF" to Grammars.CS_HALF),
            setOf("CS_WU", "CS_NU", "CS_HALF"),
        )

        val result = mutableListOf<Int>()
        for (indices in phrases) {
            if (ccIsNotOr(textPos, indices)) continue
            result.add(indices[0])
        }
        return result
    }

    private fun detectExamples(): List<List<Int>> {
        val candidates = runStages(
            leafSegments(tokens.map { it.posTag }),
            listOf("NP" to Grammars.NP, "EX" to Grammars.EX),
            setOf("EX"),
        )

        val result = mutableListOf<List<Int>>()
        for (indices in candidates) {
            val phraseTextPos = indices.map { tokens[it].text.uppercase() to tokens[it].posTag }
            when {
                phraseTextPos.size >= 2 && phraseTextPos.subList(0, 2) == Grammars.EXAMPLE_PHRASE_START_JJ -> result.add(indices)
                phraseTextPos.isNotEmpty() && phraseTextPos[0] in Grammars.EXAMPLE_PHRASE_START_IN -> result.add(indices)
                phraseTextPos.size >= 2 && phraseTextPos[0].second == "JJ" && phraseTextPos[1] in Grammars.EXAMPLE_PHRASE_START_IN ->
                    result.add(indices.drop(1))
            }
        }
        return result
    }

    private fun detectDimensionalPhrases(): List<List<Int>> {
        val textPos = tokens.map { t ->
            val tag = when {
                t.text.lowercase() in Constants.LENGTH_UNITS && t.posTag != "IN" -> "LEN"
                t.text.lowercase() in Constants.DIMENSIONS -> "DIM"
                else -> t.posTag
            }
            t.featText to tag
        }
        return runStages(
            leafSegments(textPos.map { it.second }),
            listOf(
                "LENGTH" to Grammars.LENGTH, "PLENGTH" to Grammars.PLENGTH,
                "SLENGTH" to Grammars.SLENGTH, "DP" to Grammars.DP,
            ),
            setOf("DP"),
        )
    }

    fun tokenFeatures(index: Int): StructureTokenFeatures {
        var mipStart = false
        var mipEnd = false
        for (phrase in mipPhrases) {
            if (index !in phrase) continue
            if (index == phrase.first()) mipStart = true
            if (index == phrase.last()) mipEnd = true
        }
        val afterSentenceSplit = sentenceSplits.any { index >= it }
        val examplePhrase = examplePhrases.any { index in it }
        val dimensionalPhrase = dimensionalPhrases.any { index in it }
        return StructureTokenFeatures(mipStart, mipEnd, afterSentenceSplit, examplePhrase, dimensionalPhrase)
    }
}

/** The 4 grammars from `en/_structure_features.py`, translated term-by-term into [Elem] lists. */
private object Grammars {
    private fun atom(vararg alts: String, min: Int = 1, max: Int = 1) = Elem.Atom(alts.toList(), min, max)
    private const val INF = Int.MAX_VALUE

    // EMIP: <NN.*|JJ.*>+<,><NN.*|JJ.*>+<,>?<CC><DT|NN.*|JJ.*>*<NN.*>
    val EMIP = listOf(
        atom("NN.*", "JJ.*", min = 1, max = INF),
        atom(","),
        atom("NN.*", "JJ.*", min = 1, max = INF),
        atom(",", min = 0, max = 1),
        atom("CC"),
        atom("DT", "NN.*", "JJ.*", min = 0, max = INF),
        atom("NN.*"),
    )

    // MIP: <NN.*|JJ.*>+<CC><DT|NN.*|JJ.*>*<NN.*>
    val MIP = listOf(
        atom("NN.*", "JJ.*", min = 1, max = INF),
        atom("CC"),
        atom("DT", "NN.*", "JJ.*", min = 0, max = INF),
        atom("NN.*"),
    )

    // CS_WU: <CC><RB>?<CD|DT>+<RB>?<UNIT|SIZE>+
    val CS_WU = listOf(
        atom("CC"),
        atom("RB", min = 0, max = 1),
        atom("CD", "DT", min = 1, max = INF),
        atom("RB", min = 0, max = 1),
        atom("UNIT", "SIZE", min = 1, max = INF),
    )

    // CS_NU: <CC><CD|DT>+<NN.*|JJ.*>
    val CS_NU = listOf(
        atom("CC"),
        atom("CD", "DT", min = 1, max = INF),
        atom("NN.*", "JJ.*"),
    )

    // CS_HALF: <CC><HALF>
    val CS_HALF = listOf(atom("CC"), atom("HALF"))

    // NP: (<NN.*|JJ.*>+<,>?)*<CC|DT>?<NN.*|JJ.*>*<NN.*>
    val NP = listOf(
        Elem.Group(
            sub = listOf(atom("NN.*", "JJ.*", min = 1, max = INF), atom(",", min = 0, max = 1)),
            min = 0, max = INF,
        ),
        atom("CC", "DT", min = 0, max = 1),
        atom("NN.*", "JJ.*", min = 0, max = INF),
        atom("NN.*"),
    )

    // EX: <JJ.*>?<IN><NP>
    val EX = listOf(atom("JJ.*", min = 0, max = 1), atom("IN"), atom("NP"))

    // LENGTH: <CD><LEN>
    val LENGTH = listOf(atom("CD"), atom("LEN"))

    // PLENGTH: <\(><LENGTH><\)>
    val PLENGTH = listOf(atom("("), atom("LENGTH"), atom(")"))

    // SLENGTH: <SYM><LENGTH>
    val SLENGTH = listOf(atom("SYM"), atom("LENGTH"))

    // DP: <LENGTH><SLENGTH|PLENGTH>?<IN>?<DIM>*
    val DP = listOf(
        atom("LENGTH"),
        atom("SLENGTH", "PLENGTH", min = 0, max = 1),
        atom("IN", min = 0, max = 1),
        atom("DIM", min = 0, max = INF),
    )

    val EXAMPLE_PHRASE_START_IN = listOf("AS" to "IN", "LIKE" to "IN", "E.G." to "IN")
    val EXAMPLE_PHRASE_START_JJ = listOf("SUCH" to "JJ", "AS" to "IN")
}
