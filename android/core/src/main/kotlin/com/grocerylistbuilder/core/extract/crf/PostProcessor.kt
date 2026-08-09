package com.grocerylistbuilder.core.extract.crf

/**
 * Ports `en/postprocess.py`'s `PostProcessor`: converts CRF-labelled tokens into names, amounts,
 * preparation, and comment text. Faithfully replicates a real quirk in the Python original: most
 * methods track consumed tokens by their **list position** within (a possibly-shrunk)
 * `self.tokens`, while the amount-pattern methods track consumed tokens by their **original
 * sentence index** (`.index`) — the two numbering schemes only diverge when
 * `_convert_string_number_qty` actually removes a token (rare: only for multi-token QTY merges
 * like "1 and 1/2"), at which point Python's own behavior already depends on this mixing. This
 * port reproduces it exactly rather than "fixing" it, verified by differential tests that include
 * such merge cases.
 *
 * Trimmed vs. the Python original: no `pint`/foundation-foods/purpose output (none of it is read
 * by `grocery/extract/parse.py` — see [ParsedAmount]/[ParsedIngredientResult]), and amount/name
 * building only produces the fields `parse.py` actually reads. The flag-detection methods
 * (`_is_approximate` etc.) still run at the same call sites for their consumed-token side effects,
 * even though this port discards the resulting flags.
 */
class PostProcessor(initialTokens: List<LabelledToken>, private val discardIsolatedStopWords: Boolean = true) {
    private val tokens: MutableList<LabelledToken> = initialTokens.toMutableList()
    private val consumed = mutableSetOf<Int>()

    fun parsed(): ParsedIngredientResult {
        val amounts = postprocessAmounts()
        val names = postprocessNames()
        postprocess("SIZE") // discarded; still runs for its consumed-token side effects
        val preparation = postprocess("PREP")
        val comment = postprocess("COMMENT")
        return ParsedIngredientResult(names, amounts, preparation, comment)
    }

    // ---- Generic single-label extraction (SIZE / PREP / COMMENT) ----

    private fun postprocess(selectedLabel: String): IngredientText? {
        val labelIdx = tokens.indices.filter { i ->
            (tokens[i].label == selectedLabel || tokens[i].label == "PUNC") && i !in consumed
        }
        if (labelIdx.isEmpty() || labelIdx.all { tokens[it].label == "PUNC" }) return null
        return postprocessIndices(labelIdx, selectedLabel)
    }

    private val leadingStripText = setOf(")", "]", "}", ",", ":", ";", "-", ".", "!", "?", "*", "&", "/", "--")
    private val trailingStripText = setOf("[", "(", "{", ",", ":", ";", "-", "&", "/", "*", "--", "+")

    private fun removeInvalidIndices(groupIn: List<Int>): List<Int> {
        var idx = groupIn
        while (idx.size > 1 && tokens[idx.first()].text in leadingStripText) idx = idx.drop(1)
        while (idx.size > 1 && tokens[idx.last()].text in trailingStripText) idx = idx.dropLast(1)

        val idxToRemove = mutableSetOf<Int>()
        val stack = mutableMapOf<String, MutableList<Int>>()
        for ((i, tok) in idx.map { tokens[it].text }.withIndex()) {
            val tokName = when (tok) {
                "(", ")" -> "PAREN"
                "[", "]" -> "SQUARE"
                else -> null
            }
            when (tok) {
                "(", "[" -> stack.getOrPut(tokName!!) { mutableListOf() }.add(i)
                ")", "]" -> {
                    val s = stack.getOrPut(tokName!!) { mutableListOf() }
                    if (s.isEmpty()) idxToRemove.add(i) else s.removeAt(s.size - 1)
                }
            }
        }
        for (s in stack.values) idxToRemove.addAll(s)
        return idx.filterIndexed { i, _ -> i !in idxToRemove }
    }

    private fun fixPunctuation(input: String): String {
        if (input.isEmpty()) return input
        var text = input.replace("( ", "(").replace(" )", ")").replace(" / ", "/")
        for (punc in listOf(",", ":", ";", ".", "!", "?", "*")) text = text.replace(" $punc", punc)
        return text.trim()
    }

    private fun removeAdjacentDuplicates(parts: List<String>): List<Int> {
        val padded = parts + ""
        return parts.indices.filter { padded[it] != padded[it + 1] }
    }

    private fun postprocessIndices(labelIdx: List<Int>, selectedLabel: String): IngredientText? {
        val parts = mutableListOf<String>()
        val confidenceParts = mutableListOf<Double>()
        var startingIndex = labelIdx.last()

        for (group in groupConsecutive(labelIdx)) {
            val idx = removeInvalidIndices(group)
            if (idx.isEmpty()) continue
            if (idx.all { tokens[it].label == "PUNC" }) continue

            val groupTokens = idx.map { i ->
                val t = tokens[i]
                if (Patterns.FRACTION_TOKEN_PATTERN.matches(t.text)) {
                    t.text.replace("#", " ").replace("$", "/").trim().replace("- ", "-")
                } else {
                    t.text
                }
            }
            val confidence = idx.map { tokens[it].score }.average()

            val joined = groupTokens.joinToString(" ")
            if (discardIsolatedStopWords && joined.lowercase() in Constants.STOP_WORDS) continue

            consumed.addAll(idx)
            parts.add(joined)
            confidenceParts.add(confidence)
            startingIndex = minOf(startingIndex, idx.first())
        }

        val keepIdx = removeAdjacentDuplicates(parts)
        val keptParts = keepIdx.map { parts[it] }
        val keptConfidence = keepIdx.map { confidenceParts[it] }
        if (keptParts.isEmpty()) return null

        val joinedText = if (selectedLabel == "NAME") keptParts.joinToString(" ") else keptParts.joinToString(", ")
        val text = pluraliseUnits(fixPunctuation(joinedText))

        return IngredientText(text, round6(keptConfidence.average()), startingIndex)
    }

    // ---- Names ----

    private fun postprocessNames(): List<IngredientText> {
        val nameIdx = tokens.indices.filter { i -> ("NAME" in tokens[i].label || tokens[i].label == "PUNC") && i !in consumed }
        if (nameIdx.isEmpty() || nameIdx.all { tokens[it].label == "PUNC" }) return emptyList()

        val nameLabels = nameIdx.map { tokens[it].label }
        val bioGroups = groupNameLabels(nameLabels)
        val constructedNames = constructNamesFromBioGroups(bioGroups)
        return convertNameIndicesToObjects(nameIdx, constructedNames)
    }

    private fun groupNameLabels(nameLabels: List<String>): List<List<Pair<Int, String>>> {
        val groups = mutableListOf<MutableList<Pair<Int, String>>>()
        var current = mutableListOf<Pair<Int, String>>()
        var prevLabel: String? = null
        for ((idx, label) in nameLabels.withIndex()) {
            when {
                label == "NAME_SEP" -> {
                    if (current.isNotEmpty()) groups.add(current)
                    current = mutableListOf()
                }
                label.startsWith("B_") -> {
                    if (current.isNotEmpty()) groups.add(current)
                    current = mutableListOf(idx to label)
                }
                label == "NAME_MOD" || label == "NAME_VAR" -> {
                    if (prevLabel == label) {
                        current.add(idx to label)
                    } else {
                        if (current.isNotEmpty()) groups.add(current)
                        current = mutableListOf(idx to label)
                    }
                }
                else -> current.add(idx to label)
            }
            prevLabel = label
        }
        if (current.isNotEmpty()) groups.add(current)
        return groups
    }

    private fun nameGroupLabel(labels: List<String>): String {
        for (label in labels) if (label != "PUNC") return label.substringAfterLast("_")
        return ""
    }

    private fun constructNamesFromBioGroups(nameGroups: List<List<Pair<Int, String>>>): List<List<Int>> {
        val constructed = mutableListOf<List<Int>>()
        var lastEncounteredName: List<Int>? = null
        var lastEncounteredNameUsed = false

        for (group in nameGroups.asReversed()) {
            val currentGroupIdx = group.map { it.first }
            when (nameGroupLabel(group.map { it.second })) {
                "TOK" -> {
                    if (lastEncounteredName != null && !lastEncounteredNameUsed) constructed.add(lastEncounteredName)
                    lastEncounteredName = currentGroupIdx
                    lastEncounteredNameUsed = false
                }
                "VAR" -> {
                    if (lastEncounteredName != null) {
                        constructed.add(currentGroupIdx + lastEncounteredName)
                        lastEncounteredNameUsed = true
                    } else {
                        constructed.add(currentGroupIdx)
                    }
                }
                "MOD" -> {
                    if (lastEncounteredName != null && !lastEncounteredNameUsed) {
                        constructed.add(lastEncounteredName)
                        lastEncounteredNameUsed = true
                    }
                    for (i in constructed.indices) constructed[i] = currentGroupIdx + constructed[i]
                }
            }
        }
        if (lastEncounteredName != null && !lastEncounteredNameUsed) constructed.add(lastEncounteredName)
        return constructed.asReversed()
    }

    private fun lastNonPuncTokenPos(tokenIdx: List<Int>): String {
        for (idx in tokenIdx.asReversed()) {
            if (tokens[idx].label == "PUNC") continue
            return tokens[idx].posTag
        }
        return ""
    }

    private fun mergeTexts(objs: List<IngredientText>): IngredientText {
        val sorted = objs.sortedBy { it.startingIndex }
        val text = if (sorted.map { it.text }.toSet().size == 1) sorted[0].text else sorted.joinToString(" ") { it.text }
        return IngredientText(text, round6(sorted.map { it.confidence }.average()), sorted.minOf { it.startingIndex })
    }

    private fun convertNameIndicesToObjects(nameIdx: List<Int>, nameIndexGroups: List<List<Int>>): List<IngredientText> {
        var mergeWithNext = false
        var mergeWithNextIdx: List<Int> = emptyList()
        val mergedNameIdx = mutableListOf<List<Int>>()

        for (group in nameIndexGroups) {
            var tokenIdx = group.map { nameIdx[it] }
            if (mergeWithNext && mergeWithNextIdx.isNotEmpty()) tokenIdx = mergeWithNextIdx + tokenIdx

            if (lastNonPuncTokenPos(tokenIdx) in setOf("DT", "IN", "JJ")) {
                mergeWithNext = true
                mergeWithNextIdx = tokenIdx
            } else {
                mergedNameIdx.add(tokenIdx)
                mergeWithNext = false
                mergeWithNextIdx = emptyList()
            }
        }
        if (mergeWithNext && mergeWithNextIdx.isNotEmpty()) mergedNameIdx.add(mergeWithNextIdx)

        val names = mutableListOf<IngredientText>()
        for (tokenIdx in mergedNameIdx) {
            val ingText = postprocessIndices(tokenIdx, "NAME") ?: continue
            val dupeIdx = names.indices.filter { names[it].text == ingText.text }
            if (dupeIdx.isNotEmpty()) {
                names[dupeIdx[0]] = mergeTexts(dupeIdx.map { names[it] } + ingText)
            } else {
                names.add(ingText)
            }
        }
        return names
    }

    // ---- Amounts ----

    private fun postprocessAmounts(): List<AmountResult> {
        convertStringNumberQty()
        val amounts = mutableListOf<AmountResult>()
        amounts += sizeableUnitPattern(unconsumed())
        amounts += compositeAmountsPattern(unconsumed())
        amounts += fallbackPattern(unconsumed())
        return amounts.sortedBy { it.startingIndex }
    }

    private fun unconsumed(): List<LabelledToken> = tokens.filter { it.index !in consumed }

    private fun replaceStringNumbers(text: String): String {
        var result = text
        for ((regex, replacement) in Constants.STRING_NUMBERS_REGEXES) result = regex.replace(result, replacement)
        return result
    }

    private fun convertStringNumberQty() {
        for (t in tokens) if (t.label == "QTY") t.text = replaceStringNumbers(t.text)

        val qtyIdx = tokens.filter { it.label == "QTY" }.map { it.index }
        val idxToRemove = mutableSetOf<Int>()
        for (group in groupConsecutive(qtyIdx)) {
            if (group.size == 1) continue
            val fragment = group.joinToString(" ") { i -> tokens.first { it.index == i }.text }

            var replacement = combineQuantitiesSplitByAndFragment(fragment)
            if (replacement != fragment) {
                applyQtyMerge(group, replacement)
                idxToRemove.addAll(group.drop(1))
                continue
            }
            replacement = replaceStringRangeFragment(fragment)
            if (replacement != fragment) {
                applyQtyMerge(group, replacement)
                idxToRemove.addAll(group.drop(1))
                continue
            }
        }
        if (idxToRemove.isNotEmpty()) tokens.removeAll { it.index in idxToRemove }
    }

    private fun applyQtyMerge(group: List<Int>, replacement: String) {
        val modToken = tokens.first { it.index == group[0] }
        modToken.score = group.map { i -> tokens.first { it.index == i }.score }.average()
        modToken.text = replacement
    }

    private fun combineQuantitiesSplitByAndFragment(text: String): String {
        var s = text
        for (m in Patterns.FRACTION_SPLIT_AND_PATTERN.findAll(text)) {
            val whole = m.groupValues[1]
            val replacement = m.groupValues[2] + "#" + m.groupValues[3].replace("/", "$")
            s = s.replace(whole, replacement)
        }
        return s
    }

    private fun replaceStringRangeFragment(s: String): String =
        Patterns.STRING_RANGE_PATTERN.replace(s) { "${it.groupValues[1]}-${it.groupValues[5]}" }

    // ---- Flag detection (consumed side effects matter; boolean results are otherwise unused) ----

    private fun <T> safeSlice(list: List<T>, from: Int, to: Int): List<T> {
        if (from >= list.size || from < 0) return emptyList()
        return list.subList(from, minOf(to, list.size))
    }

    private fun isApproximate(i: Int, localTokens: List<LabelledToken>): Boolean {
        if (i < 0 || i >= localTokens.size) return false
        val t = localTokens[i]
        if (t.label == "QTY" && i > 0 && localTokens[i - 1].text.lowercase() in Constants.APPROXIMATE_PREFIXES) {
            consumed.add(localTokens[i - 1].index)
            return true
        } else if (t.label == "QTY" && i > 1 && localTokens[i - 1].text == "." && localTokens[i - 2].text.lowercase() in Constants.APPROXIMATE_PREFIXES) {
            consumed.add(localTokens[i - 1].index)
            consumed.add(localTokens[i - 2].index)
            return true
        } else if (t.label == "UNIT" && i > 0 && localTokens[i - 1].text.lowercase() in Constants.APPROXIMATE_PREFIXES) {
            consumed.add(localTokens[i - 1].index)
            return true
        } else if (
            (t.label == "UNIT" || t.label == "QTY") &&
            i < tokens.size - 2 &&
            safeSlice(localTokens, i + 1, i + 3).map { it.text.lowercase() } in Constants.APPROXIMATE_SUFFIXES
        ) {
            consumed.add(localTokens[i + 1].index)
            consumed.add(localTokens[i + 2].index)
            return true
        }
        return false
    }

    private fun isSingular(i: Int, localTokens: List<LabelledToken>): Boolean {
        if (i < 0 || i >= localTokens.size) return false
        if (i == localTokens.size - 1) return false
        if (localTokens[i].label == "UNIT" && localTokens[i + 1].text.lowercase() in Constants.SINGULAR_TOKENS) {
            consumed.add(localTokens[i + 1].index)
            return true
        }
        if (i == localTokens.size - 2) return false
        if (
            localTokens[i].label == "UNIT" &&
            localTokens[i + 1].text in setOf(")", "]") &&
            localTokens[i + 2].text.lowercase() in Constants.SINGULAR_TOKENS
        ) {
            consumed.add(localTokens[i + 2].index)
            return true
        }
        return false
    }

    private fun isSingularAndApproximate(i: Int, localTokens: List<LabelledToken>): Boolean {
        if (i < 0 || i >= localTokens.size) return false
        if (
            localTokens[i].label == "QTY" && i > 1 &&
            localTokens[i - 1].text.lowercase() in Constants.APPROXIMATE_PREFIXES &&
            localTokens[i - 2].text.lowercase() in Constants.SINGULAR_TOKENS
        ) {
            consumed.add(localTokens[i - 1].index)
            consumed.add(localTokens[i - 2].index)
            return true
        } else if (
            localTokens[i].label == "UNIT" && i < tokens.size - 3 &&
            safeSlice(localTokens, i + 1, i + 3).map { it.text.lowercase() } in Constants.APPROXIMATE_SUFFIXES &&
            localTokens.getOrNull(i + 3)?.text?.lowercase() in Constants.SINGULAR_TOKENS
        ) {
            consumed.add(localTokens[i + 1].index)
            consumed.add(localTokens[i + 2].index)
            consumed.add(localTokens[i + 3].index)
            return true
        }
        return false
    }

    private fun isPrepared(i: Int, localTokens: List<LabelledToken>): Boolean {
        if (i < 0 || i >= localTokens.size) return false
        if (i < 2) return false
        if (localTokens[i].label != "QTY") return false
        for (pattern in Constants.PREPARED_INGREDIENT_TOKENS) {
            if (safeSlice(localTokens, i - 2, i).map { it.text.lowercase() } == pattern) {
                consumed.add(localTokens[i - 1].index)
                consumed.add(localTokens[i - 2].index)
                return true
            } else if (
                i > 2 &&
                localTokens[i - 1].text.lowercase() in Constants.APPROXIMATE_PREFIXES &&
                safeSlice(localTokens, i - 3, i - 1).map { it.text.lowercase() } == pattern
            ) {
                consumed.add(localTokens[i - 2].index)
                consumed.add(localTokens[i - 3].index)
                return true
            }
        }
        return false
    }

    // ---- Amount pattern matching ----

    private fun matchPattern(localTokens: List<LabelledToken>, pattern: List<String>, ignoreOtherLabels: Boolean = true): List<List<Int>> {
        val labels = localTokens.map { it.label }
        val plen = pattern.size
        val plabels = pattern.toSet()
        val lbls: List<String>
        val idx: List<Int>
        if (ignoreOtherLabels) {
            val filtered = labels.withIndex().filter { it.value in plabels }
            lbls = filtered.map { it.value }
            idx = filtered.map { it.index }
        } else {
            lbls = labels
            idx = labels.indices.toList()
        }
        if (pattern.size > lbls.size) return emptyList()

        val matches = mutableListOf<List<Int>>()
        var i = 0
        while (i < lbls.size) {
            if (lbls[i] == pattern[0] && i + plen <= lbls.size && lbls.subList(i, i + plen) == pattern) {
                matches.add(idx.subList(i, i + plen))
                i += plen
            } else {
                i += 1
            }
        }
        return matches
    }

    private val sizeableUnitEndUnits = setOf(
        "bag", "block", "bottle", "box", "bucket", "can", "carton", "container", "envelope",
        "jar", "loaf", "package", "packet", "piece", "sachet", "slice", "tin",
    )

    private fun sizeableUnitPattern(localTokens: List<LabelledToken>): List<AmountResult> {
        val patterns = listOf(
            listOf("QTY", "QTY", "UNIT", "QTY", "UNIT", "QTY", "UNIT", "UNIT"),
            listOf("QTY", "QTY", "UNIT", "QTY", "UNIT", "UNIT"),
            listOf("QTY", "QTY", "UNIT", "UNIT"),
            listOf("QTY", "UNIT", "UNIT"),
        )
        val amounts = mutableListOf<AmountResult>()

        for (pattern in patterns) {
            for (rawMatch in matchPattern(localTokens, pattern, ignoreOtherLabels = true)) {
                if (rawMatch.any { localTokens[it].index in consumed }) continue
                if (localTokens[rawMatch.last()].text !in sizeableUnitEndUnits) continue

                val match = rawMatch.toMutableList()
                val matchingTokens = match.map { localTokens[it].text }.toMutableList()
                val matchingScores = match.map { localTokens[it].score }.toMutableList()
                consumed.addAll(match.map { localTokens[it].index })

                if (pattern == patterns[3]) {
                    val unit = matchingTokens.removeAt(matchingTokens.size - 1)
                    val conf = matchingScores.removeAt(matchingScores.size - 1)
                    isApproximate(match[0], localTokens)
                    amounts.add(AmountResult.Single(buildAmount("1", unit, conf, localTokens[match[0]].index)))
                    match.removeAt(match.size - 1)
                } else {
                    val quantity = matchingTokens.removeAt(0)
                    val unit = matchingTokens.removeAt(matchingTokens.size - 1)
                    val conf = listOf(matchingScores.removeAt(0), matchingScores.removeAt(matchingScores.size - 1)).average()
                    isApproximate(match[0], localTokens)
                    amounts.add(AmountResult.Single(buildAmount(quantity, unit, conf, localTokens[match[0]].index)))
                    match.removeAt(0)
                    match.removeAt(match.size - 1)
                }

                var i = 0
                while (i < matchingTokens.size) {
                    val quantity = matchingTokens[i]
                    val unit = matchingTokens[i + 1]
                    val confidence = matchingScores[i]
                    amounts.add(AmountResult.Single(buildAmount(quantity, unit, confidence, localTokens[match[i]].index)))
                    i += 2
                }
            }
        }
        return amounts
    }

    private data class CompositePattern(
        val pattern: List<String>,
        val conjunction: String?,
        val conjIndex: Int?,
        val start1: Int,
        val start2: Int,
    )

    private fun compositeAmountsPattern(localTokens: List<LabelledToken>): List<AmountResult> {
        val patterns = linkedMapOf(
            "ptfloz" to CompositePattern(listOf("QTY", "UNIT", "QTY", "UNIT", "UNIT"), null, null, 0, 2),
            "lboz" to CompositePattern(listOf("QTY", "UNIT", "QTY", "UNIT"), null, null, 0, 2),
            "plus" to CompositePattern(listOf("QTY", "UNIT", "COMMENT", "QTY", "UNIT"), "plus", 2, 0, 3),
            "plus_punc" to CompositePattern(listOf("QTY", "UNIT", "PUNC", "QTY", "UNIT"), "+", 2, 0, 3),
            "plus_punc_comment" to CompositePattern(listOf("QTY", "UNIT", "PUNC", "COMMENT", "QTY", "UNIT"), "plus", 3, 0, 4),
            "and" to CompositePattern(listOf("QTY", "UNIT", "COMMENT", "QTY", "UNIT"), "and", 2, 0, 3),
            "minus" to CompositePattern(listOf("QTY", "UNIT", "COMMENT", "QTY", "UNIT"), "minus", 2, 0, 3),
            "less" to CompositePattern(listOf("QTY", "UNIT", "COMMENT", "QTY", "UNIT"), "less", 2, 0, 3),
        )
        val validFirstUnits = setOf("lb", "pound", "pt", "pint")
        val validLastUnits = setOf("oz", "ounce")

        val composite = mutableListOf<AmountResult>()
        for ((name, info) in patterns) {
            for (match in matchPattern(localTokens, info.pattern, ignoreOtherLabels = false)) {
                if (name == "ptfloz" || name == "lboz") {
                    val firstUnit = localTokens[match[info.start1 + 1]].text
                    val lastUnit = localTokens[match.last()].text
                    if (firstUnit !in validFirstUnits || lastUnit !in validLastUnits) continue
                } else {
                    if (localTokens[match[info.conjIndex!!]].text.lowercase() != info.conjunction) continue
                }

                val mstart1 = match[info.start1]
                val mstart2 = match[info.start2]

                // Python passes `.index` (original sentence index) as the position argument to
                // these detectors, which then index directly into `localTokens` (list positions)
                // -- a real mismatch in the upstream library whenever `localTokens` (an
                // already-consumed-filtered view) has shifted away from original indices. Ported
                // literally (bounds-guarded rather than crashing) rather than "fixed", since the
                // goal is matching Python's actual behavior, not correcting it.
                isPrepared(localTokens[mstart1].index, localTokens)
                isPrepared(localTokens[mstart2].index, localTokens)
                isApproximate(localTokens[mstart1].index, localTokens)
                isPrepared(localTokens[mstart2].index, localTokens)
                isSingular(localTokens[mstart1 + 1].index, localTokens)
                isSingular(localTokens[match.last()].index, localTokens)
                isSingularAndApproximate(localTokens[mstart1].index, localTokens)
                isSingularAndApproximate(localTokens[mstart2].index, localTokens)

                consumed.addAll(match.map { localTokens[it].index })
                composite.add(AmountResult.Composite(localTokens[mstart1].index))
            }
        }
        return composite
    }

    private data class PartialAmount(
        var quantity: String,
        val unit: MutableList<String>,
        val confidence: MutableList<Double>,
        val startingIndex: Int,
        var implicitQuantity: Boolean = false,
    )

    private fun fallbackPattern(localTokens: List<LabelledToken>): List<AmountResult> {
        val amounts = mutableListOf<PartialAmount>()
        val relatedIdx = localTokens.filter { it.text in setOf("(", "/", "[") }.map { it.index + 1 }.toSet()

        for ((i, token) in localTokens.withIndex()) {
            if (token.label == "QTY") {
                if (token.text == "dozen" && i > 0 && localTokens[i - 1].label == "QTY") {
                    val last = amounts.last()
                    last.quantity = last.quantity + " dozen"
                    last.confidence.add(token.score)
                } else {
                    amounts.add(PartialAmount(token.text, mutableListOf(), mutableListOf(token.score), token.index))
                }
            }

            if (token.label == "UNIT") {
                if (amounts.isEmpty()) {
                    var quantity = ""
                    var implicitQuantity = false
                    val indefiniteBefore = Constants.INDEFINITE_QUANTIFIERS.any { q ->
                        localTokens.subList(0, i).any { it.text.lowercase() == q }
                    }
                    if (!token.plural && !indefiniteBefore) {
                        quantity = "1"
                        implicitQuantity = true
                    }
                    amounts.add(PartialAmount(quantity, mutableListOf(), mutableListOf(), token.index, implicitQuantity))
                }
                var text = token.text
                val last = amounts.last()
                if (token.plural && last.implicitQuantity) {
                    last.quantity = ""
                    last.implicitQuantity = false
                    text = pluraliseUnits(token.text)
                } else if (token.plural && last.quantity == "") {
                    text = pluraliseUnits(token.text)
                }
                last.unit.add(text)
                last.confidence.add(token.score)
            }

            if (amounts.isNotEmpty()) {
                isApproximate(i, localTokens)
                isSingular(i, localTokens)
                isSingularAndApproximate(i, localTokens)
                isPrepared(i, localTokens)
            }
        }

        return amounts.map { amount ->
            val unit = amount.unit.joinToString(" ")
            val confidence = if (amount.confidence.isEmpty()) 0.0 else amount.confidence.average()
            AmountResult.Single(buildAmount(amount.quantity, unit, confidence, amount.startingIndex))
        }
    }


    // ---- Amount value construction (trimmed `ingredient_amount_factory`) ----

    private fun buildAmount(quantityTextIn: String, unitText: String, confidence: Double, startingIndex: Int): ParsedAmount {
        var quantityText = quantityTextIn
        if (quantityText.endsWith("x")) quantityText = quantityText.dropLast(1)

        val isRangeQty = Patterns.RANGE_PATTERN.matches(quantityText)
        val quantityValue: Double? = when {
            isRangeQty -> quantityText.split("-").mapNotNull { toFracOrNull(it) }.minOrNull()
            isFloatOrFractionToken(quantityText) -> toFracOrNull(quantityText)
            else -> null
        }

        var unit: String? = null
        if (unitText.isNotEmpty()) {
            val canonical = UnitCanonicalization.canonicalize(unitText)
            unit = canonical.text
            // Only pluralize units pint did NOT recognize (still plain strings) -- a recognized
            // unit's canonical form is never pluralized regardless of quantity, matching
            // `ingredient_amount_factory`'s `if isinstance(_unit, str): _unit = pluralise_units(...)`.
            if (!canonical.recognized && quantityValue != null && quantityValue != 1.0 && !isRangeQty) {
                unit = pluraliseUnits(unit)
            }
        }

        return ParsedAmount(quantityValue, unit, round6(confidence), startingIndex)
    }

    private fun isFloatOrFractionToken(q: String): Boolean = q.toDoubleOrNull() != null || Patterns.FRACTION_TOKEN_PATTERN.matches(q)

    private fun toFracOrNull(token: String): Double? {
        if (Patterns.FRACTION_TOKEN_PATTERN.matches(token)) {
            val parts = token.split("#").filter { it.isNotEmpty() }
            if (parts.isEmpty()) return null
            return parts.sumOf { part ->
                val p = part.replace("$", "/")
                if ("/" in p) {
                    val (n, d) = p.split("/")
                    n.toDouble() / d.toDouble()
                } else {
                    p.toDouble()
                }
            }
        }
        return token.toDoubleOrNull()
    }

    private fun pluraliseUnits(text: String): String {
        var result = text
        for ((singular, regex) in pluraliseRegexes) result = regex.replace(result, singular)
        return result
    }

    companion object {
        private val pluraliseRegexes: List<Pair<String, Regex>> =
            Constants.UNITS.entries.map { (plural, singular) -> plural to Regex("""\b($singular)\b""") }

        fun groupConsecutive(idx: List<Int>): List<List<Int>> {
            if (idx.isEmpty()) return emptyList()
            val groups = mutableListOf<MutableList<Int>>()
            var current = mutableListOf(idx[0])
            for (i in 1 until idx.size) {
                if (idx[i] == idx[i - 1] + 1) {
                    current.add(idx[i])
                } else {
                    groups.add(current)
                    current = mutableListOf(idx[i])
                }
            }
            groups.add(current)
            return groups
        }
    }
}

internal fun round6(value: Double): Double {
    val factor = 1_000_000.0
    return Math.round(value * factor) / factor
}
