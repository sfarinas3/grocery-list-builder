package com.grocerylistbuilder.core.extract.crf

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test

/** Differential test of Viterbi decoding + marginal confidence against the real Python
 * `NumpyCRFInference.tag_from_features` (loaded from the same model file), fixtures from `.venv`. */
class CrfModelTest {

    private val fixtures = Json.parseToJsonElement(
        CrfModelTest::class.java.getResourceAsStream("/crf_fixtures/crf_tagging.json")!!
            .bufferedReader().readText(),
    ).jsonObject

    @Test
    fun `tag_from_features matches Python for real ingredient lines`() {
        for ((line, case) in fixtures) {
            val obj = case.jsonObject
            val expectedTokens = obj["tokens"]!!.jsonArray.map { it.jsonPrimitive.content }
            val expectedLabels = obj["labels"]!!.jsonArray.map { it.jsonPrimitive.content }
            val expectedConfidences = obj["confidences"]!!.jsonArray.map { it.jsonPrimitive.content.toDouble() }

            val tokens = Preprocessor.calculateTokens(Preprocessor.normalise(line))
            assertEquals("tokens for \"$line\"", expectedTokens, tokens.map { it.text })

            val features = FeatureExtractor(tokens).sentenceFeatures()
            val tagged = CrfModel.tagFromFeatures(features).labels

            assertEquals("labels for \"$line\"", expectedLabels, tagged.map { it.label })
            for (i in expectedConfidences.indices) {
                assertEquals(
                    "confidence[$i] for \"$line\" (token \"${expectedTokens[i]}\", label ${expectedLabels[i]})",
                    expectedConfidences[i],
                    tagged[i].confidence,
                    1e-4,
                )
            }
        }
    }
}
