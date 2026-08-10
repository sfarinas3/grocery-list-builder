# Grocery List Builder — Design Document

## 1. Goal

Turn recipes into a consolidated grocery list with minimal effort.

**Current scope:**
- **Input:** recipe web links, uploaded documents (PDF/Word/text), and recipe photos
- **Output:** on-screen, editable grocery list
- **Form factor:** Streamlit app, runs locally or deployed (Streamlit Community Cloud / HF Space)
- **Extraction:** runs fully **locally**, with **no model of any kind** in the
  live path — no hosted API, no local LLM, no API key, no per-recipe cost
- **Deferred:** SMS/export beyond CSV/text/email, multi-user/auth, persistence
  beyond a session

A local-LLM backend (`grocery/extract/local_llm.py`, `grocery/extract/vision.py`)
still exists in the codebase but isn't wired into the app — see §3.3 and §8.

## 2. Guiding principle

The whole app is one pipeline. Getting its contract right is the point; new
input/output types become adapters bolted onto the ends. Each stage is handled
by the tool best suited to it, not by one big model.

```
URL / document / photo → ingest
   ├─ JSON-LD found          → clean ingredient lines ──────────────┐
   └─ no JSON-LD / doc text  → heuristic line-finder pulls the      ┤
      / OCR'd photo text        lines from raw text                 │
                                                                     ▼
        ingredient-parser (CRF): line → { quantity, unit, name, comment }   ← the hard structured parse
                                                                     ▼
        categorize (produce / dairy / pantry / …) → keyword lookup table
                                                                     ▼
        Ingredient[] → aggregate / normalize → GroceryList → display / edit
```

## 3. Architecture

Modular Python, single Streamlit app for the UI. Everything runs on the local
machine, with no network dependency beyond fetching the recipe itself.

```
grocery/
  ingest/
    web.py          # URL -> RecipeContent (JSON-LD lines, or readable text)
    documents.py     # PDF/Word/text -> readable text
  extract/
    base.py          # Extractor protocol — the swappable backend seam
    parse.py          # ingredient-parser (CRF): line -> {qty, unit, name, comment}
    lite.py            # the extraction backend the app actually uses (no model)
    heuristic.py         # finds ingredient lines in raw text
    noise.py               # screens out UI chrome / recipe metadata / temperatures
    ocr.py                   # recipe photo -> text, via local Tesseract OCR
    categorize.py             # assign a shopping category to each ingredient
    local_llm.py               # dormant local-LLM backend (§3.3, §8)
    vision.py                   # dormant local vision-LLM backend (§3.3, §8)
  aggregate/
    combine.py       # Ingredient[] -> GroceryList (merge, reconcile, sanity rules)
  models.py           # pydantic models (the shared contract)
  config.py            # category list, thresholds, dormant-LLM knobs
  pipeline.py           # chains ingest -> extract -> parse -> categorize
app.py                # Streamlit UI: paste URLs / upload documents / add photos, review, edit, check off
eval/                 # hand-labeled recipes + accuracy harness (see §7)
```

### 3.1 Ingestion (`ingest/web.py`, `ingest/documents.py`)
For URLs, a two-tier strategy for accuracy:
1. **Preferred — structured data.** Parse `schema.org/Recipe` JSON-LD from the
   page (`<script type="application/ld+json">`). ~70% of recipe sites embed it,
   giving clean ingredient lines with near-zero ambiguity and **no model needed**.
2. **Fallback — readable text.** If no JSON-LD, strip the page to main content
   (`trafilatura`) and hand that text to the heuristic line-finder (§3.3).

Documents (PDF/Word/text) skip straight to readable text (`pypdf`/`python-docx`),
which goes through the same heuristic line-finder as the URL fallback. Photos
go through OCR first (§3.3), then the same path.

Output: `RecipeContent { source_url, title, servings, ingredient_lines[], raw_text }`.

### 3.2 Ingredient-line parsing (`extract/parse.py`)
The hard sub-task — turning `"2 cloves garlic, minced"` into structured fields —
is handled by **[`ingredient-parser`](https://github.com/strangetom/ingredient-parser)**,
a trained CRF sequence-labeling model purpose-built for exactly this. It runs
**locally, instantly, free**, needs no GPU, and is the component where accuracy
matters most. Output per line: `{ quantity, unit, name, comment }`.

### 3.3 Line-finding, OCR & categorization (`extract/heuristic.py`, `extract/noise.py`, `extract/ocr.py`, `extract/categorize.py`)
No model of any kind runs in the live app. Three plain, deterministic pieces
replace what a local LLM used to do:

- **Line-finding (`heuristic.py`).** When there's no JSON-LD, find the
  ingredient lines in raw text two ways: look for an "Ingredients:" heading
  and take every line up to the next section heading (no further filtering —
  real ingredient lines like "salt to taste" have no quantity/unit to filter
  on); if no heading is found, fall back to a per-line filter (starts with a
  quantity, or has a unit word near the start, and doesn't start with an
  instruction verb like "Preheat"). Reuses `ingredient-parser`'s own
  number-word and unit-word lists rather than duplicating them. A first cut,
  not tuned against a large corpus — expected to need iteration as real
  failure modes turn up (see §8).
- **Noise filtering (`noise.py`).** A curated word/phrase list that screens
  out device/app UI chrome, recipe-page metadata, and cooking temperatures
  that can end up as their own line when a photo captures a phone/website
  screen alongside the recipe (e.g. "Partly Sunny", "82 degrees Celsius").
  Applied at line-finding time in both paths above.
- **Photo OCR (`ocr.py`).** Recipe photos are read with local Tesseract OCR
  (`pytesseract`, no model) into raw text, which then goes through the same
  line-finder as any other raw text. Needs the `tesseract-ocr` system binary
  (see README.md).
- **Categorization (`categorize.py`).** A keyword lookup table assigns each
  ingredient a shopping aisle (produce / dairy / pantry / …). Anything the
  table misses keeps the default sentinel and is flagged `needs_category` for
  human review — there's no model fallback anymore.
- **Swappable backend (`base.py`).** All of this sits behind the same
  `Extractor` protocol the (now dormant) LLM backend implemented, so a real
  model — local or hosted — is still a drop-in swap if it's ever worth
  bringing back for a specific deployment target (see below).

**A local-LLM backend still exists, dormant.** `local_llm.py` (text
extraction + categorization fallback) and `vision.py` (reading photos
directly, no OCR step) implement the same `Extractor` protocol and are kept
in the codebase — just not instantiated by `app.py` anymore. They're parked
for a possible future **desktop build**, where the hardware budget (a real
CPU/GPU, not a phone or a free-tier cloud host) could make a real local model
worth its cost again, especially for messier inputs the heuristic misses.
Install their dependency with `uv sync --extra local`; `eval/run.py` still
exercises them for exactly this reason (§7).

### 3.4 Aggregation (`aggregate/combine.py`)
- Merge duplicate ingredients across recipes.
- Reconcile units where trivially compatible (same unit, or simple conversions).
- Leave genuinely ambiguous merges ("to taste", cups↔grams) as separate line
  items rather than guessing — correctness over cleverness.
- Group by category for a shopping-friendly order.
- **Deterministic sanity rules (Quality Check #2).** Non-model validation on
  the assembled list: reject negative/zero quantities, flag absurd magnitudes
  (e.g. "500 cups"), catch unknown categories and empty names. Free, fast, and
  deterministic.

### 3.5 UI (`app.py`, Streamlit)
- Text area to paste one or more recipe URLs, plus expanders to add photos
  (camera or upload) and documents (PDF/Word/text).
- "Build list" runs the pipeline, shows per-recipe extraction, then the combined list.
- List is **editable**: rename, adjust quantity, delete, check off. This edit step
  is the trust mechanism — the user can fix any miss before shopping
  **(Quality Check #3 — human review)**. Items flagged by Check #2 (and by
  line-finding turning up nothing recognizable) are visually surfaced here so
  attention goes where it matters.

## 4. Quality & validation

Two deterministic layered checks, plus human review:

| # | Check | Where | Type | Catches |
|---|---|---|---|---|
| 1 | Line-finding filters | `extract/heuristic.py`, `extract/noise.py` | Deterministic | Non-ingredient lines: instruction steps, UI chrome, recipe metadata, cooking temperatures |
| 2 | Sanity rules | `aggregate/combine.py` | Deterministic | Negative/zero/absurd quantities, unknown categories, empty names |
| 3 | Human review | `app.py` | User | Everything else — dropped/missed lines, misreads; the final safety net |

**Known trade-off:** the old design had an LLM re-check, in-context, that no
ingredient line was dropped (a recall safety net). There's no equivalent now —
the heuristic line-finder has no self-correction step, so a missed line is
just missed; it depends entirely on human review to catch. This was a
deliberate, accepted trade-off (see §8) in exchange for a fully model-free,
low-resource pipeline. If it proves insufficient in practice, the fix is
tuning the heuristic against real failure cases (as already happened once —
see git history for `noise.py`'s additions after real photo/OCR testing on
the sibling Android app), not reintroducing a model into the default path.

## 5. Shared data contract (`models.py`)

```python
Ingredient    { name, quantity: float|None, unit: str|None, category, notes, source_url,
                confidence: float, flags: list[str] }   # flags set by Checks #1 & #2
RecipeContent { source_url, title, servings, ingredient_lines[], raw_text }
GroceryList   { items: list[GroceryItem], by_category }
GroceryItem   { name, quantity, unit, category, checked: bool, sources: list[url],
                flags: list[str] }                       # surfaced in the UI for review
```

## 6. Finalized stack

| Layer | Choice | Notes |
|---|---|---|
| Language / UI | Python + Streamlit | Minimal frontend code |
| Fetch | `httpx` | Retrieve pages |
| Readable text | `trafilatura` | Strip a page to main content (text fallback) |
| Document text | `pypdf`, `python-docx` | PDF / Word upload support |
| Structured line parse | `ingredient-parser` (CRF) | Local, instant, free — the accuracy-critical step |
| Line-finding (non-JSON-LD text) | Heuristic (`extract/heuristic.py`) | Heading detection + quantity/unit fallback filter, no model |
| Noise filtering | `extract/noise.py` | Curated word/phrase list |
| Photo OCR | `pytesseract` (Tesseract) | Local OCR, no model; feeds the same line-finder |
| Data models | `pydantic` | The shared contract |
| *(dormant)* Local LLM | `llama-cpp-python==0.3.19` + GGUF | Not wired into the app; parked for a future desktop build (§3.3) |

No hosted API and no LLM in the live path, so **no API key and no `.env` are
needed**. Runtime dependencies are added per build step (YAGNI), not all up
front.

## 7. Evaluation

We don't guess accuracy — we measure it. `eval/run.py` runs a hand-labeled
set (`eval/cases.json`) through a real `Extractor` and reports precision /
recall / F1 on ingredient names plus category accuracy. It currently
exercises the dormant `LocalLLMExtractor` (its original purpose: comparing
candidate models before this app dropped the LLM) — it's a useful harness to
revive if the desktop-LLM path in §3.3 gets picked back up. There isn't yet
an equivalent harness for the live heuristic path; building one (labeled
messy-text fixtures, same P/R/F1 approach) would be the natural next step if
line-finding accuracy needs to be measured rather than eyeballed.

## 8. Key risks & how we handle them

| Risk | Handling |
|---|---|
| Site blocks scraping / JS-only page | Prefer JSON-LD; on failure, clear error + let user paste text manually |
| Heuristic line-finder misses lines (no self-correction, unlike the old LLM check — see §4) | Human review catches it; tune `extract/heuristic.py`/`extract/noise.py` against real failure cases as they turn up (already happened once, on the sibling Android app) |
| Unit conversion (cups↔grams) | Don't attempt density conversions; keep incompatible units separate |
| Same ingredient, different names | Canonicalize where easy (plural-tolerant keyword matching); accept imperfect merges |
| OCR misreads a recipe photo | Same as any missed/garbled line — human review; a fused OCR misread (e.g. a bullet glyph fused onto a word) isn't something word-level filtering can cleanly separate |
| *(only if the dormant LLM extra is installed)* Prebuilt wheel uses unsupported CPU instructions | Pin `llama-cpp-python==0.3.19` (AVX2-safe) |
| *(only if the dormant LLM extra is installed)* Corporate proxy blocks HF/Ollama model CDNs | Download from ModelScope instead (`extract/download.py`) |

## 9. Build order (vertical slices, historical)

1. `models.py` — lock the contract (incl. `confidence` / `flags`).
2. `ingest/web.py` — JSON-LD path first, text fallback second.
3. `extract/parse.py` — wire `ingredient-parser`; test on JSON-LD lines (no model needed).
4. `extract/categorize.py` — keyword-based shopping categories.
5. Minimal `app.py` — paste one URL, see `Ingredient[]`. **First end-to-end demo.**
6. `aggregate/combine.py` — multi-recipe merge + categories **+ Check #2 sanity rules**.
7. Documents and photos added (originally via a local LLM + vision model),
   then a Streamlit Community Cloud deploy needing a model-free path
   (`extract/lite.py`) alongside the full local-LLM mode.
8. **The LLM dropped from the live app entirely** — `extract/heuristic.py` +
   `extract/noise.py` replaced local-LLM line-finding, `extract/ocr.py`
   (Tesseract) replaced the vision model for photos, and `lite.py` became the
   only backend `app.py` uses. `local_llm.py`/`vision.py` kept, dormant, for
   a possible future desktop build (§3.3).

## 10. Explicitly out of scope

SMS/Twilio, accounts/auth, persistence beyond a session, and a standalone
final-list model checker (no source text to compare against at that point;
would only earn its place in a future hands-off mode). Each is an adapter or
add-on that can be layered on once there's a real need for it.
