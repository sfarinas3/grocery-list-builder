package com.grocerylistbuilder.core.extract.crf

/**
 * Static data tables ported verbatim from `ingredient_parser_nlp`'s `en/_constants.py`.
 * Kept as a direct transcription (not regenerated at build time) since these rarely change
 * and a one-time hand-port is easier to diff against upstream than a codegen step.
 */
object Constants {
    /** Plural -> singular unit map (length units excluded), mirrors Python's `UNITS`. */
    val UNITS: Map<String, String> = run {
        val base = mapOf(
            "balls" to "ball", "bags" to "bag", "bars" to "bar", "baskets" to "basket",
            "batches" to "batch", "blocks" to "block", "bottles" to "bottle", "boxes" to "box",
            "branches" to "branch", "buckets" to "bucket", "bulbs" to "bulb", "bunches" to "bunch",
            "bundles" to "bundle", "c" to "c", "cans" to "can", "canisters" to "canister",
            "chunks" to "chunk", "cloves" to "clove", "clusters" to "cluster", "counts" to "count",
            "cl" to "cl", "cL" to "cL", "cubes" to "cube", "cups" to "cup", "cutlets" to "cutlet",
            "dashes" to "dash", "dessertspoons" to "dessertspoon", "dollops" to "dollop",
            "drops" to "drop", "ears" to "ear", "envelopes" to "envelope", "feet" to "foot",
            "fl" to "fl", "floz" to "floz", "g" to "g", "gm" to "gm", "gal" to "gal",
            "gallons" to "gallon", "glasses" to "glass", "grams" to "gram", "grinds" to "grind",
            "handfuls" to "handful", "heads" to "head", "jars" to "jar", "jiggers" to "jigger",
            "kg" to "kg", "kilograms" to "kilogram", "knobs" to "knob", "ladles" to "ladle",
            "lbs" to "lb", "leaves" to "leaf", "lengths" to "length", "links" to "link", "l" to "l",
            "liters" to "liter", "litres" to "litre", "loaves" to "loaf",
            "milliliters" to "milliliter", "millilitres" to "millilitre", "ml" to "ml",
            "mL" to "mL", "mugs" to "mug", "ounces" to "ounce", "oz" to "oz", "packs" to "pack",
            "packages" to "package", "packets" to "packet", "pairs" to "pair", "pieces" to "piece",
            "pinches" to "pinch", "pints" to "pint", "pods" to "pod", "pots" to "pot",
            "pounds" to "pound", "pts" to "pt", "punnets" to "punnet", "racks" to "rack",
            "rashers" to "rasher", "recipes" to "recipe", "rectangles" to "rectangle",
            "ribs" to "rib", "quarts" to "quart", "qt" to "qt", "sachets" to "sachet",
            "scoops" to "scoop", "sections" to "section", "segments" to "segment",
            "shakes" to "shake", "sheets" to "sheet", "shots" to "shot", "shoots" to "shoot",
            "slabs" to "slab", "slices" to "slice", "sprigs" to "sprig", "squares" to "square",
            "stalks" to "stalk", "stems" to "stem", "sticks" to "stick", "strips" to "strip",
            "tablespoons" to "tablespoon", "tbsps" to "tbsp", "tbs" to "tb",
            "teaspoons" to "teaspoon", "tins" to "tin", "tsps" to "tsp", "tubs" to "tub",
            "tubes" to "tube", "twists" to "twist", "units" to "unit", "wedges" to "wedge",
            "vials" to "vial", "wheels" to "wheel",
        )
        val capitalized = base.entries.associate { (plural, singular) ->
            plural.replaceFirstChar { it.uppercase() } to singular.replaceFirstChar { it.uppercase() }
        }
        base + capitalized
    }

    val FLATTENED_UNITS_LIST: Set<String> = UNITS.entries.flatMap { (k, v) -> listOf(k, v) }.toSet()

    val AMBIGUOUS_UNITS: Set<String> = run {
        val base = listOf("cloves", "leaves", "slabs", "wedges", "ribs", "gram", "glass")
        val extra = base.flatMap { unit ->
            buildList {
                add(unit.replaceFirstChar { it.uppercase() })
                UNITS[unit]?.let { add(it) }
                UNITS[unit.replaceFirstChar { it.uppercase() }]?.let { add(it) }
            }
        }
        (base + extra).toSet()
    }

    val SIZES: List<String> = listOf(
        "big", "bite-size", "bite-sized", "extra-large", "jumbo", "large", "lg", "little", "md",
        "medium", "medium-large", "medium-size", "medium-sized", "medium-small",
        "medium-to-large", "miniature", "regular", "slim", "sm", "small", "small-to-medium",
        "smaller", "smallest", "thick", "thin", "tiny",
    )

    val STRING_NUMBERS: Map<String, String> = mapOf(
        "one-quarter" to "1/4", "one-half" to "1/2", "three-quarter" to "3/4",
        "three-quarters" to "3/4", "one" to "1", "two" to "2", "three" to "3", "four" to "4",
        "five" to "5", "six" to "6", "seven" to "7", "eight" to "8", "nine" to "9", "ten" to "10",
        "eleven" to "11", "twelve" to "12", "thirteen" to "13", "fourteen" to "14",
        "fifteen" to "15", "sixteen" to "16", "seventeen" to "17", "eighteen" to "18",
        "nineteen" to "19",
    )

    /** (case-insensitive whole-word regex, replacement) pairs, mirrors `STRING_NUMBERS_REGEXES`. */
    val STRING_NUMBERS_REGEXES: List<Pair<Regex, String>> =
        STRING_NUMBERS.map { (s, n) -> Regex("\\b($s)\\b", RegexOption.IGNORE_CASE) to n }

    /** Unicode fraction -> replacement text. Order matters: hyphen-prefixed entries must be
     * checked before their non-hyphen counterparts (mirrors Python dict iteration order). */
    val UNICODE_FRACTIONS: List<Pair<String, String>> = listOf(
        "-⅛" to "-1/8", "-⅜" to "-3/8", "-⅝" to "-5/8", "-⅞" to "-7/8",
        "-⅙" to "-1/6", "-⅚" to "-5/6", "-⅕" to "-1/5", "-⅖" to "-2/5",
        "-⅗" to "-3/5", "-⅘" to "-4/5", "-¼" to "-1/4", "-¾" to "-3/4",
        "-⅓" to "-1/3", "-⅔" to "-2/3", "-½" to "-1/2",
        "⅛" to " 1/8", "⅜" to " 3/8", "⅝" to " 5/8", "⅞" to " 7/8",
        "⅙" to " 1/6", "⅚" to " 5/6", "⅕" to " 1/5", "⅖" to " 2/5",
        "⅗" to " 3/5", "⅘" to " 4/5", "¼" to " 1/4", "¾" to " 3/4",
        "⅓" to " 1/3", "⅔" to " 2/3", "½" to " 1/2",
    )

    /** NLTK stopwords, trimmed to what the tokenizer can actually produce — see Python's comment. */
    val STOP_WORDS: Set<String> = setOf(
        "i", "me", "my", "myself", "we", "our", "ours", "ourselves", "you", "you're", "you've",
        "you'll", "you'd", "your", "yours", "yourself", "yourselves", "he", "him", "his",
        "himself", "she", "she's", "her", "hers", "herself", "it", "it's", "its", "itself",
        "they", "them", "their", "theirs", "themselves", "what", "which", "who", "whom", "this",
        "that", "that'll", "these", "those", "am", "is", "are", "was", "were", "be", "been",
        "being", "have", "has", "had", "having", "do", "does", "did", "doing", "a", "an", "the",
        "and", "but", "if", "or", "because", "as", "until", "while", "of", "at", "by", "for",
        "with", "about", "against", "between", "into", "through", "during", "before", "after",
        "above", "below", "to", "from", "up", "down", "in", "out", "on", "off", "over", "under",
        "again", "further", "then", "once", "here", "there", "when", "where", "why", "how", "all",
        "any", "each", "few", "more", "most", "other", "some", "such", "no", "nor", "not", "only",
        "own", "same", "so", "than", "too", "very", "can", "will", "just", "don't", "should",
        "should've", "now", "aren't", "couldn't", "didn't", "doesn't", "hadn't", "hasn't",
        "haven't", "isn't", "mightn't", "mustn't", "needn't", "shan't", "shouldn't", "wasn't",
        "weren't", "won't", "wouldn't",
    )

    val APPROXIMATE_PREFIXES: Set<String> =
        setOf("about", "approx", "approximately", "nearly", "roughly", "~", "generous")

    val APPROXIMATE_SUFFIXES: List<List<String>> = listOf(listOf("or", "so"))

    val SINGULAR_TOKENS: Set<String> = setOf("each")

    val PREPARED_INGREDIENT_TOKENS: List<List<String>> = listOf(listOf("to", "yield"), listOf("to", "make"))

    val UNIT_SYNONYMS: List<Set<String>> = listOf(
        setOf("cup", "c"), setOf("gram", "g", "gm"), setOf("kilogram", "kg"),
        setOf("litre", "liter", "l"), setOf("ounce", "oz"), setOf("pound", "lb"),
        setOf("quart", "qt"), setOf("tablespoon", "tbsp", "tbs", "tb"), setOf("teaspoon", "tsp"),
    )

    val LENGTH_UNITS: Set<String> = setOf(
        "centimeter", "centimetre", "cm", "in", "inch", "inches", "millimeter", "millimetre", "mm",
    )

    val DIMENSIONS: Set<String> = setOf(
        "diameter", "inch-long", "inch-thick", "length", "long", "thick", "thickness", "wide", "width",
    )

    val INDEFINITE_QUANTIFIERS: Set<String> =
        setOf("couple", "few", "many", "plenty", "more", "several", "some")
}
