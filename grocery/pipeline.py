"""The end-to-end pipeline, kept free of any UI so it stays testable.

Per recipe (one call per URL / document / photo / pasted text):

    ingest (fetch / read / OCR) -> ingredient lines (LLM) -> parse (CRF)

Then once for the whole batch, so the LLM sees every recipe's names together:

    normalize (LLM: shopping name + aisle) -> combine -> GroceryList

`llm` may be None (model failed to download/load): every step then falls back
to its model-free path — see `extract/lines.py` and `extract/normalize.py`.
"""

from grocery.aggregate.combine import combine
from grocery.extract.lines import ingredient_lines
from grocery.extract.llm import LocalLLM
from grocery.extract.normalize import normalize
from grocery.extract.ocr import extract_text as ocr_text
from grocery.extract.parse import parse_lines
from grocery.ingest import documents, web
from grocery.models import GroceryList, Ingredient, RecipeContent

Recipe = tuple[RecipeContent, list[Ingredient]]


def process_url(url: str, llm: LocalLLM | None) -> Recipe:
    """Fetch one recipe URL. May raise `web.IngestError` if the page yields nothing usable."""
    return _process(web.fetch(url), llm)


def process_document(data: bytes, name: str, llm: LocalLLM | None) -> Recipe:
    """Process a recipe document (PDF/Word/text)."""
    return _process(RecipeContent(title=name, raw_text=documents.extract_text(data, name)), llm)


def process_image(image: bytes, name: str, llm: LocalLLM | None) -> Recipe:
    """Process a recipe photo: OCR it, then treat it like any other raw text."""
    return _process(RecipeContent(title=name, raw_text=ocr_text(image)), llm)


def process_text(text: str, name: str, llm: LocalLLM | None) -> Recipe:
    """Process recipe text pasted by hand (e.g. from a site that blocks fetching)."""
    return _process(RecipeContent(title=name, raw_text=text), llm)


def build_list(recipes: list[Recipe], llm: LocalLLM | None) -> GroceryList:
    """Normalize every recipe's ingredients in one batch, then merge into a list."""
    normalize([ing for _, ingredients in recipes for ing in ingredients], llm)
    return combine([(recipe_label(content), ingredients) for content, ingredients in recipes])


def recipe_label(content: RecipeContent) -> str:
    """A human-readable name for a recipe: its title, else its URL."""
    return content.title or content.source_url or "Recipe"


def _process(content: RecipeContent, llm: LocalLLM | None) -> Recipe:
    """Shared tail of every input type: find the lines, then CRF-parse them."""
    lines = ingredient_lines(content, llm)
    return content, parse_lines(lines, source_url=content.source_url)
