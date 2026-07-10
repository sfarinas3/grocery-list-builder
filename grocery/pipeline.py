"""The end-to-end extraction pipeline for one recipe URL.

Kept free of any UI so it stays testable and reusable (the Streamlit app and,
later, the aggregation step both call it). It simply chains the stages:

    fetch (ingest) -> ingredient lines (extract) -> parse (CRF) -> categorize
"""

from grocery.extract.base import Extractor
from grocery.extract.categorize import categorize
from grocery.extract.parse import parse_lines
from grocery.ingest import web
from grocery.models import Ingredient, RecipeContent


def process_url(url: str, extractor: Extractor, *, use_llm: bool = True) -> tuple[RecipeContent, list[Ingredient]]:
    """Fetch and fully process one recipe URL into categorized ingredients.

    `use_llm` only controls the *categorization* fallback; line extraction still
    uses the model when a page has no structured data (that's not optional). May
    raise `web.IngestError` if the page yields nothing usable.
    """
    content = web.fetch(url)
    lines = extractor.ingredient_lines(content)
    ingredients = parse_lines(lines, source_url=content.source_url)
    categorize(ingredients, extractor=extractor if use_llm else None)
    return content, ingredients
