package com.grocerylistbuilder.core.extract.crf

/**
 * A minimal, purpose-built re-implementation of `nltk.RegexpParser`'s tag-pattern matching, scoped
 * to exactly what `en/_structure_features.py`'s 4 grammars need (verified against the real NLTK
 * objects: each `LABEL: {pattern}` line is its OWN stage — `._stages` showed one `ChunkRule` per
 * stage, applied in sequence, each stage scanning only the segments left unchunked by earlier
 * stages). Not a general regex-over-tags engine — no chink/split/merge rules, no alternation across
 * multiple rules within one stage, since none of the 4 grammars used here need those.
 *
 * A [Segment] is either an original token (tag = its POS tag) or a chunk produced by an earlier
 * stage (tag = that stage's label) — later stages match against this mixed sequence exactly like
 * NLTK's cascaded chunking treats an already-formed subtree as one opaque unit.
 */
data class Segment(val tag: String, val indices: List<Int>)

sealed class Elem {
    /** Matches if a segment's tag equals one of [alts] exactly, or (for entries ending in ".*",
     * matching this grammar DSL's only wildcard usage) the tag starts with that prefix. */
    data class Atom(val alts: List<String>, val min: Int, val max: Int) : Elem()
    data class Group(val sub: List<Elem>, val min: Int, val max: Int) : Elem()
}

private fun tagMatches(tag: String, alts: List<String>): Boolean =
    alts.any { alt -> if (alt.endsWith(".*")) tag.startsWith(alt.dropLast(2)) else tag == alt }

object TagPatternMatcher {

    /** One stage: match [pattern] against [segments], leftmost-first, greedy, non-overlapping.
     * Returns the resulting segment list with matched spans replaced by a single [label] segment
     * (mirrors a `ChunkRule` producing one new subtree per match) plus the list of newly-created
     * segments' original index-lists (for phrase detection callers that need the leaf indices). */
    fun applyStage(segments: List<Segment>, pattern: List<Elem>, label: String): Pair<List<Segment>, List<Segment>> {
        val result = mutableListOf<Segment>()
        val produced = mutableListOf<Segment>()
        var pos = 0
        while (pos < segments.size) {
            val end = matchElems(segments, pos, pattern, 0)
            if (end != null && end > pos) {
                val merged = Segment(label, segments.subList(pos, end).flatMap { it.indices })
                result.add(merged)
                produced.add(merged)
                pos = end
            } else {
                result.add(segments[pos])
                pos += 1
            }
        }
        return result to produced
    }

    private fun matchElems(segments: List<Segment>, pos: Int, elems: List<Elem>, idx: Int): Int? {
        if (idx == elems.size) return pos
        return when (val elem = elems[idx]) {
            is Elem.Atom -> matchAtom(segments, pos, elem, elems, idx)
            is Elem.Group -> matchGroup(segments, pos, elem, elems, idx)
        }
    }

    private fun matchAtom(segments: List<Segment>, pos: Int, atom: Elem.Atom, elems: List<Elem>, idx: Int): Int? {
        var count = 0
        var p = pos
        // Greedily consume up to `max` matching segments first (mirrors regex greedy quantifiers).
        while (count < atom.max && p < segments.size && tagMatches(segments[p].tag, atom.alts)) {
            p += 1
            count += 1
        }
        // Backtrack the count down to `min`, trying the rest of the pattern at each length.
        while (count >= atom.min) {
            matchElems(segments, pos + count, elems, idx + 1)?.let { return it }
            count -= 1
        }
        return null
    }

    private fun matchGroup(segments: List<Segment>, pos: Int, group: Elem.Group, elems: List<Elem>, idx: Int): Int? {
        // Collect checkpoints after 0, 1, 2, ... repetitions of the group's sub-pattern (each
        // repetition matched greedily via matchElems), up to `max` reps.
        val checkpoints = mutableListOf(pos)
        var p = pos
        while (checkpoints.size - 1 < group.max) {
            val next = matchElems(segments, p, group.sub, 0) ?: break
            if (next == p) break // avoid an infinite loop on a zero-width sub-match
            checkpoints.add(next)
            p = next
        }
        // Backtrack the repetition count down to `min`, trying the rest of the pattern each time.
        for (reps in checkpoints.size - 1 downTo group.min) {
            matchElems(segments, checkpoints[reps], elems, idx + 1)?.let { return it }
        }
        return null
    }
}
