package com.grocerylistbuilder.core.extract.crf

import kotlinx.serialization.json.jsonObject

/** A labelled token: the CRF's chosen label for one token, plus its marginal-probability
 * confidence (mirrors `tag_from_features`'s `list[tuple[str, float]]` return). */
data class CrfLabel(val label: String, val confidence: Double)

/** [labels] in sentence order, plus the full marginals matrix (`[position][labelIdx]`) they were
 * derived from — needed by [guessIngredientName]'s fallback, which queries marginals for labels
 * Viterbi didn't actually choose. */
data class TaggingResult(val labels: List<CrfLabel>, val marginals: Array<DoubleArray>)

/**
 * Ports `inference.py`'s `NumpyCRFInference`/`NumpyViterbiInference`: Viterbi decoding with
 * BIO transition constraints (I_NAME_TOK requires a preceding B_NAME_TOK since the start of the
 * sentence or the last NAME_SEP), plus log-space forward-backward marginals for per-token
 * confidence. Loads `model.en.json.gz` (feature/label indices, quantized emission and transition
 * weights) from resources — see `scripts/generate_android_crf_resources.py`.
 *
 * Weights are stored quantized (as whole numbers); Viterbi path-scoring sums the RAW quantized
 * weights directly (matching the Python implementation, which only ever de-quantizes the
 * transition weights and per-token state scores for the separate marginals computation — the
 * decoded label sequence itself is unaffected by the quantization's affine scale/offset since
 * argmax comparisons are scale/offset-invariant only when comparing sums with equal term counts,
 * which the Python code doesn't rely on; it simply never de-quantizes for the decode path at all).
 */
object CrfModel {
    private val labelToIdx: Map<String, Int>
    private val idxToLabel: List<String>
    private val featureToIdx: Map<String, Int>
    private val emissionWeights: Array<DoubleArray> // [featureIdx][labelIdx], raw quantized
    private val transitionWeights: Array<DoubleArray> // [prevLabelIdx][labelIdx], raw quantized
    private val dqTransitionWeights: Array<DoubleArray> // [prevLabelIdx][labelIdx], de-quantized
    private val scale: Double
    private val zeroOffset: Double

    private val bNameIdx: Int?
    private val iNameIdx: Int?
    private val nameSepIdx: Int?

    init {
        val data = ResourceData.readJsonObject("model.en.json.gz")
        featureToIdx = ResourceData.intMap(data["attributes"]!!.jsonObject)
        labelToIdx = ResourceData.intMap(data["labels"]!!.jsonObject)
        idxToLabel = labelToIdx.entries.sortedBy { it.value }.map { it.key }
        scale = ResourceData.double(data["quantization_scale"]!!)
        zeroOffset = ResourceData.double(data["quantization_zero_offset"]!!)

        val nLabels = idxToLabel.size
        val nFeatures = featureToIdx.size
        emissionWeights = Array(nFeatures) { DoubleArray(nLabels) }
        for ((key, weight) in ResourceData.flatDoubleMap(data["state_features"]!!.jsonObject)) {
            val (feature, label) = key.split("|", limit = 2)
            emissionWeights[featureToIdx.getValue(feature)][labelToIdx.getValue(label)] = weight
        }

        transitionWeights = Array(nLabels) { DoubleArray(nLabels) }
        for ((key, weight) in ResourceData.flatDoubleMap(data["transitions"]!!.jsonObject)) {
            val (prevLabel, label) = key.split("|", limit = 2)
            transitionWeights[labelToIdx.getValue(prevLabel)][labelToIdx.getValue(label)] = weight
        }
        dqTransitionWeights = Array(nLabels) { p -> DoubleArray(nLabels) { c -> (transitionWeights[p][c] - zeroOffset) / scale } }

        bNameIdx = labelToIdx["B_NAME_TOK"]
        iNameIdx = labelToIdx["I_NAME_TOK"]
        nameSepIdx = labelToIdx["NAME_SEP"]
    }

    fun labelIndex(label: String): Int = labelToIdx.getValue(label)

    /** Tags a sentence's per-token feature dicts, mirroring `NumpyCRFInference.tag_from_features`
     * (always called here with the equivalent of `combined_name_labels=False`, i.e. transition
     * constraints always enforced — this port never needs the unconstrained mode). Also returns
     * the marginals matrix used to compute confidence, since [guessIngredientName]'s fallback
     * needs to query marginals for labels *other* than the ones Viterbi actually chose — mirrors
     * Python's `TAGGER.marginal()`, which reads off the same stateful matrix `tag_from_features`
     * just computed. */
    fun tagFromFeatures(sentenceFeatures: List<FeatureDict>): TaggingResult {
        val featureSets = sentenceFeatures.map { convertFeatures(it) }
        return predictSequence(featureSets)
    }

    fun marginal(marginals: Array<DoubleArray>, label: String, position: Int): Double =
        marginals[position][labelToIdx.getValue(label)]

    private fun predictSequence(featuresSeq: List<Set<String>>): TaggingResult {
        val seqLen = featuresSeq.size
        val nLabels = idxToLabel.size

        val stateScores = Array(seqLen) { t ->
            DoubleArray(nLabels).also { row ->
                for (feat in featuresSeq[t]) {
                    val fIdx = featureToIdx[feat] ?: continue
                    val weights = emissionWeights[fIdx]
                    for (l in 0 until nLabels) row[l] += weights[l]
                }
            }
        }

        val hasBName = Array(seqLen) { BooleanArray(nLabels) }
        val latticeScores = Array(seqLen) { DoubleArray(nLabels) { Double.NEGATIVE_INFINITY } }
        val backpointers = Array(seqLen) { IntArray(nLabels) }

        stateScores[0].copyInto(latticeScores[0])
        if (iNameIdx != null) latticeScores[0][iNameIdx] = Double.NEGATIVE_INFINITY
        if (bNameIdx != null) hasBName[0][bNameIdx] = true

        for (t in 1 until seqLen) {
            for (cur in 0 until nLabels) {
                var best = Double.NEGATIVE_INFINITY
                var bestPrev = 0
                for (prev in 0 until nLabels) {
                    var score = latticeScores[t - 1][prev] + transitionWeights[prev][cur] + stateScores[t][cur]
                    if (bNameIdx != null && cur == iNameIdx && !hasBName[t - 1][prev]) {
                        score = Double.NEGATIVE_INFINITY
                    }
                    if (score > best) {
                        best = score
                        bestPrev = prev
                    }
                }
                latticeScores[t][cur] = best
                backpointers[t][cur] = bestPrev
            }
            if (bNameIdx != null) {
                for (cur in 0 until nLabels) hasBName[t][cur] = hasBName[t - 1][backpointers[t][cur]]
                hasBName[t][bNameIdx] = true
                if (nameSepIdx != null) hasBName[t][nameSepIdx] = false
            }
        }

        val labelIndices = IntArray(seqLen)
        labelIndices[seqLen - 1] = argmax(latticeScores[seqLen - 1])
        for (t in seqLen - 2 downTo 0) labelIndices[t] = backpointers[t + 1][labelIndices[t + 1]]

        val marginals = computeMarginals(seqLen, stateScores)
        val labelled = labelIndices.mapIndexed { t, idx -> CrfLabel(idxToLabel[idx], marginals[t][idx]) }
        return TaggingResult(labelled, marginals)
    }

    private fun computeMarginals(seqLen: Int, rawStateScores: Array<DoubleArray>): Array<DoubleArray> {
        val nLabels = idxToLabel.size
        val stateScores = Array(seqLen) { t -> DoubleArray(nLabels) { l -> (rawStateScores[t][l] - zeroOffset) / scale } }

        val logAlpha = Array(seqLen) { DoubleArray(nLabels) { Double.NEGATIVE_INFINITY } }
        val logBeta = Array(seqLen) { DoubleArray(nLabels) { Double.NEGATIVE_INFINITY } }

        stateScores[0].copyInto(logAlpha[0])
        for (t in 1 until seqLen) {
            for (cur in 0 until nLabels) {
                var maxVal = Double.NEGATIVE_INFINITY
                for (prev in 0 until nLabels) {
                    val v = logAlpha[t - 1][prev] + dqTransitionWeights[prev][cur]
                    if (v > maxVal) maxVal = v
                }
                var sumExp = 0.0
                if (maxVal.isFinite()) {
                    for (prev in 0 until nLabels) sumExp += Math.exp(logAlpha[t - 1][prev] + dqTransitionWeights[prev][cur] - maxVal)
                }
                logAlpha[t][cur] = (if (maxVal.isFinite()) maxVal + Math.log(sumExp) else Double.NEGATIVE_INFINITY) + stateScores[t][cur]
            }
        }

        logBeta[seqLen - 1] = DoubleArray(nLabels) { 0.0 }
        for (t in seqLen - 2 downTo 0) {
            for (prev in 0 until nLabels) {
                var maxVal = Double.NEGATIVE_INFINITY
                for (cur in 0 until nLabels) {
                    val v = dqTransitionWeights[prev][cur] + stateScores[t + 1][cur] + logBeta[t + 1][cur]
                    if (v > maxVal) maxVal = v
                }
                var sumExp = 0.0
                if (maxVal.isFinite()) {
                    for (cur in 0 until nLabels) sumExp += Math.exp(dqTransitionWeights[prev][cur] + stateScores[t + 1][cur] + logBeta[t + 1][cur] - maxVal)
                }
                logBeta[t][prev] = if (maxVal.isFinite()) maxVal + Math.log(sumExp) else Double.NEGATIVE_INFINITY
            }
        }

        val logZ = logSumExp(logAlpha[seqLen - 1])
        return Array(seqLen) { t -> DoubleArray(nLabels) { l -> Math.exp(logAlpha[t][l] + logBeta[t][l] - logZ) } }
    }

    private fun logSumExp(values: DoubleArray): Double {
        val maxVal = values.max()
        if (!maxVal.isFinite()) return Double.NEGATIVE_INFINITY
        var sumExp = 0.0
        for (v in values) sumExp += Math.exp(v - maxVal)
        return maxVal + Math.log(sumExp)
    }

    private fun argmax(values: DoubleArray): Int {
        var bestIdx = 0
        var best = values[0]
        for (i in 1 until values.size) {
            if (values[i] > best) {
                best = values[i]
                bestIdx = i
            }
        }
        return bestIdx
    }
}
