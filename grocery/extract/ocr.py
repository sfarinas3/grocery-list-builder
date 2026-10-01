"""Recipe photo -> raw text, via local Tesseract OCR.

The OCR'd text is then just another `raw_text` input: the local LLM pulls the
ingredient lines out of it (`extract/lines.py`) and is told to fix obvious OCR
typos while it's at it.

Needs the `tesseract-ocr` system binary — `pytesseract` is only a thin wrapper
around it (see README.md setup). The Windows installer doesn't add it to PATH,
so its default install location is checked too.
"""

import io
import shutil
from pathlib import Path

_WINDOWS_DEFAULT = Path(r"C:\Program Files\Tesseract-OCR\tesseract.exe")


class OCRUnavailable(RuntimeError):
    """Tesseract isn't installed, so photos can't be read."""


def extract_text(image: bytes) -> str:
    """OCR a recipe photo (raw image bytes) into readable text."""
    import pytesseract
    from PIL import Image

    if not shutil.which("tesseract"):
        if not _WINDOWS_DEFAULT.exists():
            raise OCRUnavailable("Tesseract OCR isn't installed — see README.md to enable photos.")
        pytesseract.pytesseract.tesseract_cmd = str(_WINDOWS_DEFAULT)
    return pytesseract.image_to_string(Image.open(io.BytesIO(image)))
