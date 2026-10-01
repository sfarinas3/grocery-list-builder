"""The local LLM — one small instruct model on CPU, via llama-cpp-python.

Used for three narrow jobs (docs/design.md §3.3), never for the quantity/unit
parse (the CRF in `extract/parse.py` is more reliable at that):
  - find the ingredient lines in messy text            (`extract/lines.py`)
  - double-check lines it may have dropped             (`extract/lines.py`)
  - give each ingredient a shopping name + aisle       (`extract/normalize.py`)

Every call is constrained to a JSON schema (grammar-forced decoding), so the
model physically cannot emit anything but the object we asked for — no prose,
always JSON-shaped. That's the key reliability lever for a small local model.
"""

import json
from collections.abc import Callable

from grocery import config
from grocery.extract import download


class LocalLLM:
    """Lazily-loaded local GGUF model with a single `ask()` entry point."""

    def __init__(self) -> None:
        self._llm = None  # a llama_cpp.Llama, loaded on first use

    def load(self, log: Callable[[str], None] = print) -> None:
        """Download the weights if missing, then load the model (once)."""
        if self._llm is not None:
            return
        if not config.LLM_PATH.exists():
            download.download(config.LLM_URL, config.LLM_PATH, log=log)
        log(f"Loading {config.LLM_FILE}…")
        from llama_cpp import Llama  # heavy import, deferred until actually needed

        self._llm = Llama(
            model_path=str(config.LLM_PATH),
            n_ctx=config.LLM_CONTEXT,
            n_threads=config.LLM_THREADS,
            verbose=False,
        )

    def ask(self, prompt: str, schema: dict, *, system: str = "", max_tokens: int = 1024) -> dict:
        """Run one schema-constrained chat completion and return the parsed object.

        Returns {} if the output isn't valid JSON — grammar keeps it JSON-shaped,
        but a long answer can be cut off at `max_tokens`. Callers treat {} as
        "the model gave us nothing usable" and fall back gracefully.
        """
        self.load()
        messages = ([{"role": "system", "content": system}] if system else []) + [
            {"role": "user", "content": prompt}
        ]
        response = self._llm.create_chat_completion(
            messages=messages,
            response_format={"type": "json_object", "schema": schema},
            temperature=0.0,  # deterministic — this is extraction, not creativity
            max_tokens=max_tokens,
        )
        try:
            result = json.loads(response["choices"][0]["message"]["content"])
        except json.JSONDecodeError:
            return {}
        return result if isinstance(result, dict) else {}
