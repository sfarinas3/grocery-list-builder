"""Keyword lookup: ingredient name -> shopping aisle (docs/design.md §3.3).

The first-choice categorizer for `extract/normalize.py`; the LLM only sees
names this table doesn't recognize. Free, instant, and on the eval set more
accurate than a small LLM at picking aisles — but it only knows the words
below, and it's naive about compounds unless they're listed (longest keyword
wins, so "peanut butter" must be listed or it matches "butter").
"""

import re


# The tweakable knob: which words map an ingredient to which aisle. Keywords are
# matched whole-word and longest-first, so "black pepper" (spices) wins over
# "pepper", and "olive oil" (condiments) wins over "oil". Add your own staples
# freely — this table only needs to cover the common case for when the LLM
# is unavailable; human review handles the rest.
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
        "peanut butter",
    ],
    "canned & jarred": [
        "canned", "crushed tomatoes", "diced tomatoes", "tomato paste",
        "tomato sauce", "broth", "stock", "coconut milk",
        # compounds that would otherwise match a shorter, wrong keyword
        # ("chicken" -> meat), since longer keywords win:
        "chicken broth", "chicken stock", "beef broth", "beef stock",
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
