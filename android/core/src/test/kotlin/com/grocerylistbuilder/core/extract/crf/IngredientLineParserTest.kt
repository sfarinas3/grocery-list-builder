package com.grocerylistbuilder.core.extract.crf

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Differential test of the full pipeline against the real `grocery/extract/parse.py:parse_line`
 * (which itself wraps the real `ingredient_parser` CRF library), fixtures generated from `.venv`.
 * This is the main correctness gate for the whole CRF port. */
class IngredientLineParserTest {

    private val fixtures = Json.parseToJsonElement(
        IngredientLineParserTest::class.java.getResourceAsStream("/crf_fixtures/parse_line.json")!!
            .bufferedReader().readText(),
    ).jsonObject

    @Test
    fun `parseIngredientLine matches Python parse_line for real ingredient lines`() {
        for ((line, case) in fixtures) {
            val obj = case.jsonObject
            val expectedName = obj["name"]!!.jsonPrimitive.content
            val expectedQuantity = if (obj["quantity"] == JsonNull) null else obj["quantity"]!!.jsonPrimitive.content.toDouble()
            val expectedUnit = if (obj["unit"] == JsonNull) null else obj["unit"]!!.jsonPrimitive.content
            val expectedNotes = obj["notes"]!!.jsonPrimitive.content
            val expectedConfidence = obj["confidence"]!!.jsonPrimitive.content.toDouble()
            val expectedFlags = obj["flags"]!!.jsonArray.map { it.jsonPrimitive.content }

            val actual = IngredientLineParser.parseIngredientLine(line)

            assertEquals("name for \"$line\"", expectedName, actual.name)
            if (expectedQuantity == null) {
                assertNull("quantity for \"$line\" should be null", actual.quantity)
            } else {
                assertEquals("quantity for \"$line\"", expectedQuantity, actual.quantity!!, 1e-6)
            }
            assertEquals("unit for \"$line\"", expectedUnit, actual.unit)
            assertEquals("notes for \"$line\"", expectedNotes, actual.notes)
            assertEquals("confidence for \"$line\"", expectedConfidence, actual.confidence, 1e-4)
            assertEquals("flags for \"$line\"", expectedFlags, actual.flags)
        }
    }

    @Test
    fun `composite-first-amount lines degrade gracefully instead of crashing`() {
        // Python's parse_line() crashes on these (confirmed via .venv -- AttributeError:
        // 'CompositeIngredientAmount' object has no attribute 'quantity'); this port must not.
        for (line in listOf("1 lb 2 oz beef mince", "2 cups plus 1 tablespoon sugar")) {
            val result = IngredientLineParser.parseIngredientLine(line)
            assertTrue("should still produce a name for \"$line\"", result.name.isNotEmpty())
        }
    }
}
