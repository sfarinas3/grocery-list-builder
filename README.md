# Grocery List Builder Bot

Turn recipe web links, documents, or photos into a consolidated, editable
grocery list — runs fully **locally**, no cloud API, no LLM, no key required.

See [docs/design.md](docs/design.md) for the design and [docs/design-principles.md](docs/design-principles.md)
for the coding principles all code here follows.

## Pipeline

```
URL / document / photo → ingest → find ingredient lines (heuristic / OCR) → parse (CRF) → categorize → aggregate → display/edit
```

No model of any kind sits in this path: `ingredient-parser` is a small
trained CRF (not an LLM), line-finding is a plain heuristic
(`grocery/extract/heuristic.py`), and photos are read with local OCR
(`grocery/extract/ocr.py`). A local-LLM backend still exists in the codebase
(`grocery/extract/local_llm.py`, `grocery/extract/vision.py`) but isn't wired
into the app — parked for a possible future desktop build; see design.md.

## Setup

Requires [uv](https://docs.astral.sh/uv/) and, for photo uploads, the
[Tesseract OCR](https://github.com/tesseract-ocr/tesseract) system binary
(`pytesseract` just wraps it — Windows: the
[UB Mannheim installer](https://github.com/UB-Mannheim/tesseract/wiki); macOS:
`brew install tesseract`; Debian/Ubuntu: `apt install tesseract-ocr`). Then,
from this folder:

```bash
uv sync
```

First run downloads a small NLTK data file for the ingredient parser (once,
then cached).

## Running

```bash
uv run streamlit run app.py     # the UI
```

## Project layout

```
grocery/                  # the package, mirrors the pipeline
  config.py               # settings & knobs (categories, sanity thresholds)
  models.py                # shared data contract
  ingest/
    web.py                 # URL -> recipe content (JSON-LD or readable text)
    documents.py            # PDF/Word/text -> readable text
  extract/
    parse.py                # ingredient line -> {qty, unit, name, comment} (CRF)
    base.py                  # Extractor protocol (swappable backend)
    lite.py                  # the extraction backend the app actually uses (no model)
    heuristic.py             # finds ingredient lines in raw text (heading detection + fallback filter)
    noise.py                 # screens out UI chrome / recipe metadata / temperatures
    ocr.py                   # recipe photo -> text, via local Tesseract OCR
    categorize.py            # assign shopping categories (keyword lookup)
    local_llm.py             # dormant local-LLM backend, parked for a future desktop build
    vision.py                # dormant local vision-LLM backend, same
    download.py               # model weight downloader (used only by the dormant backends)
  aggregate/combine.py     # ingredients -> grocery list
  pipeline.py               # chains ingest -> extract -> parse -> categorize
app.py                     # Streamlit UI
eval/                      # labeled recipes + accuracy harness (evaluates the dormant LLM backend)
docs/                      # design docs
```
