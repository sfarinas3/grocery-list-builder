package com.grocerylistbuilder.core.extract.crf

/**
 * Rejects lines/names that look like device or app UI chrome rather than an ingredient — e.g.
 * "Partly Sunny" (a phone status-bar weather widget) or "Q Search" (a search box), both of which
 * a photo of a phone/website can capture alongside the actual recipe. [IngredientLineFinder]'s
 * heading-scoped extraction deliberately applies no quantity/unit filtering (real ingredient
 * lines like "salt to taste" have neither), which means unrelated UI text sitting between the
 * detected "Ingredients:" heading and the next section heading gets swept in unfiltered unless
 * something else screens it out — this is that screen.
 *
 * A curated word/phrase list, not a general classifier — it catches the concrete failure modes
 * observed in practice (status-bar chrome, recipe-page metadata), not everything imaginable.
 * OCR garbage *fused onto* a real ingredient (e.g. a misread bullet icon producing "P1tbsp
 * Sugar") isn't something a word-level filter like this can cleanly separate; those still need a
 * manual edit/delete in the list.
 */
object GroceryNoiseFilter {
    // Phone/OS UI chrome a photo of a screen can capture alongside the recipe -- never
    // legitimate grocery text.
    private val uiChromeWords = setOf(
        "search", "menu", "settings", "notification", "notifications", "wifi", "wi-fi",
        "bluetooth", "battery", "signal", "airplane", "hotspot", "brightness", "volume",
        "back", "home", "share", "bookmark", "widget", "app", "apps",
    )
    private val uiChromePhrases = setOf(
        "log in", "sign in", "sign up", "log out",
    )

    // Weather-widget conditions -- only ever noise here, never an ingredient.
    private val weatherWords = setOf(
        "sunny", "cloudy", "rainy", "snowy", "windy", "stormy", "foggy", "overcast",
        "humidity", "forecast", "precipitation", "partly", "mostly",
    )

    // Recipe-page metadata that often sits right next to (or gets OCR'd into the middle of) an
    // ingredients section without itself being an ingredient.
    private val recipeMetadataWords = setOf(
        "serves", "servings", "yield", "yields", "calories", "nutrition", "nutritional",
        "difficulty", "rating", "ratings", "review", "reviews", "advertisement",
    )
    private val recipeMetadataPhrases = setOf(
        "prep time", "cook time", "total time", "ready in", "print recipe", "save recipe",
        "jump to recipe",
    )

    // Cooking temperatures ("82°C", "350 degrees F", "internal temperature of 165°F") describe
    // *how* to cook something, not what to buy -- never a grocery item, even though a bare
    // fragment like "82 degrees Celsius" can end up as its own OCR'd line right next to real
    // ingredients (no leading instruction verb like "cook" or "grill" for the verb-stoplist in
    // IngredientLineFinder to catch). Requires "degrees"/"°" explicitly, so this can't misfire on
    // a quantity+unit like "2 c flour" or "6 oz".
    private val temperaturePattern = Regex(
        """\d+\s*°\s*[cf]\b|\d+\s*degrees?\s*(celsius|fahrenheit|[cf])?\b""",
        RegexOption.IGNORE_CASE,
    )
    private val temperaturePhrases = setOf("internal temp", "internal temperature", "oven temperature")

    private val noiseWords = uiChromeWords + weatherWords + recipeMetadataWords
    private val noisePhrases = uiChromePhrases + recipeMetadataPhrases + temperaturePhrases

    /** True if [text] (a candidate line, or a parsed ingredient name) looks like UI chrome,
     * recipe metadata, or a cooking temperature rather than an ingredient. */
    fun isLikelyNoise(text: String): Boolean {
        val normalized = text.lowercase().trim()
        if (normalized.isEmpty()) return false

        if (noisePhrases.any { normalized.contains(it) }) return true
        if (temperaturePattern.containsMatchIn(normalized)) return true

        val words = normalized.split(Regex("""\s+""")).map { it.trim(',', '.', ':', ';', '!', '?') }
        return words.any { it in noiseWords }
    }
}
