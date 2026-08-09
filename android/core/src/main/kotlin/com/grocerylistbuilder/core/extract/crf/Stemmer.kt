package com.grocerylistbuilder.core.extract.crf

/**
 * Porter2/Snowball English stemmer, matching NLTK's `EnglishStemmer` (which the CRF model was
 * trained against — the "stem" feature must match exactly, or the model's learned weights won't
 * fire correctly). Ported from the canonical Snowball English algorithm
 * (snowballstem.org/algorithms/english/stemmer.html), not NLTK's Python wrapper directly, since
 * NLTK's stemmer itself just implements that same published algorithm.
 */
object Stemmer {
    private val vowels = "aeiouy"

    private val exceptionalForms = mapOf(
        "skis" to "ski", "skies" to "sky", "dying" to "die", "lying" to "lie", "tying" to "tie",
        "idly" to "idl", "gently" to "gentl", "ugly" to "ugli", "early" to "earli",
        "only" to "onli", "singly" to "singl",
    )

    private val invariantForms = setOf(
        "sky", "news", "howe", "atlas", "cosmos", "bias", "andes",
        "inning", "innings", "outing", "outings", "canning", "cannings",
        "herring", "herrings", "earring", "earrings",
        "proceed", "exceed", "succeed",
    )

    private data class Step2Rule(val suffix: String, val replacement: String, val condition: ((String) -> Boolean)? = null)

    private val step2Rules = listOf(
        Step2Rule("ization", "ize"), Step2Rule("ational", "ate"), Step2Rule("fulness", "ful"),
        Step2Rule("ousness", "ous"), Step2Rule("iveness", "ive"), Step2Rule("biliti", "ble"),
        Step2Rule("lessli", "less"), Step2Rule("entli", "ent"), Step2Rule("ation", "ate"),
        Step2Rule("alism", "al"), Step2Rule("aliti", "al"), Step2Rule("ousli", "ous"),
        Step2Rule("iviti", "ive"), Step2Rule("fulli", "ful"), Step2Rule("enci", "ence"),
        Step2Rule("anci", "ance"), Step2Rule("abli", "able"), Step2Rule("izer", "ize"),
        Step2Rule("ator", "ate"), Step2Rule("alli", "al"), Step2Rule("bli", "ble"),
        Step2Rule("ogi", "og") { stem -> stem.isNotEmpty() && stem.last() == 'l' },
        Step2Rule("li", "") { stem -> stem.isNotEmpty() && stem.last() in "cdeghkmnrt" },
        Step2Rule("tional", "tion"),
    ).sortedByDescending { it.suffix.length }

    private val step3Rules = listOf(
        Step3Rule("ational", "ate"), Step3Rule("tional", "tion"), Step3Rule("alize", "al"),
        Step3Rule("icate", "ic"), Step3Rule("iciti", "ic"), Step3Rule("ative", "", requiresR2 = true),
        Step3Rule("ical", "ic"), Step3Rule("ness", ""), Step3Rule("ful", ""),
    ).sortedByDescending { it.suffix.length }

    private data class Step3Rule(val suffix: String, val replacement: String, val requiresR2: Boolean = false)

    private val step4Suffixes = listOf(
        "ement", "ance", "ence", "able", "ible", "ment", "ant", "ent", "ism", "ate", "iti",
        "ous", "ive", "ize", "ion", "al", "er", "ic",
    ).sortedByDescending { it.length }

    fun stem(word: String): String {
        val lower = word.lowercase()
        if (lower.length <= 2) return lower
        exceptionalForms[lower]?.let { return it }
        if (lower in invariantForms) return lower

        val marked = markY(lower)
        // R1/R2 are computed once, on the word as it stands after step 1a (matching the reference
        // Snowball implementation), as absolute character-offset boundaries. Since every later step
        // only strips suffixes (never changes the prefix), those offsets stay meaningful indices
        // into the shrinking word for the rest of the algorithm.
        var w = step0(marked)
        w = step1a(w)
        if (w in invariantForms) return w.replace('Y', 'y')

        val (r1, r2) = computeR1R2(w)
        w = step1b(w, r1)
        w = step1c(w)
        w = step2(w, r1)
        w = step3(w, r1, r2)
        w = step4(w, r2)
        w = step5(w, r1, r2)

        return w.replace('Y', 'y')
    }

    /** Set initial y, or y immediately after a vowel, to 'Y' (treated as a consonant from here on). */
    private fun markY(word: String): String {
        val chars = word.toCharArray()
        for (i in chars.indices) {
            if (chars[i] == 'y' && (i == 0 || chars[i - 1] in vowels)) chars[i] = 'Y'
        }
        return String(chars)
    }

    private fun isVowel(c: Char): Boolean = c in vowels

    /** R1: region after the first non-vowel following a vowel (special-cased prefixes per spec).
     * R2: R1-style rule applied again, searching within R1. Returns (r1Start, r2Start), either may
     * equal word.length (empty region). */
    private fun computeR1R2(word: String): Pair<Int, Int> {
        val r1Start = when {
            word.startsWith("gener") || word.startsWith("commun") || word.startsWith("arsen") -> {
                if (word.startsWith("commun")) 6 else 5
            }
            else -> findRegionStart(word, 0)
        }
        val r2Start = findRegionStart(word, r1Start)
        return r1Start to r2Start
    }

    private fun findRegionStart(word: String, from: Int): Int {
        var i = from
        while (i < word.length && !isVowel(word[i])) i++
        while (i < word.length && isVowel(word[i])) i++
        return (i + 1).coerceAtMost(word.length)
    }

    private fun step0(w: String): String = when {
        w.endsWith("'s'") -> w.dropLast(3)
        w.endsWith("'s") -> w.dropLast(2)
        w.endsWith("'") -> w.dropLast(1)
        else -> w
    }

    private fun step1a(w: String): String {
        if (w.endsWith("sses")) return w.dropLast(2)
        if (w.endsWith("ied") || w.endsWith("ies")) {
            val stemLen = w.length - 3
            return if (stemLen > 1) w.dropLast(2) else w.dropLast(1)
        }
        if (w.endsWith("us") || w.endsWith("ss")) return w
        if (w.endsWith("s") && !w.endsWith("us") && !w.endsWith("ss")) {
            val beforeS = w.dropLast(1)
            // "contains a vowel not immediately before the s" -> any vowel at index < length-1
            val hasEarlierVowel = beforeS.dropLast(1).any { isVowel(it) }
            if (hasEarlierVowel) return beforeS
        }
        return w
    }

    private val step1bSuffixesByLengthDesc = listOf("eedly", "ingly", "edly", "eed", "ing", "ed").sortedByDescending { it.length }

    /** Longest-matching-suffix-wins, same as every other step — "eed"/"eedly" and "ed"/"ing"/... are
     * candidates in the SAME search, not independent fallbacks. Matching "eed" on "feed" and then
     * failing its R1 condition must NOT fall through to trying "ed" too (a bug caught by the
     * differential test: stem("feed") was wrongly returning "fee" instead of "feed"). */
    private fun step1b(w: String, r1: Int): String {
        val suf = step1bSuffixesByLengthDesc.firstOrNull { w.endsWith(it) } ?: return w
        return when (suf) {
            "eedly" -> if (w.length - 5 >= r1) w.dropLast(3) else w
            "eed" -> if (w.length - 3 >= r1) w.dropLast(1) else w
            else -> {
                val stemPart = w.dropLast(suf.length)
                if (stemPart.any { isVowel(it) }) finishStep1b(stemPart, r1) else w
            }
        }
    }

    private fun finishStep1b(stemPart: String, r1: Int): String {
        if (stemPart.endsWith("at") || stemPart.endsWith("bl") || stemPart.endsWith("iz")) {
            return stemPart + "e"
        }
        if (stemPart.length >= 2 && stemPart[stemPart.length - 1] == stemPart[stemPart.length - 2] &&
            stemPart[stemPart.length - 1] !in "lsz" && !isVowel(stemPart.last())
        ) {
            return stemPart.dropLast(1)
        }
        if (isShortWord(stemPart, r1)) return stemPart + "e"
        return stemPart
    }

    // (isConsonant intentionally omitted — !isVowel(c) already treats marked 'Y' as a consonant,
    // since `vowels` only contains lowercase 'y'.)

    /** A word is "short" if R1 is empty relative to it (i.e. [r1] — an absolute offset computed
     * once on the pre-suffix-removal word — falls at or beyond [w]'s end) and it ends in a short
     * syllable: consonant-vowel-consonant, with the final consonant not w/x/Y. [r1] indexes validly
     * into [w] because [w] is always a prefix of the word it was computed from (steps only strip
     * suffixes, never touch the start of the word). */
    private fun isShortWord(w: String, r1: Int): Boolean {
        if (r1 < w.length) return false
        return endsInShortSyllable(w)
    }

    private fun endsInShortSyllable(w: String): Boolean {
        if (w.length < 2) return false
        if (w.length == 2) {
            return !isVowel(w[0]) && isVowel(w[1])
        }
        val a = w[w.length - 3]
        val b = w[w.length - 2]
        val c = w[w.length - 1]
        return !isVowel(a) && isVowel(b) && !isVowel(c) && c !in "wxY"
    }

    private fun step1c(w: String): String {
        if (w.length <= 1) return w
        val last = w.last()
        if ((last == 'y' || last == 'Y') && !isVowel(w[w.length - 2])) {
            return w.dropLast(1) + "i"
        }
        return w
    }

    private fun step2(w: String, r1: Int): String {
        for (rule in step2Rules) {
            if (w.endsWith(rule.suffix) && w.length - rule.suffix.length >= r1) {
                val stemPart = w.dropLast(rule.suffix.length)
                if (rule.condition == null || rule.condition.invoke(stemPart)) {
                    return stemPart + rule.replacement
                }
            }
        }
        return w
    }

    private fun step3(w: String, r1: Int, r2: Int): String {
        for (rule in step3Rules) {
            if (w.endsWith(rule.suffix) && w.length - rule.suffix.length >= r1) {
                if (rule.requiresR2 && w.length - rule.suffix.length < r2) continue
                return w.dropLast(rule.suffix.length) + rule.replacement
            }
        }
        return w
    }

    private fun step4(w: String, r2: Int): String {
        for (suf in step4Suffixes) {
            if (w.endsWith(suf) && w.length - suf.length >= r2) {
                if (suf == "ion") {
                    val stemPart = w.dropLast(3)
                    if (stemPart.isNotEmpty() && (stemPart.last() == 's' || stemPart.last() == 't')) {
                        return stemPart
                    }
                    continue
                }
                return w.dropLast(suf.length)
            }
        }
        return w
    }

    private fun step5(w: String, r1: Int, r2: Int): String {
        if (w.endsWith("e")) {
            val stemPart = w.dropLast(1)
            if (w.length - 1 >= r2 || (w.length - 1 >= r1 && !endsInShortSyllable(stemPart))) {
                return stemPart
            }
        }
        if (w.endsWith("ll") && w.length - 1 >= r2) {
            return w.dropLast(1)
        }
        return w
    }
}
