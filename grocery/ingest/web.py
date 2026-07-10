"""Ingest stage: fetch a recipe URL and turn it into a `RecipeContent`.

Two-tier strategy (docs/design.md §3.1), best case first:

  1. schema.org/Recipe JSON-LD embedded in the page  -> clean ingredient lines,
     no model needed. ~70% of recipe sites provide this.
  2. Readable main text (via trafilatura) when there's no JSON-LD -> the local
     LLM pulls the lines out later.

On total failure we raise `IngestError` with a human-readable message so the UI
can tell the user to paste the recipe manually, rather than failing silently.
"""

import html
import json
import re
import ssl
from html.parser import HTMLParser

import httpx
import trafilatura
import truststore

from grocery.models import RecipeContent

# This machine sits behind a TLS-inspecting corporate proxy, so httpx's default
# (certifi) CA bundle rejects the proxy's certificate. `truststore` verifies
# against the OS trust store instead — the same store that already trusts the
# corporate root CA — so verification stays ON (never disable it). Built once
# and reused across requests.
_SSL_CONTEXT = truststore.SSLContext(ssl.PROTOCOL_TLS_CLIENT)

# A browser-ish User-Agent reduces trivial bot blocks. Not a guarantee — some
# sites use Cloudflare/JS rendering we can't get past; those fall to the caller.
_HEADERS = {
    "User-Agent": (
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
        "(KHTML, like Gecko) Chrome/124.0 Safari/537.36"
    )
}


class IngestError(Exception):
    """Raised when we can't get usable recipe content from a URL."""


def fetch(url: str, *, timeout: float = 15.0) -> RecipeContent:
    """Fetch `url` and return its `RecipeContent`. Raises `IngestError` on failure."""
    html = _fetch_html(url, timeout)

    # Tier 1: structured data. Only trust it if it actually yields ingredient lines.
    recipe = _extract_jsonld_recipe(html)
    if recipe:
        lines = _clean_lines(recipe.get("recipeIngredient"))
        if lines:
            return RecipeContent(
                source_url=url,
                title=_as_text(recipe.get("name")),
                servings=_parse_servings(recipe.get("recipeYield")),
                ingredient_lines=lines,
            )

    # Tier 2: readable text fallback. Reuse the HTML we already fetched.
    text = (trafilatura.extract(html) or "").strip()
    if text:
        return RecipeContent(source_url=url, raw_text=text)

    raise IngestError(
        f"Could not extract recipe content from {url}. The page may block "
        "scraping or render with JavaScript — try pasting the recipe manually."
    )


# --- Fetching -------------------------------------------------------------

def _fetch_html(url: str, timeout: float) -> str:
    """GET the page as text, following redirects; wrap network errors."""
    try:
        resp = httpx.get(
            url, headers=_HEADERS, timeout=timeout, follow_redirects=True, verify=_SSL_CONTEXT
        )
        resp.raise_for_status()
    except httpx.HTTPError as exc:
        raise IngestError(f"Failed to fetch {url}: {exc}") from exc
    return resp.text


# --- JSON-LD extraction ---------------------------------------------------

class _LDJSONExtractor(HTMLParser):
    """Collects the text of every <script type="application/ld+json"> block.

    HTMLParser treats <script> content as CDATA, so `handle_data` receives the
    raw JSON text between the tags — no need to unescape entities.
    """

    def __init__(self) -> None:
        super().__init__()
        self.blocks: list[str] = []
        self._collecting = False
        self._buf: list[str] = []

    def handle_starttag(self, tag: str, attrs: list[tuple[str, str | None]]) -> None:
        if tag == "script" and dict(attrs).get("type", "").strip().lower() == "application/ld+json":
            self._collecting = True
            self._buf = []

    def handle_data(self, data: str) -> None:
        if self._collecting:
            self._buf.append(data)

    def handle_endtag(self, tag: str) -> None:
        if tag == "script" and self._collecting:
            self._collecting = False
            self.blocks.append("".join(self._buf))


def _extract_jsonld_recipe(html: str) -> dict | None:
    """Return the first schema.org Recipe node found in the page's JSON-LD."""
    parser = _LDJSONExtractor()
    parser.feed(html)
    for block in parser.blocks:
        try:
            data = json.loads(block)
        except json.JSONDecodeError:
            continue  # some sites ship malformed JSON-LD; just skip it
        recipe = _find_recipe(data)
        if recipe is not None:
            return recipe
    return None


def _find_recipe(node: object) -> dict | None:
    """Recursively search a parsed JSON-LD value for a node typed "Recipe".

    Handles the three shapes sites use: a bare object, a list of objects, or an
    object wrapping the list in "@graph".
    """
    if isinstance(node, list):
        for item in node:
            found = _find_recipe(item)
            if found is not None:
                return found
    elif isinstance(node, dict):
        node_type = node.get("@type")
        types = node_type if isinstance(node_type, list) else [node_type]
        if any(isinstance(t, str) and t.lower() == "recipe" for t in types):
            return node
        if "@graph" in node:
            return _find_recipe(node["@graph"])
    return None


# --- Field normalization --------------------------------------------------

def _clean_lines(value: object) -> list[str]:
    """`recipeIngredient` -> a list of non-empty, stripped strings.

    Unescapes HTML entities: WordPress/Yoast HTML-encode inside JSON-LD, so a
    line can arrive as "salt &amp; pepper" or "&#189; cup" — html.unescape turns
    those back into "&" and "½" before the CRF parser ever sees them.
    """
    if isinstance(value, str):
        value = [value]
    if not isinstance(value, list):
        return []
    cleaned = (html.unescape(item).strip() for item in value if isinstance(item, str))
    return [line for line in cleaned if line]


def _as_text(value: object) -> str:
    """Coerce a JSON-LD field to a trimmed, entity-unescaped string."""
    return html.unescape(value).strip() if isinstance(value, str) else ""


def _parse_servings(value: object) -> int | None:
    """`recipeYield` is a mess across sites ("4", "4 servings", ["6"], 4) -> int."""
    if isinstance(value, list):
        value = value[0] if value else None
    if isinstance(value, bool):  # guard: bool is an int subclass
        return None
    if isinstance(value, (int, float)):
        return int(value)
    if isinstance(value, str):
        match = re.search(r"\d+", value)
        return int(match.group()) if match else None
    return None
