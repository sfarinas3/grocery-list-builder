"""Finds candidate ingredient lines within raw, unstructured text (OCR output,
document text, a non-JSON-LD web page) — the job the local LLM used to do
before it was dropped from this app (see docs/design.md and `local_llm.py`).

A plain heuristic, deliberately not a trained model: heading detection first
("Ingredients:" ... next section), a quantity/unit line filter as a fallback
when no heading is found. Reuses the real `ingredient_parser` library's own
number/unit word lists rather than duplicating them. First cut, not tuned
against a large corpus — mirrors the same approach already validated (and
iterated on against real photos) in the Android app's
`IngredientLineFinder.kt`; expect this to need the same kind of tuning here.
"""

import re

# Reaching into a private submodule of `ingredient-parser-nlp` for its
# curated number-word and unit-word lists, rather than hand-duplicating ~500
# lines of constants here. Pinned to `ingredient-parser-nlp>=1.0` in
# pyproject.toml, so this stays stable across the versions we actually use.
from ingredient_parser.en._constants import FLATTENED_UNITS_LIST, STRING_NUMBERS

from grocery.extract.noise import is_likely_noise

_UNIT_WORDS = {u.lower() for u in FLATTENED_UNITS_LIST}

_HEADING = re.compile(
    r"^\s*(ingredients?|what you('| wi)ll need|shopping list)\s*:?\s*$", re.IGNORECASE
)
_NEXT_SECTION = re.compile(
    r"^\s*(instructions?|directions?|method|steps?|preparation|notes?|nutrition)\s*:?\s*$",
    re.IGNORECASE,
)
_BULLET = re.compile(r"^\s*[-*•]\s*")

_LEADING_QUANTITY = re.compile(
    r"^\s*(\d|[¼½¾⅓⅔⅕⅖⅗⅘⅙⅚⅛⅜⅝⅞]|(" + "|".join(re.escape(w) for w in STRING_NUMBERS) + r")\b)",
    re.IGNORECASE,
)

# Lines starting with one of these are a cooking step, not an ingredient --
# catches both "Preheat the oven..." and numbered/bulleted steps like
# "1. Preheat the oven...".
_INSTRUCTION_VERBS = {
    "preheat", "mix", "add", "bake", "stir", "combine", "pour", "whisk", "heat", "cook",
    "serve", "remove", "place", "cover", "season", "cut", "chop", "let", "transfer",
    "reduce", "simmer", "drain", "spread", "sprinkle", "garnish", "repeat", "continue",
    "meanwhile", "once", "using", "brown", "boil", "roast", "grill", "melt", "beat",
    "fold", "knead", "chill", "freeze", "marinate", "toss", "arrange", "line", "grease",
}


def find_candidate_lines(text: str) -> list[str]:
    """Return the lines of `text` that look like ingredient lines."""
    lines = text.strip().splitlines()

    heading_idx = next((i for i, line in enumerate(lines) if _HEADING.match(line)), None)
    if heading_idx is not None:
        return _heading_scoped(lines[heading_idx + 1 :])

    return [
        stripped
        for line in lines
        if (stripped := _BULLET.sub("", line).strip())
        and _is_candidate_line(stripped)
        and not is_likely_noise(stripped)
    ]


def _heading_scoped(lines: list[str]) -> list[str]:
    """Take every non-blank line after an "Ingredients:" heading up to the next
    section heading. No quantity/unit filtering here (real ingredient lines
    like "salt to taste" have neither) -- just the noise filter, to screen out
    UI chrome a photo of a phone/website can capture alongside the recipe.
    """
    section = []
    for line in lines:
        if _NEXT_SECTION.match(line):
            break
        if not line.strip():
            continue
        stripped = _BULLET.sub("", line).strip()
        if is_likely_noise(stripped):
            continue
        section.append(stripped)
    return section


def _is_candidate_line(line: str) -> bool:
    """No heading found -- fall back to a quantity/unit line filter."""
    words = line.split()
    if not words:
        return False

    leading = [w.strip(":.,").lower() for w in words[:2]]
    if any(w in _INSTRUCTION_VERBS for w in leading):
        return False

    if _LEADING_QUANTITY.search(line):
        return True

    # A unit word near the start suggests "<qty> <unit> ...". Checking only
    # nearby words (not the whole line) avoids false positives from ordinary
    # words that double as unit names deep in an instruction sentence (e.g.
    # "brown the beef in a large pot").
    return any(w.strip(",.:;").lower() in _UNIT_WORDS for w in words[:4])
