package com.grocerylistbuilder.core.extract.crf

/**
 * Ports NLTK's `PerceptronTagger`/`AveragedPerceptron` (`nltk/tag/perceptron.py`) — the averaged-
 * perceptron POS tagger `ingredient_parser_nlp` uses, extended with its own `ingredient_tagdict`
 * overrides (`en/_utils.py:pos_tag`). Only inference is ported (training is irrelevant — the
 * weights are pretrained data loaded from resources).
 */
object PosTagger {
    private const val START1 = "-START-"
    private const val START2 = "-START2-"
    private const val END1 = "-END-"
    private const val END2 = "-END2-"

    /** feature -> (label -> weight). Lazy: only loaded (a few hundred ms of JSON parsing) the
     * first time tagging actually happens. */
    private val weights: Map<String, Map<String, Double>> by lazy {
        ResourceData.doubleWeightMap(ResourceData.readJsonObject("pos_tagger.weights.json.gz"))
    }
    private val classes: List<String> by lazy {
        ResourceData.stringList(ResourceData.readJson("pos_tagger.classes.json.gz"))
    }

    /** Base NLTK tagdict, overridden by `ingredient_tagdict` entries (mirrors
     * `tagger.tagdict.update(ingredient_tagdict)` — the ingredient-specific dict wins on conflicts). */
    private val tagdict: Map<String, String> by lazy {
        val base = ResourceData.stringMap(ResourceData.readJsonObject("pos_tagger.tagdict.json.gz"))
        val overrides = ResourceData.stringMap(ResourceData.readJsonObject("ingredient_tagdict.json.gz"))
        base + overrides
    }

    /** Tags a full token sequence (one ingredient line), returning (token, tag) pairs in order. */
    fun tag(tokens: List<String>): List<Pair<String, String>> {
        var prev = START1
        var prev2 = START2
        val context = buildList {
            add(START1); add(START2)
            addAll(tokens.map(::normalize))
            add(END1); add(END2)
        }

        val output = ArrayList<Pair<String, String>>(tokens.size)
        for ((i, word) in tokens.withIndex()) {
            val tag = tagdict[word] ?: predict(features(i, word, context, prev, prev2))
            output.add(word to tag)
            prev2 = prev
            prev = tag
        }
        return output
    }

    /** Mirrors `PerceptronTagger.normalize`. */
    private fun normalize(word: String): String {
        if ("-" in word && !word.startsWith("-")) return "!HYPHEN"
        if (word.length == 4 && word.all { it.isDigit() }) return "!YEAR"
        if (word.isNotEmpty() && word[0].isDigit()) return "!DIGITS"
        return word.lowercase()
    }

    private fun suffix3(s: String): String = if (s.length <= 3) s else s.substring(s.length - 3)

    /** Mirrors `PerceptronTagger._get_features`. [i] is the token's index within the untagged
     * `tokens` list passed to [tag] (context is shifted by 2 for the START padding internally). */
    private fun features(i: Int, word: String, context: List<String>, prev: String, prev2: String): List<String> {
        val ci = i + 2
        return listOf(
            "bias",
            "i suffix ${suffix3(word)}",
            "i pref1 ${if (word.isEmpty()) "" else word[0]}",
            "i-1 tag $prev",
            "i-2 tag $prev2",
            "i tag+i-2 tag $prev $prev2",
            "i word ${context[ci]}",
            "i-1 tag+i word $prev ${context[ci]}",
            "i-1 word ${context[ci - 1]}",
            "i-1 suffix ${suffix3(context[ci - 1])}",
            "i-2 word ${context[ci - 2]}",
            "i+1 word ${context[ci + 1]}",
            "i+1 suffix ${suffix3(context[ci + 1])}",
            "i+2 word ${context[ci + 2]}",
        )
    }

    /** Mirrors `AveragedPerceptron.predict` (without confidence — `ingredient_parser`'s `pos_tag()`
     * never requests it). Each active feature contributes weight*1 (feature values here are always
     * 1 -- each feature name in [features] is unique per call). Ties broken by largest label string,
     * matching Python's `max(classes, key=lambda label: (scores[label], label))`. */
    private fun predict(activeFeatures: List<String>): String {
        val scores = HashMap<String, Double>()
        for (feat in activeFeatures) {
            val labelWeights = weights[feat] ?: continue
            for ((label, weight) in labelWeights) {
                scores[label] = (scores[label] ?: 0.0) + weight
            }
        }
        return classes.maxWithOrNull(
            compareBy<String> { scores[it] ?: 0.0 }.thenBy { it },
        )!!
    }
}
