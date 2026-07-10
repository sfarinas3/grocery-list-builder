"""The swappable extraction-backend seam (docs/design.md §3.3).

An `Extractor` turns a fetched `RecipeContent` into a flat list of ingredient
lines. Keeping this behind a `Protocol` means the rest of the pipeline never
knows *how* the lines were obtained — a different local model, or a hosted model
later, is a drop-in replacement chosen in config, with no other code changes.

Downstream, each returned line goes to the CRF parser (`extract/parse.py`).
"""

from typing import Protocol

from grocery.models import RecipeContent


class Extractor(Protocol):
    """Produce a recipe's ingredient lines from its fetched content."""

    def ingredient_lines(self, content: RecipeContent) -> list[str]:
        """Return the ingredient lines for `content`.

        Implementations should prefer `content.ingredient_lines` when present
        (the JSON-LD path — clean, no model needed) and only fall back to
        reading `content.raw_text` with a model when it isn't.
        """
        ...

    def categorize(self, names: list[str], categories: list[str]) -> dict[str, str]:
        """Map each ingredient name to one of `categories`.

        The categorizer (`extract/categorize.py`) calls this only for names its
        keyword lookup couldn't place, in a single batched request.
        """
        ...
