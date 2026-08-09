package com.grocerylistbuilder.core.ingest.web

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import org.jsoup.nodes.Entities

/**
 * Normalize irregular JSON-LD recipe fields into clean values (mirrors the field-normalization
 * helpers in grocery/ingest/web.py: `_clean_lines`, `_as_text`, `_parse_servings`).
 */
object RecipeFields {

    /**
     * `recipeIngredient` -> a list of non-empty, entity-unescaped, trimmed strings.
     *
     * Sites HTML-encode inside JSON-LD (WordPress/Yoast are common offenders), so a line can
     * arrive as "salt &amp; pepper" or "&#189; cup" — unescape turns those back into "&" and "½"
     * before anything downstream sees them.
     */
    fun cleanLines(value: JsonElement?): List<String> {
        val rawStrings: List<String> = when (value) {
            is JsonArray -> value.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
            is JsonPrimitive -> listOfNotNull(value.contentOrNull)
            else -> emptyList()
        }
        return rawStrings.map { Entities.unescape(it).trim() }.filter { it.isNotEmpty() }
    }

    /** Coerce a JSON-LD field (e.g. `name`) to a trimmed, entity-unescaped string. */
    fun asText(value: JsonElement?): String {
        val content = (value as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return ""
        return Entities.unescape(content).trim()
    }

    /** `recipeYield` is a mess across sites ("4", "4 servings", ["6"], 4) -> an Int, or null. */
    fun parseServings(value: JsonElement?): Int? {
        val element = if (value is JsonArray) value.firstOrNull() else value
        val primitive = element as? JsonPrimitive ?: return null
        if (primitive.booleanOrNull != null) return null // guard: JSON true/false isn't a count
        primitive.intOrNull?.let { return it }
        primitive.doubleOrNull?.let { return it.toInt() }
        return Regex("""\d+""").find(primitive.content)?.value?.toIntOrNull()
    }
}
