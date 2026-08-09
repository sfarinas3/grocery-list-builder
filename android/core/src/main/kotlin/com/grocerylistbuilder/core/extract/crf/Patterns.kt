package com.grocerylistbuilder.core.extract.crf

/**
 * Regex patterns ported verbatim from `ingredient_parser_nlp`'s `en/_regex.py` and (tokenizer
 * ones) `en/_utils.py`. Python's `re.VERBOSE` patterns are transcribed as their equivalent
 * compact form since `kotlin.text.Regex`/`java.util.regex` has no verbose mode.
 */
object Patterns {
    private val unitsList: Set<String> = Constants.FLATTENED_UNITS_LIST + setOf("x") + Constants.LENGTH_UNITS
    private val unitsAlternation: String = unitsList.joinToString("|") { Regex.escape(it) }
    private val stringNumbersAlternation: String = Constants.STRING_NUMBERS.keys.joinToString("|") { Regex.escape(it) }

    val FRACTION_PARTS_PATTERN = Regex("""(\d*\s*\d/\d+)""")
    val CAPITALISED_PATTERN = Regex("""^[A-Z]""")

    val QUANTITY_UNITS_PATTERN = Regex("""(\d)-?($unitsAlternation)(?![a-wyzA-WYZ])""")
    val UNITS_QUANTITY_PATTERN = Regex("""($unitsAlternation)(\d)""")
    val UNITS_HYPHEN_QUANTITY_PATTERN = Regex("""($unitsAlternation)-(\d)""")
    val STRING_QUANTITY_HYPHEN_PATTERN =
        Regex("""\b($stringNumbersAlternation)\b-\b($unitsAlternation)\b""", RegexOption.IGNORE_CASE)

    val STRING_RANGE_PATTERN = Regex(
        """(0\.[0-9]|[1-9][\d.]*?|\d*#\d+\$\d+)\s*(-)?\s*(to|or)\s*(-)*\s*((0\.[0-9]+|[1-9][\d.]*?|\d*#\d+\$\d+)(-)?)""",
    )

    val FRACTION_SPLIT_AND_PATTERN = Regex("""((\d+)\sand\s(\d/\d+))""")

    val DUPE_UNIT_RANGES_PATTERN = Regex(
        """(([\d.]+|\d*#\d+\$\d+)\s([a-zA-Z]+)\s*(?:-|to|or)\s*([\d.]+|\d*#\d+\$\d+)\s([a-zA-Z]+))""",
        RegexOption.IGNORE_CASE,
    )

    val QUANTITY_X_PATTERN = Regex("""([\d.]+|\d*#\d+\$\d+)\s[xX]\s*""")

    val EXPANDED_RANGE = Regex("""(\d)\s*-\s*([\d#])""")

    val LOWERCASE_PATTERN = Regex("""[a-z]""")
    val UPPERCASE_PATTERN = Regex("""[A-Z]""")
    val DIGIT_PATTERN = Regex("""[0-9]""")

    val FRACTION_TOKEN_PATTERN = Regex("""^\d*#\d+\$\d+(?:-\d*#\d+\$\d+)?$""")

    /** From `_common.py`. Matches a numeric range e.g. 1-2, #1$2-1#3$4. */
    val RANGE_PATTERN = Regex("""^[\d#$]+\s*-[\d#$]+$""")

    private val currencyAlternation = listOf("$", "£", "€", "¥", "₹").joinToString("|") { Regex.escape(it) }
    val CURRENCY_PATTERN = Regex("""\(\s*(?:$currencyAlternation)\s*[0-9.,]+\**\s*\)""")

    // --- Tokenizer patterns (en/_utils.py) ---
    val WHITESPACE_TOKENISER = Regex("""\S+""")
    val PUNCTUATION_TOKENISER = Regex("""([()\[\]{},/:;?!*~])""")
    val FULL_STOP_TOKENISER = Regex("""(?<!\.\w)(\.)$""")
}
