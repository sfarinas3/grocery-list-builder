"""Model-free extraction backend — the only one this app uses.

Handles both extraction paths without a model:
  - JSON-LD path: `content.ingredient_lines` is already clean, nothing to do.
  - Raw-text fallback (non-JSON-LD pages, documents, OCR'd photos): a
    heuristic (`extract/heuristic.py`) finds candidate ingredient lines by
    pattern, not a trained model — see that module for the approach and its
    limits.

Implements the same `Extractor` protocol as `LocalLLMExtractor` (`local_llm.py`
— not currently wired into the app, parked for a possible future desktop
build with a real local LLM; see docs/design.md). Categorization stays
lookup-table-only (`extract/categorize.py`); nothing here falls back to a
model for unknowns.
"""

from grocery.extract.heuristic import find_candidate_lines
from grocery.models import RecipeContent


class LiteExtractor:
    """An `Extractor` (see `base.py`) that uses no model at all."""

    def ingredient_lines(self, content: RecipeContent) -> list[str]:
        """Prefer JSON-LD lines; otherwise find candidates in raw text heuristically."""
        if content.ingredient_lines:
            return list(content.ingredient_lines)
        return find_candidate_lines(content.raw_text)

    def categorize(self, names: list[str], categories: list[str]) -> dict[str, str]:
        """No model → no fallback categorization; the lookup table already ran."""
        return {}
