package com.grocerylistbuilder.core.extract.crf

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test

/** Differential test of the full normalize -> tokenize -> POS-tag -> feature-extraction pipeline
 * against the real Python `PreProcessor.sentence_features()` (run through the same
 * `_convert_features` set-conversion the CRF model itself uses), fixtures generated from `.venv`. */
class FeatureExtractorTest {

    private val fixtures = Json.parseToJsonElement(
        FeatureExtractorTest::class.java.getResourceAsStream("/crf_fixtures/feature_extraction.json")!!
            .bufferedReader().readText(),
    ).jsonObject

    @Test
    fun `sentence_features matches Python for real ingredient lines`() {
        for ((line, case) in fixtures) {
            val obj = case.jsonObject
            val expectedTokens = obj["tokens"]!!.jsonArray.map { it.jsonPrimitive.content }
            val expectedFeatures = obj["features"]!!.jsonArray.map { tokenFeatures ->
                tokenFeatures.jsonArray.map { it.jsonPrimitive.content }.toSet()
            }

            val tokens = Preprocessor.calculateTokens(Preprocessor.normalise(line))
            assertEquals("tokens for \"$line\"", expectedTokens, tokens.map { it.text })

            val actualFeatures = FeatureExtractor(tokens).sentenceFeatures().map { convertFeatures(it) }
            for (i in expectedFeatures.indices) {
                assertEquals("features[$i] for \"$line\" (token \"${expectedTokens[i]}\")", expectedFeatures[i], actualFeatures[i])
            }
        }
    }
}
