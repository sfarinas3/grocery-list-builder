"""The end-to-end extraction pipeline for one recipe input.

Kept free of any UI so it stays testable and reusable (the Streamlit app and,
later, the aggregation step both call it). It simply chains the stages:

    fetch/read/OCR (ingest) -> ingredient lines (extract) -> parse (CRF) -> categorize

All three input types (URL, document, photo) end up as a `RecipeContent` fed
through the same `Extractor` (`extract/base.py`) — a photo's OCR'd text and a
non-JSON-LD page's readable text both go through the same heuristic
line-finder (`extract/heuristic.py`).
"""

from grocery.extract.base import Extractor
from grocery.extract.categorize import categorize
from grocery.extract.ocr import extract_text as ocr_text
from grocery.extract.parse import parse_lines
from grocery.ingest import documents, web
from grocery.models import Ingredient, RecipeContent


def process_url(url: str, extractor: Extractor) -> tuple[RecipeContent, list[Ingredient]]:
    """Fetch and fully process one recipe URL into categorized ingredients.

    May raise `web.IngestError` if the page yields nothing usable.
    """
    content = web.fetch(url)
    lines = extractor.ingredient_lines(content)
    ingredients = parse_lines(lines, source_url=content.source_url)
    categorize(ingredients, extractor=extractor)
    return content, ingredients


def process_image(image: bytes, name: str, extractor: Extractor) -> tuple[RecipeContent, list[Ingredient]]:
    """Process a recipe *photo* into categorized ingredients.

    OCRs the image to text, then runs the same raw-text path a non-JSON-LD web
    page or document uses.
    """
    content = RecipeContent(title=name, raw_text=ocr_text(image))
    lines = extractor.ingredient_lines(content)
    ingredients = parse_lines(lines, source_url=None)
    categorize(ingredients, extractor=extractor)
    return content, ingredients


def process_document(data: bytes, name: str, extractor: Extractor) -> tuple[RecipeContent, list[Ingredient]]:
    """Process a recipe *document* (PDF/Word/text) into categorized ingredients."""
    content = RecipeContent(title=name, raw_text=documents.extract_text(data, name))
    lines = extractor.ingredient_lines(content)
    ingredients = parse_lines(lines, source_url=None)
    categorize(ingredients, extractor=extractor)
    return content, ingredients
