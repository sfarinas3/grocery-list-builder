# Grocery List Builder — Design Document

## 1. Goal

Turn recipes into a consolidated grocery list with minimal effort.

- **Input:** recipe links, pasted recipe text, documents (PDF/Word/text), photos
- **Output:** an on-screen, editable, aisle-grouped checklist; copy / CSV / email
- **Form factor:** a native desktop app (PySide6), run from source with `uv`
- **Extraction:** fully **local** — a small LLM on the CPU plus deterministic
  tools; no hosted API, no key, no per-recipe cost
- **Deferred:** a packaged installer (.exe), persistence between sessions,
  multi-user, SMS

### Why a desktop app with an LLM (history)

The project started as a local Streamlit app with an LLM, then was cut down to
a model-free pipeline to fit a phone (Android) and free-tier cloud hosting.
That version's quality ceiling was the heuristic line-finder: it can't read
recipe prose, fix OCR typos, or tell a section heading from an ingredient, and
nothing caught a dropped line. A desktop has the CPU/RAM budget for a small
model, so this rewrite brings the LLM back — but only for the jobs the
deterministic tools are bad at (§3.3), with each choice checked against the
eval set (§7). The Android app and deploy setup are preserved at git tag
`android-final`.

## 2. Guiding principle

The whole app is one pipeline; each stage is handled by the tool that's
measurably best at it, not by one big model.

```
link / text / document / photo → ingest
   ├─ JSON-LD found  → clean ingredient lines ─────────────────────────────┐
   └─ raw text       → LLM picks ingredient lines by number               │
      (page, doc,      + heuristic recall check + LLM rewrites prose lines ┤
       OCR'd photo)                                                         ▼
      ingredient-parser (CRF): line → { quantity, unit, name, notes }     ← precise structured parse
                                                                            ▼
      shopping name + aisle: synonyms → keyword table → LLM for unknowns   (one batched call)
                                                                            ▼
      Ingredient[] → combine (merge by shopping name, sanity flags) → GroceryList → review / edit
```

## 3. Architecture

```
app.py                  # PySide6 window: inputs, background worker, editable list, export
grocery/
  pipeline.py           # process_url / _document / _image / _text, build_list
  models.py             # pydantic data contract (§5)
  config.py             # model, categories, synonyms, thresholds, SMTP
  export.py             # GroceryList -> text / CSV / email
  ingest/web.py, documents.py
  extract/llm.py, lines.py, heuristic.py, noise.py, ocr.py, parse.py,
          normalize.py, categorize.py, download.py
  aggregate/combine.py
eval/                   # labeled fixtures + accuracy/timing harness (§7)
tests/                  # unit tests with a FakeLLM
```

### 3.1 Ingestion (`ingest/`, `extract/ocr.py`)
- **URLs:** prefer `schema.org/Recipe` JSON-LD (most recipe sites embed it:
  clean lines, no model needed); otherwise strip the page to main text with
  `trafilatura`.
- **Documents:** `pypdf` / `python-docx` / plain decode → text.
- **Photos:** Tesseract OCR → text (needs the system binary; the Windows
  default install path is detected).
- **Pasted text:** used as-is — the escape hatch for sites that block fetching.

### 3.2 Ingredient-line parsing (`extract/parse.py`)
`ingredient-parser`, a trained CRF, turns `"2 cloves garlic, minced"` into
quantity / unit / name / notes — locally, instantly, and more reliably than a
small LLM. A line naming several things with no amount ("salt and pepper to
taste") becomes several items; with an amount ("1 cup milk or cream") they're
alternatives and stay one item.

### 3.3 Where the LLM is used — and where it isn't
The model is Qwen3-4B (Q4_K_M GGUF, ~2.5 GB) via `llama-cpp-python`
on CPU (`extract/llm.py`). Every call is **grammar-constrained to a JSON
schema**, so output is always the requested shape. On a laptop CPU, output
tokens are the cost (~5–7 tokens/s plugged in), so every prompt is designed to
make the model write as little as possible.

| Job | Who does it | Why |
|---|---|---|
| Find ingredient lines in raw text | **LLM**, answering with line *numbers* | Handles messy pages/OCR/sub-headings the heuristic can't; numbers are ~5× fewer tokens than copying lines, and can't garble or invent text |
| Catch lines the LLM skipped | Heuristic finds candidates; **LLM** re-checks only the ones it skipped | A recall safety net that costs one small call, and only when needed |
| Recipe prose ("you'll need a can of coconut milk…") | **LLM** rewrites just those long lines as ingredient lines | The only case where the model writes text out |
| Quantities, units, names | CRF | Purpose-built, precise |
| Alternate names (scallion → green onion) | `config.SYNONYMS` | Deterministic, free |
| Aisle for known ingredients | Keyword table (`categorize.py`) | Measured far more accurate than the 3B model (§7) |
| Name + aisle for unknown ingredients (tahini, OCR typos like "garIic") | **LLM**, one batched call for all recipes | The table can't; the model's cleaned name goes back through the table first |

**No model?** If the download or load fails, every LLM step has a model-free
fallback (heuristic lines, keyword aisles, `needs_category` flags) and the
app shows "basic mode".

### 3.4 Aggregation (`aggregate/combine.py`)
- Merge on shopping name, with plurals folded ("onions" = "onion").
- Sum amounts that share a unit; never convert between units (cups↔grams) —
  mixed units are shown as a breakdown and flagged.
- The recipes' own wording is kept in the notes ("as: spring onions"), so
  every merge can be checked.
- **Sanity rules (Quality Check #2):** flag zero/negative/absurd quantities,
  missing aisles, empty names. Drop non-purchases (water) via `EXCLUDE_FROM_LIST`.

### 3.5 UI (`app.py`)
- Inputs: links, pasted text (`---` separates recipes), photo/document files.
- Work runs on a single background worker thread: the window stays
  responsive, the model is never called concurrently (llama.cpp isn't
  thread-safe), and a build queued during start-up simply waits for the model.
- Model download/load progress and "basic mode" are shown in the status bar.
- The list is a tree grouped by aisle (store order from `config.CATEGORIES`):
  edit in place, tick off, right-click to move aisle / delete, add items.
  Flagged rows are amber with a tooltip saying why **(Quality Check #3 — human
  review)**. A "By recipe" tab shows what was read from each recipe.

## 4. Quality & validation

| # | Check | Where | Catches |
|---|---|---|---|
| 1 | Recall check + filters | `extract/lines.py`, `heuristic.py`, `noise.py` | Lines the LLM skipped; sub-headings; UI chrome, metadata, temperatures |
| 2 | Sanity rules | `aggregate/combine.py` | Bad/absurd quantities, unknown aisles, empty names, mixed units |
| 3 | Human review | `app.py` | Everything else — the final safety net, with flagged rows highlighted |

## 5. Shared data contract (`models.py`)

```python
RecipeContent { source_url, title, servings, ingredient_lines[], raw_text }
Ingredient    { name, shopping_name, quantity, unit, category, notes, source_url,
                confidence, flags[] }
GroceryItem   { name, quantity, unit, category, notes, checked, sources[], flags[] }
GroceryList   { items[], by_category }
```

## 6. Stack

| Layer | Choice | Notes |
|---|---|---|
| UI | PySide6 (Qt) | Native window, all-Python |
| Local LLM | `llama-cpp-python==0.3.19` + Qwen3-4B GGUF | Pinned for the prebuilt AVX2 wheel (no compiler needed; this CPU lacks AVX512) |
| Fetch / text | `httpx`, `trafilatura`, `truststore` | Corporate TLS proxy handled via the OS cert store |
| Documents / OCR | `pypdf`, `python-docx`, `pytesseract` | Tesseract binary installed separately |
| Line parse | `ingredient-parser-nlp` (CRF) | |
| Data models | `pydantic` | |

## 7. Evaluation

`eval/run.py` runs hand-labeled fixtures (`eval/cases.json`: JSON-LD lines,
messy text, prose, an OCR-style screen capture) through the real pipeline and
reports name precision/recall/F1, aisle accuracy, and seconds per case;
`--no-llm` gives the model-free baseline. Design choices in §3.3 came from it:

| Version | Name F1 | Recall | Aisles | Notes |
|---|---|---|---|---|
| Model-free (previous app) | 0.92 | 0.87 | 88% | Misses all of the prose recipe; keeps OCR typos |
| LLM picks every aisle (first cut, 3B) | 0.90 | 0.87 | 60% | ~30 s/recipe even for JSON-LD |
| Table-first, LLM for unknowns (3B) | 0.90 | 0.87 | 92% | 3B copied few-shot examples into lists |
| **Same, Qwen3-4B (current)** | **0.99** | **1.00** | **91%** | After bounding the line-number schema (prose fix) |

Lessons that shaped §3.3: the keyword table beats a small LLM at aisles for
anything it knows; a small model will leak few-shot examples and run away
counting unless the schema forbids it; and output tokens dominate time.
JSON-LD recipes take <1 s; raw-text recipes took ~30–75 s each on this laptop
on battery (noticeably faster plugged in). Try other models via
`GROCERY_LLM_REPO` / `GROCERY_LLM_FILE` and re-run the eval.

The set is small (9 recipes); read the per-case diffs, and add a fixture
whenever a real recipe goes wrong.

## 8. Key risks & how we handle them

| Risk | Handling |
|---|---|
| Slow on a laptop CPU | Minimize output tokens (line numbers, tuple answers, LLM only for unknowns); background worker keeps the UI responsive |
| Model download blocked by the corporate proxy | Download from ModelScope (HF/Ollama CDNs are blocked); resumable; app degrades to basic mode if it fails |
| Prebuilt wheel uses unsupported CPU instructions | Pin `llama-cpp-python==0.3.19` (AVX2) |
| Small model mislabels or skips | Constrained schemas, recall check, deterministic tiers first, amber review flags |
| Site blocks scraping / JS-only page | Prefer JSON-LD; on failure, an error plus the Paste-text tab |
| Unit conversion (cups↔grams) | Not attempted; mixed units shown and flagged |

## 9. Out of scope (for now)

Packaged installer, saved lists/history, pantry tracking, servings scaling,
SMS. Each is an add-on to the existing pipeline once there's a need.
