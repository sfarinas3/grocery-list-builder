"""Recipe photo -> raw text, via local OCR. No model, no LLM.

Replaces the old vision-LLM path (`vision.py`, no longer wired into the app —
see docs/design.md) for reading recipe photos. Hands the raw OCR'd text to the
same heuristic line-finder (`extract/heuristic.py`) every other raw-text
source (non-JSON-LD pages, documents) already goes through, so a photo is just
another `raw_text` input to the rest of the pipeline.

Needs the `tesseract-ocr` system binary installed separately -- `pytesseract`
is just a thin wrapper around it (see README.md setup).
"""

import io


def extract_text(image: bytes) -> str:
    """OCR a recipe photo (raw image bytes) into readable text."""
    import pytesseract
    from PIL import Image

    return pytesseract.image_to_string(Image.open(io.BytesIO(image)))
