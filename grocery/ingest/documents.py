"""Extract text from an uploaded recipe document (PDF, Word, or plain text).

Just the text step — turning that text into ingredient lines is the local LLM's
job (the same raw-text path as a non-JSON-LD web page), so documents are a
full-mode feature. The heavy parsers (`pypdf`, `python-docx`) are imported lazily
inside the functions so this module is safe to import even in lite mode, where
they aren't installed.
"""

import io
from pathlib import Path


def extract_text(data: bytes, filename: str) -> str:
    """Return the text content of a document, dispatched by file extension."""
    ext = Path(filename).suffix.lower()
    if ext == ".pdf":
        return _pdf_text(data)
    if ext == ".docx":
        return _docx_text(data)
    # .txt, .md, or anything else: best-effort decode as UTF-8.
    return data.decode("utf-8", errors="replace")


def _pdf_text(data: bytes) -> str:
    from pypdf import PdfReader

    reader = PdfReader(io.BytesIO(data))
    return "\n".join((page.extract_text() or "") for page in reader.pages)


def _docx_text(data: bytes) -> str:
    from docx import Document

    document = Document(io.BytesIO(data))
    return "\n".join(paragraph.text for paragraph in document.paragraphs)
