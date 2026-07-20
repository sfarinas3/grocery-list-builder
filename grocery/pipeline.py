"""The end-to-end extraction pipeline for one recipe URL.

Kept free of any UI so it stays testable and reusable (the Streamlit app and,
later, the aggregation step both call it). It simply chains the stages:

    fetch (ingest) -> ingredient lines (extract) -> parse (CRF) -> categorize
"""

from grocery.extract.base import Extractor
from grocery.extract.categorize import categorize
from grocery.extract.parse import parse_lines
from grocery.ingest import documents, web
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


def process_image(image: bytes, name: str, vision_extractor, *, categorizer=None) -> tuple[RecipeContent, list[Ingredient]]:
    """Process a recipe *photo* into categorized ingredients.

    `vision_extractor` reads the image into lines (see `extract/vision.py`); the
    rest is the same CRF parse + categorize as a URL recipe. `categorizer` is an
    optional text `Extractor` for the LLM category fallback — pass None to keep it
    lookup-only (avoids loading a second big model alongside the vision one).
    """
    lines = vision_extractor.ingredient_lines(image)
    ingredients = parse_lines(lines, source_url=None)
    categorize(ingredients, extractor=categorizer)
    return RecipeContent(title=name, ingredient_lines=lines), ingredients


def process_document(data: bytes, name: str, extractor: Extractor, *, use_llm: bool = True) -> tuple[RecipeContent, list[Ingredient]]:
    """Process a recipe *document* (PDF/Word/text) into categorized ingredients.

    Reads the document's text, then feeds it through the same raw-text path a
    non-JSON-LD web page uses: the model finds the ingredient lines, the CRF
    parses them, and they're categorized. Needs the model, so it's full-mode only.
    """
    text = documents.extract_text(data, name)
    content = RecipeContent(title=name, raw_text=text)
    lines = extractor.ingredient_lines(content)
    ingredients = parse_lines(lines, source_url=None)
    categorize(ingredients, extractor=extractor if use_llm else None)
    return content, ingredients
