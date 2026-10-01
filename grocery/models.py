"""Shared data contract for the whole pipeline.

Every stage speaks in terms of these models, so getting them right is what keeps
the stages decoupled (see docs/design.md §5). The flow they travel through:

    ingest          -> RecipeContent   (a fetched recipe: raw lines and/or text)
    lines + parse   -> Ingredient[]    (one structured item per ingredient line)
    normalize       -> Ingredient[]    (+ shopping name and aisle, via the LLM)
    aggregate       -> GroceryList     (merged across recipes, grouped for shopping)

These are pydantic models: they validate types on creation and give us clean
`.model_dump()` / `.model_validate()` for free (handy for caching and the UI).
"""

from pydantic import BaseModel, Field

# Default aisle for an item we haven't categorized yet. The canonical list of
# shopping categories lives in config.py; Quality Check #2 flags anything outside it.
DEFAULT_CATEGORY = "uncategorized"


class RecipeContent(BaseModel):
    """One recipe as it comes out of the ingest stage, before any parsing.

    Populated one of two ways (docs/design.md §3.1):
      - JSON-LD path  -> `ingredient_lines` filled with clean lines, `raw_text` empty.
      - raw text      -> `raw_text` filled with page/document/OCR text; the local
        LLM later pulls the lines out of it (`extract/lines.py`).
    """

    source_url: str | None = None          # where it came from (None if pasted by hand)
    title: str = ""
    servings: int | None = None            # best-effort; None when the page doesn't say
    ingredient_lines: list[str] = Field(default_factory=list)  # raw, one line per ingredient
    raw_text: str = ""                     # full readable text, used only on the fallback path


class Ingredient(BaseModel):
    """A single parsed, categorized ingredient from one recipe.

    `ingredient-parser` (CRF) splits a line into quantity/unit/name/comment;
    `extract/normalize.py` then sets `shopping_name` and `category`.
    `quantity` is intentionally NOT bounded to be positive here — a bad parse can
    produce a negative or zero value, and we want it to flow through so Quality
    Check #2 can flag it rather than crash on construction.
    """

    name: str                              # ingredient name as the recipe wrote it, e.g. "yellow onions"
    shopping_name: str = ""                # what to buy, e.g. "onion" — the merge key; "" until normalized
    quantity: float | None = None          # e.g. 2.0; None for "to taste" / unquantified
    unit: str | None = None                # e.g. "clove", "cup"; None when there is no unit
    category: str = DEFAULT_CATEGORY       # shopping aisle, e.g. "produce" (see config.py)
    notes: str = ""                        # the CRF "comment", e.g. "minced", "finely chopped"
    source_url: str | None = None          # the recipe this came from

    # Set by the quality checks (docs/design.md §4):
    confidence: float = Field(default=1.0, ge=0.0, le=1.0)  # Check #1 lowers this when unsure
    flags: list[str] = Field(default_factory=list)          # e.g. "low_confidence", "absurd_quantity"


class GroceryItem(BaseModel):
    """One line on the final grocery list — an Ingredient merged across recipes.

    Differs from Ingredient in two ways: it can come from several recipes
    (`sources`), and it carries UI state (`checked`).
    """

    name: str
    quantity: float | None = None
    unit: str | None = None
    category: str = DEFAULT_CATEGORY
    notes: str = ""                        # merged prep notes / mixed-unit amount breakdown
    checked: bool = False                  # ticked off in the UI while shopping
    sources: list[str] = Field(default_factory=list)  # names of recipes contributing to this item
    flags: list[str] = Field(default_factory=list)    # surfaced in the UI for review


class GroceryList(BaseModel):
    """The assembled list the user shops from."""

    items: list[GroceryItem] = Field(default_factory=list)

    @property
    def by_category(self) -> dict[str, list[GroceryItem]]:
        """Group items by shopping category for a shopping-friendly display.

        Computed on access (not stored) so it can never drift out of sync with
        `items`. Insertion order of categories follows first appearance in `items`.
        """
        grouped: dict[str, list[GroceryItem]] = {}
        for item in self.items:
            grouped.setdefault(item.category, []).append(item)
        return grouped
