"""Recipe content -> ingredient lines (docs/design.md §3.3).

Pipeline position: ingest -> **lines** -> parse (CRF) -> normalize -> aggregate.

  - JSON-LD path: the page already lists clean lines — no model needed.
  - Raw text (non-JSON-LD pages, documents, OCR'd photos): the text is shown
    to the LLM with numbered lines, and it answers with the *numbers* of the
    ingredient lines. Picking numbers instead of copying text out is ~5x fewer
    output tokens (the slow part on a CPU) and can't garble or invent a line.
    OCR typos in the picked lines are fixed later, in `extract/normalize.py`.
  - Prose: a picked line that's really a paragraph ("you'll need a can of
    coconut milk, two tablespoons of curry paste…") is sent back to the LLM to
    be rewritten as one ingredient per line. Only these lines pay the cost of
    the model writing text out.
  - Recall check (Quality Check #1): the cheap heuristic line-finder
    (`extract/heuristic.py`) also runs, and any line *it* picked that the LLM
    didn't goes back to the LLM for a second look in one small call. This
    catches the model silently skipping a line without a full second pass.
  - No model available (or it returned nothing): the heuristic's picks are
    used as-is, so the app still works, just less accurately.
"""

from grocery import config
from grocery.extract.heuristic import find_candidate_lines
from grocery.extract.llm import LocalLLM
from grocery.models import RecipeContent

_REWRITE_SCHEMA = {
    "type": "object",
    "properties": {"ingredient_lines": {"type": "array", "items": {"type": "string"}}},
    "required": ["ingredient_lines"],
}

# A picked line longer than this (chars) is treated as prose to be rewritten.
# Real ingredient lines are short; "1 (14 oz) can coconut milk, shaken well"
# is ~40 characters.
PROSE_LINE_CHARS = 120

# Tweakable: the line-finding instructions.
_EXTRACT_SYSTEM = (
    "You find the ingredient list in recipe text, which may be a messy web page, "
    "a document, or OCR output from a photo of a screen or cookbook. The text is "
    "given with numbered lines. Answer with the numbers of every line that is an "
    'ingredient, in the JSON object {"ingredient_lines": [...]}. Leave out the '
    "recipe title, instructions, headings, servings, times, nutrition facts, temperatures, and "
    "page or app text. Don't skip ingredients that have no quantity (\"salt to taste\")."
)

# Tweakable: the prose-rewrite instructions.
_REWRITE_SYSTEM = (
    "Rewrite the ingredients mentioned in this recipe text as a shopping-style "
    'ingredient list in the JSON object {"ingredient_lines": [...]}, one ingredient '
    "per line, quantity in digits first, e.g. \"1 (14 oz) can coconut milk\", "
    "\"2 tbsp red curry paste\", \"1 lime\". Include every ingredient mentioned "
    "and nothing else."
)

# Tweakable: the recall-check instructions.
_CONFIRM_SYSTEM = (
    "These numbered lines come from a recipe. Answer with the numbers of the "
    "lines that are ingredients (with or without a quantity), in the JSON object "
    '{"ingredient_lines": [...]}. Leave out instructions, headings, and anything else.'
)


def ingredient_lines(content: RecipeContent, llm: LocalLLM | None) -> list[str]:
    """Return the ingredient lines for one recipe."""
    if content.ingredient_lines:
        return list(content.ingredient_lines)

    lines = _clean_lines(content.raw_text)
    if not lines:
        return []
    heuristic = set(find_candidate_lines("\n".join(lines)))
    if llm is None:
        return [line for line in lines if line in heuristic]

    picked = _pick(lines, llm, _EXTRACT_SYSTEM)
    if not picked:
        return [line for line in lines if line in heuristic]  # degrade, don't fail

    dropped = [i for i, line in enumerate(lines) if line in heuristic and i not in picked]
    if dropped:
        recovered = _pick([lines[i] for i in dropped], llm, _CONFIRM_SYSTEM)
        picked |= {dropped[j] for j in recovered}
    # A line ending in ":" is a sub-heading ("For the sauce:"), and the title
    # is the title — never ingredients, however the model labeled them.
    title = content.title.strip().lower()
    keep = [lines[i] for i in sorted(picked) if not lines[i].endswith(":") and lines[i].lower() != title]
    return [out for line in keep for out in _expand(line, llm)]


def _expand(line: str, llm: LocalLLM) -> list[str]:
    """A normal line passes through; a prose line becomes its ingredient lines."""
    if len(line) <= PROSE_LINE_CHARS:
        return [line]
    answer = llm.ask(line, _REWRITE_SCHEMA, system=_REWRITE_SYSTEM, max_tokens=512)
    rewritten = answer.get("ingredient_lines", [])
    if not isinstance(rewritten, list):
        return [line]
    return [r.strip() for r in rewritten if isinstance(r, str) and r.strip()] or [line]


def _pick(lines: list[str], llm: LocalLLM, system: str) -> set[int]:
    """Show `lines` numbered from 1; return the 0-based indices the LLM picks."""
    numbered = "\n".join(f"{n}: {line}" for n, line in enumerate(lines, 1))
    # ~2-4 tokens per picked number, plus headroom for the JSON wrapper and
    # whatever whitespace the model puts around it (too tight a cap truncates
    # the answer into invalid JSON).
    # Numbers limited to 1..N, at most N of them: without this a small model
    # can start counting ("1, 2, 3, … 19") past the end of a short text and
    # run out of tokens mid-answer.
    schema = {
        "type": "object",
        "properties": {
            "ingredient_lines": {
                "type": "array",
                "maxItems": len(lines),
                "items": {"type": "integer", "minimum": 1, "maximum": len(lines)},
            }
        },
        "required": ["ingredient_lines"],
    }
    answer = llm.ask(numbered, schema, system=system, max_tokens=4 * len(lines) + 64)
    numbers = answer.get("ingredient_lines", [])
    if not isinstance(numbers, list):
        return set()
    return {n - 1 for n in numbers if isinstance(n, int) and 1 <= n <= len(lines)}


def _clean_lines(text: str) -> list[str]:
    """Non-blank, bullet-stripped lines, capped so a huge page can't overflow the context."""
    lines, size = [], 0
    for raw in text.splitlines():
        line = raw.strip().lstrip("-*•·▢□").strip()
        if not line:
            continue
        size += len(line) + 1
        if size > config.LLM_MAX_INPUT_CHARS:
            break
        lines.append(line)
    return lines
