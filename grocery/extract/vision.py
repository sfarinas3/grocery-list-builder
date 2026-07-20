"""Recipe photo -> ingredient lines, via a local vision model (Approach B).

Uses Qwen2.5-VL (a multimodal GGUF) through llama-cpp-python's Qwen25VLChatHandler:
the model reads the image and returns ingredient lines as grammar-constrained
JSON, which then flow into the same parse -> categorize -> merge pipeline as a
recipe from a URL. Weights (model + `mmproj` projector) download from ModelScope
on first use.

CPU-only vision inference is slow — expect the one-time model load plus the first
image to take a while on a machine without a GPU.
"""

import base64
import io
import json

from llama_cpp import Llama
from llama_cpp.llama_chat_format import Qwen25VLChatHandler

from grocery import config
from grocery.extract import download

# Same shape as the text backend, so grammar-constrained decoding guarantees a
# parseable object (and no chatty preamble from the model).
_SCHEMA = {
    "type": "object",
    "properties": {"ingredient_lines": {"type": "array", "items": {"type": "string"}}},
    "required": ["ingredient_lines"],
}

_PROMPT = (
    "This image shows a recipe. Read it and return every ingredient line exactly "
    'as written, in the JSON object {"ingredient_lines": [...]}. Include only the '
    "ingredients — not the title, step instructions, or commentary."
)


def _safe_json(raw: str) -> dict:
    try:
        result = json.loads(raw)
        return result if isinstance(result, dict) else {}
    except json.JSONDecodeError:
        return {}


def _to_jpeg_data_uri(image: bytes, max_side: int = 1024) -> str:
    """Downscale (bounds the image-token count and CPU cost) and base64-encode.

    Pillow ships with Streamlit, so it's available; if anything goes wrong we
    fall back to sending the original bytes untouched.
    """
    try:
        from PIL import Image

        img = Image.open(io.BytesIO(image)).convert("RGB")
        scale = min(1.0, max_side / max(img.size))
        if scale < 1.0:
            img = img.resize((int(img.width * scale), int(img.height * scale)))
        buffer = io.BytesIO()
        img.save(buffer, format="JPEG", quality=90)
        image = buffer.getvalue()
    except Exception:  # noqa: BLE001 — best effort; send original if resize fails
        pass
    return "data:image/jpeg;base64," + base64.b64encode(image).decode()


class VisionExtractor:
    """Reads recipe photos into ingredient lines with a local Qwen2.5-VL model."""

    def __init__(self) -> None:
        self._llm: Llama | None = None

    def _model(self) -> Llama:
        """Load the vision model on first use, downloading weights if needed."""
        if self._llm is None:
            if not config.VISION_PATH.exists():
                download.download(config.VISION_URL, config.VISION_PATH)
            if not config.VISION_MMPROJ_PATH.exists():
                download.download(config.VISION_MMPROJ_URL, config.VISION_MMPROJ_PATH)
            handler = Qwen25VLChatHandler(clip_model_path=str(config.VISION_MMPROJ_PATH), verbose=False)
            self._llm = Llama(
                model_path=str(config.VISION_PATH),
                chat_handler=handler,
                n_ctx=config.VISION_CONTEXT,
                verbose=False,
            )
        return self._llm

    def ingredient_lines(self, image: bytes) -> list[str]:
        """Extract ingredient lines from a recipe photo (raw image bytes)."""
        response = self._model().create_chat_completion(
            messages=[
                {
                    "role": "user",
                    "content": [
                        {"type": "image_url", "image_url": {"url": _to_jpeg_data_uri(image)}},
                        {"type": "text", "text": _PROMPT},
                    ],
                }
            ],
            response_format={"type": "json_object", "schema": _SCHEMA},
            temperature=0.0,
            max_tokens=1024,
        )
        raw = response["choices"][0]["message"]["content"]
        lines = _safe_json(raw).get("ingredient_lines", [])
        return [line.strip() for line in lines if isinstance(line, str) and line.strip()]
