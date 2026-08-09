package com.grocerylistbuilder.core.extract.crf

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test

/** Differential test against the real NLTK-backed `pos_tag()` (including the ingredient-specific
 * tagdict overrides), fixtures generated from the real `.venv`. */
class PosTaggerTest {

    private val fixtures = Json.parseToJsonElement(
        PosTaggerTest::class.java.getResourceAsStream("/crf_fixtures/pos_tagger.json")!!
            .bufferedReader().readText(),
    ).jsonObject

    @Test
    fun `tag matches Python pos_tag for real ingredient lines`() {
        for ((line, case) in fixtures) {
            val obj = case.jsonObject
            val tokens = obj["tokens"]!!.jsonArray.map { it.jsonPrimitive.content }
            val expectedTags = obj["tags"]!!.jsonArray.map { it.jsonPrimitive.content }
            val actualTags = PosTagger.tag(tokens).map { it.second }
            assertEquals("pos_tag(\"$line\")", expectedTags, actualTags)
        }
    }
}
