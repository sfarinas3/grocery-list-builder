package com.grocerylistbuilder.core.extract.crf

/** Ports `tokenize()`/`combine_and_or()` from `ingredient_parser_nlp`'s `en/_utils.py`. */
object Tokenizer {
    private val andOrPattern = listOf("and", "/", "or")

    fun tokenize(sentence: String): List<String> {
        val flattened = Patterns.WHITESPACE_TOKENISER.findAll(sentence)
            .flatMap { splitKeepDelimiters(it.value, Patterns.PUNCTUATION_TOKENISER) }
            .filter { it.isNotEmpty() }
            .toList()

        val combined = combineAndOr(flattened)

        return combined
            .flatMap { splitKeepDelimiters(it, Patterns.FULL_STOP_TOKENISER) }
            .filter { it.isNotEmpty() }
    }

    /** Mirrors Python's `re.split` with a capturing group: matched delimiters are kept as
     * their own elements, interleaved with the text between them. */
    private fun splitKeepDelimiters(text: String, pattern: Regex): List<String> {
        val result = mutableListOf<String>()
        var last = 0
        for (m in pattern.findAll(text)) {
            result.add(text.substring(last, m.range.first))
            result.add(m.groupValues[1])
            last = m.range.last + 1
        }
        result.add(text.substring(last))
        return result
    }

    private fun combineAndOr(tokens: List<String>): List<String> {
        val combined = mutableListOf<String>()
        var i = 0
        while (i < tokens.size) {
            if (tokens[i] == andOrPattern[0] && tokens.subList(i, minOf(i + 3, tokens.size)) == andOrPattern) {
                combined.add("and/or")
                i += 3
            } else {
                combined.add(tokens[i])
                i += 1
            }
        }
        return combined
    }
}
