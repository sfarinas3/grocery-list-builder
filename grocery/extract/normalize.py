"""Give each ingredient a shopping name and an aisle (docs/design.md §3.3).

Pipeline position: ingest -> lines -> parse (CRF) -> **normalize** -> aggregate.

Two tiers, cheapest and most accurate first — chosen by measurement
(`eval/run.py`), not assumption:

  0. Synonyms (`config.SYNONYMS`): "scallions" -> "green onion", so common
     alternate names merge on the list without needing the model.
  1. Keyword table (`extract/categorize.py`): if a name matches a known
     ingredient, that's its aisle and the CRF name is already a fine shopping
     name. Instant, and on the eval set far more accurate than a small LLM at
     picking aisles (88% vs ~60%).
  2. The LLM, for everything the table doesn't know — unusual ingredients
     (tahini, gochujang), OCR typos ("garIic"), regional names ("spring
     onions") — in ONE batched call. It returns a cleaned-up shopping name and
     an aisle; the name goes back through the table first, so "garIic" ->
     "garlic" -> produce without trusting the model's aisle.

The output schema pins the model's answer to one entry per name, in order,
with the aisle restricted to `config.CATEGORIES`. Anything still unplaced is
flagged "needs_category" for human review.
"""

from grocery import config
from grocery.extract.categorize import lookup_category
from grocery.extract.llm import LocalLLM
from grocery.models import Ingredient

# Tweakable: how the model should name and file items. The worked examples
# matter more than the rules for a small model — add one when you spot a
# recurring mistake.
_SYSTEM = """\
You turn recipe ingredient names into a grocery shopping list. For each \
ingredient give [product, aisle]. The product is what you'd look for in the \
store: lowercase, singular, typos fixed, preparation/size/freshness words \
removed, and the common name when several names mean the same product. Keep \
words that change what you buy (red onion, brown sugar, unsalted butter).
Aisles: {categories}.
Examples:
1. large carrots -> ["carrot", "produce"]
2. fresh flat-leaf parsley -> ["parsley", "produce"]
3. spring onions -> ["green onion", "produce"]
4. tomatoe paste -> ["tomato paste", "canned & jarred"]
5. grated parmigiano reggiano -> ["parmesan cheese", "dairy & eggs"]
6. gochujang -> ["gochujang", "condiments & sauces"]
7. sumac -> ["sumac", "spices & seasonings"]"""


def normalize(ingredients: list[Ingredient], llm: LocalLLM | None) -> list[Ingredient]:
    """Set `shopping_name` and `category` on each ingredient in place; return the list."""
    for ing in ingredients:
        ing.shopping_name = _synonym(ing.name)
    unknown = list(dict.fromkeys(ing.shopping_name for ing in ingredients if not lookup_category(ing.shopping_name)))
    answers = _ask(unknown, llm) if llm is not None and unknown else {}

    for ing in ingredients:
        shopping_name, model_aisle = answers.get(ing.shopping_name, (ing.shopping_name, None))
        ing.shopping_name = _synonym(shopping_name)
        # The table wins whenever it recognizes the (possibly model-cleaned)
        # name; the model's aisle only fills the gaps.
        ing.category = lookup_category(shopping_name) or model_aisle or ing.category
        if ing.category not in config.CATEGORIES and "needs_category" not in ing.flags:
            ing.flags.append("needs_category")
    return ingredients


def _synonym(name: str) -> str:
    """Map an alternate name to its common one (config.SYNONYMS), else return it unchanged."""
    key = name.strip().lower()
    return config.SYNONYMS.get(key) or config.SYNONYMS.get(key.removesuffix("s")) or name


def _ask(names: list[str], llm: LocalLLM) -> dict[str, tuple[str, str | None]]:
    """One LLM call: {original name: (shopping name, category or None)}."""
    schema = {
        "type": "object",
        "properties": {
            "items": {
                "type": "array",
                "minItems": len(names),
                "maxItems": len(names),
                # [product, aisle] pairs rather than {"product": ..., "aisle": ...}
                # objects: same information, about half the output tokens.
                "items": {
                    "type": "array",
                    "prefixItems": [
                        {"type": "string"},
                        {"type": "string", "enum": config.CATEGORIES},
                    ],
                    "items": False,
                },
            }
        },
        "required": ["items"],
    }
    system = _SYSTEM.format(categories=", ".join(config.CATEGORIES))
    prompt = "One entry per ingredient, in order:\n" + "\n".join(
        f"{i}. {name}" for i, name in enumerate(names, 1)
    )
    items = llm.ask(prompt, schema, system=system, max_tokens=24 * len(names) + 64).get("items", [])
    if not isinstance(items, list) or len(items) != len(names):
        return {}  # truncated or malformed — can't trust the order, use the fallback

    answers = {}
    for name, item in zip(names, items):
        product, aisle = item if isinstance(item, list) and len(item) == 2 else ("", None)
        product = str(product).strip().lower()
        answers[name] = (product or name, aisle if aisle in config.CATEGORIES else None)
    return answers
