# Grocery List Builder

A desktop app that turns recipe links, pasted text, documents, or photos into
one consolidated, editable grocery list, grouped by aisle. Everything runs
**locally**: a small LLM on your CPU, no cloud API, no key.

See [docs/design.md](docs/design.md) for the design and
[docs/design-principles.md](docs/design-principles.md) for the coding
principles all code here follows.

## Pipeline

```
link / text / document / photo → ingest → ingredient lines (LLM + heuristic check)
   → parse (CRF) → shopping name + aisle (keywords, LLM for unknowns) → merge → edit in the app
```

The LLM (Qwen3-4B, ~2.5 GB) does the fuzzy jobs: finding ingredient lines in
messy text and OCR output, rewriting recipe prose as ingredient lines, and
naming/filing ingredients the keyword table doesn't know. Precise jobs stay
deterministic: quantities and units come from a trained CRF parser
(`ingredient-parser`), and common aisles from a keyword table that measured
more accurate than the LLM (see `eval/`).

## Setup

Requires [uv](https://docs.astral.sh/uv/). From this folder:

```bash
uv sync
```

For **photos**, also install the [Tesseract OCR](https://github.com/tesseract-ocr/tesseract)
binary (Windows: the [UB Mannheim installer](https://github.com/UB-Mannheim/tesseract/wiki),
default location is found automatically; macOS: `brew install tesseract`;
Debian/Ubuntu: `apt install tesseract-ocr`). Everything else works without it.

## Running

```bash
uv run python app.py
```

The first launch downloads the model (~2.5 GB, once, into `models/`) and shows
progress in the status bar; later launches load it in a few seconds. If the
model can't be downloaded or loaded, the app still works in a basic
(model-free) mode and says so.

Recipe sites with structured data (most of them) are near-instant. Raw text,
documents, and photos go through the model: expect roughly 30–75 s per recipe
on a laptop CPU on battery, faster plugged in. The window stays usable while
it works.

### Using it

- Paste links (**Links**), raw recipe text (**Paste text**, separate recipes
  with a `---` line), or add photo/PDF/Word files, then **Build list**.
- In the list: double-click to edit, tick items as you shop, right-click to
  change an item's aisle or delete it. **Amber rows** are ones the app is
  unsure about (hover for why).
- **By recipe** shows what was read from each recipe, as written.
- **Copy as text**, **Save CSV…**, or **Email…** (set the `GROCERY_SMTP_*`
  variables described in `grocery/config.py` to enable email).

## Development

```bash
uv run pytest                 # unit tests (no model needed)
uv run python -m eval.run     # accuracy + timing on labeled recipes (uses the model)
uv run python -m eval.run --no-llm   # same, model-free baseline
```

Model, categories, synonyms, and thresholds are all knobs in
[grocery/config.py](grocery/config.py); prompts live at the top of
[grocery/extract/lines.py](grocery/extract/lines.py) and
[grocery/extract/normalize.py](grocery/extract/normalize.py).

## Project layout

```
app.py                     # the desktop window (PySide6)
grocery/                   # the pipeline, no UI code
  config.py                # settings & knobs (model, categories, synonyms, thresholds)
  models.py                # shared data contract
  pipeline.py              # chains ingest -> lines -> parse -> normalize -> combine
  export.py                # list -> text / CSV / email
  ingest/
    web.py                 # URL -> recipe content (JSON-LD or readable text)
    documents.py           # PDF/Word/text -> text
  extract/
    llm.py                 # the local model: lazy download/load, schema-constrained calls
    lines.py               # raw text -> ingredient lines (LLM picks, heuristic recall check)
    heuristic.py           # pattern-based line finder (recall check + no-model fallback)
    noise.py               # screens out UI chrome / metadata / temperatures
    ocr.py                 # photo -> text (Tesseract)
    parse.py               # ingredient line -> {qty, unit, name, notes} (CRF)
    normalize.py           # shopping name + aisle (synonyms, keywords, LLM for unknowns)
    categorize.py          # the keyword -> aisle table
    download.py            # resumable model download (ModelScope)
  aggregate/combine.py     # merge across recipes + sanity checks
eval/                      # labeled recipes + accuracy/timing harness
tests/                     # unit tests (a fake LLM stands in for the model)
docs/                      # design docs
```

The earlier Android app and cloud-deploy setup were removed in this rewrite;
they're preserved at the git tag `android-final`.
