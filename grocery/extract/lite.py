"""Model-free extraction backend.

Used where the local LLM can't run — e.g. the free Streamlit Community Cloud
deploy, which doesn't have the RAM for the model. It handles the JSON-LD path
(clean structured recipes) and simply declines what the model would do: pages
with no structured data yield no lines, and unknown-ingredient categorization is
left to the deterministic lookup table (`extract/categorize.py`).

Implements the same `Extractor` protocol as `LocalLLMExtractor`, so the rest of
the pipeline doesn't know or care which backend is running. Crucially, this
module imports no model libraries, so it works in an environment where
`llama-cpp-python` isn't installed.
"""

from grocery.models import RecipeContent


class LiteExtractor:
    """An `Extractor` (see `base.py`) that uses no model at all."""

    def ingredient_lines(self, content: RecipeContent) -> list[str]:
        """Only the structured-data path works without a model."""
        return list(content.ingredient_lines)

    def categorize(self, names: list[str], categories: list[str]) -> dict[str, str]:
        """No model → no fallback categorization; the lookup table already ran."""
        return {}
