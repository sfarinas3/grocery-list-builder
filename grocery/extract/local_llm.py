"""Local GGUF model backend for extracting ingredient lines (docs/design.md §3.3).

Its job is narrow: when a page has no JSON-LD, read the readable text and pull
out the ingredient lines. The hard structured parse is still the CRF's job
(`extract/parse.py`); this model only has to *find the lines*.

Output is constrained by a JSON schema (grammar-forced decoding), so the model
physically cannot emit anything but the object we asked for — no `<think>`
preamble, no prose, always-valid JSON. That's the key reliability lever for a
small local model.
"""

import json

from llama_cpp import Llama

from grocery import config
from grocery.extract import download
from grocery.models import RecipeContent

# The model must return exactly this shape. Constrained decoding guarantees it.
_SCHEMA = {
    "type": "object",
    "properties": {"ingredient_lines": {"type": "array", "items": {"type": "string"}}},
    "required": ["ingredient_lines"],
}

# Copy lines verbatim (Quality Check #1: don't drop or invent). "/no_think" tells
# Qwen to skip its thinking phase; the grammar enforces it regardless, but the
# hint avoids wasted work.
_SYSTEM_PROMPT = (
    "You extract the ingredient list from recipe text. Return every ingredient "
    "line, copied verbatim, in the JSON object {\"ingredient_lines\": [...]}. "
    "Include only ingredients — no step instructions, section headings, or "
    "commentary. Do not invent, merge, or drop lines. /no_think"
)


def _safe_json(raw: str) -> dict:
    """Parse model output as a JSON object, returning {} if it's malformed.

    Grammar-constrained decoding keeps output JSON-shaped, but a weak model can
    ramble past max_tokens and get truncated into invalid JSON. Callers treat {}
    as "the model gave us nothing usable" and degrade gracefully.
    """
    try:
        result = json.loads(raw)
        return result if isinstance(result, dict) else {}
    except json.JSONDecodeError:
        return {}


class LocalLLMExtractor:
    """`Extractor` backed by a local GGUF model (see `extract/base.py`)."""

    def __init__(self) -> None:
        self._llm: Llama | None = None  # loaded lazily on first model use

    def ingredient_lines(self, content: RecipeContent) -> list[str]:
        """Prefer JSON-LD lines (no model); otherwise extract them from raw text."""
        if content.ingredient_lines:
            return content.ingredient_lines
        text = content.raw_text.strip()
        if not text:
            return []
        return self._extract_from_text(text[: config.LLM_MAX_INPUT_CHARS])

    def _model(self) -> Llama:
        """Load the model on first use, downloading the weights if not present."""
        if self._llm is None:
            if not config.LLM_PATH.exists():
                download.download(config.LLM_URL, config.LLM_PATH)
            self._llm = Llama(
                model_path=str(config.LLM_PATH),
                n_ctx=config.LLM_CONTEXT,
                verbose=False,
            )
        return self._llm

    def _extract_from_text(self, text: str) -> list[str]:
        """Run the model with schema-constrained output and return clean lines."""
        response = self._model().create_chat_completion(
            messages=[
                {"role": "system", "content": _SYSTEM_PROMPT},
                {"role": "user", "content": text},
            ],
            response_format={"type": "json_object", "schema": _SCHEMA},
            temperature=0.0,        # deterministic — this is extraction, not creativity
            max_tokens=2048,
        )
        raw = response["choices"][0]["message"]["content"]
        # Grammar keeps the output JSON-shaped, but a weak model can ramble until
        # it's truncated at max_tokens, leaving incomplete JSON — degrade to "no
        # lines" rather than crashing the whole pipeline.
        lines = _safe_json(raw).get("ingredient_lines", [])
        return [line.strip() for line in lines if isinstance(line, str) and line.strip()]

    def categorize(self, names: list[str], categories: list[str]) -> dict[str, str]:
        """Assign each name to one of `categories` in a single grammar-bound call.

        The `enum` in the schema constrains the model to valid categories only,
        so it can never invent an aisle that isn't on the list.
        """
        if not names:
            return {}
        schema = {
            "type": "object",
            "properties": {
                "assignments": {
                    "type": "array",
                    "items": {
                        "type": "object",
                        "properties": {
                            "name": {"type": "string"},
                            "category": {"type": "string", "enum": categories},
                        },
                        "required": ["name", "category"],
                    },
                }
            },
            "required": ["assignments"],
        }
        prompt = (
            "Assign each grocery ingredient to exactly one shopping category from "
            f"this list: {', '.join(categories)}. Ingredients:\n"
            + "\n".join(f"- {n}" for n in names)
            + "\n/no_think"
        )
        response = self._model().create_chat_completion(
            messages=[{"role": "user", "content": prompt}],
            response_format={"type": "json_object", "schema": schema},
            temperature=0.0,
            max_tokens=1024,
        )
        raw = response["choices"][0]["message"]["content"]
        assignments = _safe_json(raw).get("assignments", [])
        return {a["name"]: a["category"] for a in assignments if "name" in a and "category" in a}
