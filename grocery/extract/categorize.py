"""Assign each ingredient a shopping aisle (docs/design.md §3.3).

A keyword lookup (below) — free, instant, no model — handles the common case.
`categorize()` also accepts an `Extractor` for a model-backed fallback on
whatever the lookup misses (a hook `local_llm.py`'s dormant backend uses, see
docs/design.md §3.3); the live `LiteExtractor` implements that hook as a
no-op, so categorization is lookup-only in the app today.

Anything still unassigned keeps `DEFAULT_CATEGORY` and gets a "needs_category"
flag so the UI surfaces it for review (Quality Check #3).
"""

import re

from grocery import config
from grocery.extract.base import Extractor
from grocery.models import Ingredient

# The tweakable knob: which words map an ingredient to which aisle. Keywords are
# matched whole-word and longest-first, so "black pepper" (spices) wins over
# "pepper", and "olive oil" (condiments) wins over "oil". Add your own staples
# freely — this table only needs to cover the common case; the LLM fallback and
# human review handle the rest.
_CATEGORY_KEYWORDS: dict[str, list[str]] = {
    "produce": [
        "garlic", "onion", "scallion", "green onion", "shallot", "tomato", "lettuce",
        "carrot", "potato", "bell pepper", "jalapeno", "spinach", "kale", "lemon",
        "lime", "apple", "banana", "avocado", "celery", "cucumber", "mushroom",
        "broccoli", "cauliflower", "ginger", "parsley", "cilantro", "basil",
        "zucchini", "corn", "cabbage", "eggplant", "sweet potato", "lime juice",
        "lemon juice",
    ],
    "meat & seafood": [
        "chicken", "beef", "ground beef", "pork", "bacon", "sausage", "turkey",
        "lamb", "shrimp", "salmon", "fish", "tuna", "steak", "ham", "prosciutto",
    ],
    "dairy & eggs": [
        "milk", "butter", "cheese", "egg", "eggs", "cream", "heavy cream", "yogurt",
        "parmesan", "mozzarella", "cheddar", "feta", "sour cream", "cream cheese",
    ],
    "bakery": ["bread", "tortilla", "bun", "baguette", "roll", "pita", "naan"],
    "pantry": [
        "flour", "all-purpose flour", "rice", "pasta", "spaghetti", "noodle",
        "oats", "quinoa", "lentil", "bean", "chickpea", "breadcrumb", "cornmeal",
    ],
    "canned & jarred": [
        "canned", "crushed tomatoes", "diced tomatoes", "tomato paste",
        "tomato sauce", "broth", "stock", "coconut milk",
    ],
    "condiments & sauces": [
        "oil", "olive oil", "vegetable oil", "sesame oil", "soy sauce", "vinegar",
        "ketchup", "mustard", "mayonnaise", "mayo", "honey", "oyster sauce",
        "hot sauce", "fish sauce", "worcestershire", "salsa", "syrup", "maple syrup",
    ],
    "spices & seasonings": [
        "salt", "pepper", "black pepper", "red pepper", "cumin", "paprika",
        "cinnamon", "oregano", "thyme", "rosemary", "chili", "chili powder",
        "garlic powder", "onion powder", "bay leaf", "nutmeg", "turmeric", "curry",
        "cayenne", "seasoning", "red pepper flakes",
    ],
    "baking": [
        "sugar", "brown sugar", "powdered sugar", "baking powder", "baking soda",
        "yeast", "vanilla", "cocoa", "chocolate", "chocolate chip", "cornstarch",
    ],
    "beverages": ["wine", "juice", "coffee", "tea", "beer"],
}

# Flattened to (keyword, category), longest keyword first for specific-match wins.
_LOOKUP: list[tuple[str, str]] = sorted(
    ((kw, cat) for cat, kws in _CATEGORY_KEYWORDS.items() for kw in kws),
    key=lambda pair: len(pair[0]),
    reverse=True,
)


def lookup_category(name: str) -> str | None:
    """Return the aisle for an ingredient name via the keyword table, or None.

    Tolerates a trailing "s"/"es" on the keyword so plurals match too (e.g.
    "green onions" still matches the "green onion" keyword, "beans" matches
    "bean") — a bare `\\bkeyword\\b` fails on these since there's no word
    boundary between the keyword and a trailing plural suffix.
    """
    text = name.lower()
    for keyword, category in _LOOKUP:
        if re.search(rf"\b{re.escape(keyword)}(?:es|s)?\b", text):
            return category
    return None


def categorize(ingredients: list[Ingredient], *, extractor: Extractor | None = None) -> list[Ingredient]:
    """Set `category` on each ingredient in place, then return the list.

    Lookup handles the common case for free. Leftovers go to
    `extractor.categorize()` — a no-op for the live `LiteExtractor`, so they
    just get flagged for review; a model-backed `Extractor` could place more
    of them here instead.
    """
    unresolved: list[Ingredient] = []
    for ing in ingredients:
        category = lookup_category(ing.name)
        if category:
            ing.category = category
        else:
            unresolved.append(ing)

    if unresolved and extractor is not None:
        # One call for all unique unknown names, constrained to valid categories.
        names = list({ing.name for ing in unresolved})
        mapping = extractor.categorize(names, config.CATEGORIES)
        for ing in unresolved:
            category = mapping.get(ing.name)
            if category in config.CATEGORIES:
                ing.category = category

    # Anything still unassigned (lookup missed it and the LLM didn't place it)
    # keeps the default sentinel — flag it for human review.
    for ing in unresolved:
        if ing.category not in config.CATEGORIES:
            ing.flags.append("needs_category")

    return ingredients
