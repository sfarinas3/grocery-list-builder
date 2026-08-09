package com.grocerylistbuilder.core.ingest.web

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.jsoup.Jsoup

/**
 * Pull the first schema.org/Recipe node out of a page's embedded JSON-LD (mirrors
 * grocery/ingest/web.py `_extract_jsonld_recipe` / `_find_recipe`).
 *
 * Uses [JsonElement]'s dynamic tree API rather than deserializing into a fixed data class:
 * JSON-LD recipe nodes are irregular by design (a bare object, a list of objects, or an object
 * wrapping the list in "@graph"; fields like `recipeYield` show up as a string, a number, or a
 * list) — exactly why Python parses with plain `json.loads` + `isinstance` checks instead of
 * validating through pydantic here too.
 */
object JsonLdParser {

    private val json = Json { ignoreUnknownKeys = true }

    /** Parse every `<script type="application/ld+json">` block and return the first Recipe node. */
    fun findRecipeInHtml(html: String): JsonObject? {
        val document = Jsoup.parse(html)
        for (script in document.select("script[type=application/ld+json]")) {
            val element = try {
                json.parseToJsonElement(script.data())
            } catch (_: SerializationException) {
                continue // some sites ship malformed JSON-LD; just skip it
            }
            findRecipe(element)?.let { return it }
        }
        return null
    }

    /**
     * Recursively search a parsed JSON-LD value for a node typed "Recipe". Handles the three
     * shapes sites use: a bare object, a list of objects, or an object wrapping the list in
     * "@graph".
     */
    private fun findRecipe(node: JsonElement): JsonObject? = when (node) {
        is JsonArray -> node.firstNotNullOfOrNull { findRecipe(it) }
        is JsonObject -> {
            val types = when (val typeField = node["@type"]) {
                is JsonArray -> typeField.mapNotNull { (it as? JsonPrimitive)?.content }
                is JsonPrimitive -> listOf(typeField.content)
                else -> emptyList()
            }
            when {
                types.any { it.equals("Recipe", ignoreCase = true) } -> node
                node.containsKey("@graph") -> node["@graph"]?.let { findRecipe(it) }
                else -> null
            }
        }
        else -> null
    }
}
