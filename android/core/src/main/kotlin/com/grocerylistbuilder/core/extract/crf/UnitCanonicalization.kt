package com.grocerylistbuilder.core.extract.crf

/**
 * Reproduces `en/_utils.py:convert_to_pint_unit`'s STRING output (what `str(pint.Unit(...))`
 * returns for a recognized unit) without depending on pint at all. `grocery/extract/parse.py`
 * calls `parse_ingredient()` with `string_units=False` (the default), so recognized units become
 * `pint.Unit` objects and `str()` on those returns pint's *canonical* name — e.g. "oz" -> "ounce",
 * "Tbsp" -> "tablespoon" — not the original text. `parse.py` never calls `.convert_to()`, so no
 * unit *conversion* math is needed, only this canonicalization — a finite, enumerable lookup
 * generated once by running the real `convert_to_pint_unit()` (see
 * `scripts/generate_android_crf_resources.py`), not a hand-ported pint.
 */
object UnitCanonicalization {
    /** value is the pint-canonical string if the unit is recognized; `null` (present as an
     * explicit map entry) means pint did NOT recognize it — it stays a plain string. This
     * distinction matters: `ingredient_amount_factory` only pluralizes `_unit` when it's still a
     * Python string (i.e. NOT pint-recognized) — a recognized unit's canonical form is never
     * pluralized, regardless of quantity ("2 cups" -> unit "cup", not "cups"). */
    private val table: Map<String, String?> by lazy {
        ResourceData.nullableStringMap(ResourceData.readJsonObject("unit_canonicalization.json.gz"))
    }

    private val unitReplacements: List<Pair<Regex, String>> = listOf(
        Regex("""\b(fl oz)\b""") to "floz",
        Regex("""\b(fluid oz)\b""") to "fluid_ounce",
        Regex("""\b(fl ounce)\b""") to "fluid_ounce",
        Regex("""\b(fluid ounce)\b""") to "fluid_ounce",
        Regex("""\bC\b""") to "cup",
        Regex("""\bc\b""") to "cup",
        Regex("""\bqt\b""") to "quart",
        Regex("""\bCl\b""") to "centiliter",
        Regex("""\bG\b""") to "gram",
        Regex("""\bMl\b""") to "milliliter",
        Regex("""\bMm\b""") to "millimeter",
        Regex("""\bPt\b""") to "pint",
        Regex("""\bTb\b""") to "tablespoon",
    )

    private val misinterpretedUnits = setOf(
        "pinch", "pinches", "bar", "bars", "link", "links", "shake", "shakes",
        "tin", "tins", "unit", "units", "fat",
    )

    data class CanonicalUnit(val text: String, val recognized: Boolean)

    /** Returns the pint-canonicalized unit string for [unit] plus whether pint recognized it at
     * all (mirrors `convert_to_pint_unit` + `str()`, `us_customary` system) — callers need
     * [CanonicalUnit.recognized] to decide whether to pluralize afterward (recognized units never
     * are; see the class doc comment). */
    fun canonicalize(unit: String): CanonicalUnit {
        if ("-" in unit) return CanonicalUnit(unit, recognized = false)
        if (unit.lowercase() in misinterpretedUnits) return CanonicalUnit(unit, recognized = false)

        var replaced = unit
        for ((pattern, replacement) in unitReplacements) replaced = pattern.replace(replaced, replacement)

        val canonical = table[replaced]
        return if (canonical != null) CanonicalUnit(canonical, recognized = true) else CanonicalUnit(replaced, recognized = false)
    }
}
