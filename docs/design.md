# Grocery List Builder — Design Document (v1)

## 1. Goal

Turn recipes into a consolidated grocery list with minimal effort.

**v1 scope (this document):**
- **Input:** recipe web links only
- **Output:** on-screen, editable grocery list
- **Form factor:** quick personal prototype, run locally
- **Extraction:** runs fully **locally** — no hosted LLM API, no API key, no per-recipe cost
- **Deferred to later:** PDFs, photos, SMS/export, multi-user/auth

## 2. Guiding principle

The whole app is one pipeline. Getting its contract right is the point; new
input/output types become adapters bolted onto the ends. Each stage is handled
by the tool best suited to it, not by one big model.

```
URL → fetch
   ├─ JSON-LD found      → clean ingredient lines ─────────────┐
   └─ no JSON-LD         → local LLM pulls the lines from text ┤
                                                               ▼
        ingredient-parser (CRF): line → { quantity, unit, name, comment }   ← the hard structured parse
                                                               ▼
        categorize (produce / dairy / pantry / …) → lookup table or local LLM
                                                               ▼
        Ingredient[] → aggregate / normalize → GroceryList → display / edit
```

## 3. Architecture

Modular Python, single Streamlit app for the UI. Everything runs on the local
machine.

```
grocery/
  ingest/
    web.py          # URL -> RecipeContent (JSON-LD lines, or readable text)
  extract/
    base.py         # Extractor protocol — the swappable backend seam
    parse.py        # ingredient-parser (CRF): line -> {qty, unit, name, comment}
    local_llm.py    # llama-cpp-python + GGUF model, grammar-forced JSON
    categorize.py   # assign a shopping category to each ingredient
  aggregate/
    combine.py      # Ingredient[] -> GroceryList (merge, reconcile, sanity rules)
  models.py         # pydantic models (the shared contract)
  config.py         # model repo/quant, thresholds, category list — the knobs
app.py              # Streamlit UI: paste URLs, review, edit, check off
eval/               # hand-labeled recipes + accuracy harness (see §7)
```

### 3.1 Web ingestion (`ingest/web.py`)
Two-tier strategy for accuracy:
1. **Preferred — structured data.** Parse `schema.org/Recipe` JSON-LD from the
   page (`<script type="application/ld+json">`). ~70% of recipe sites embed it,
   giving clean ingredient lines with near-zero ambiguity and **no model needed**.
2. **Fallback — readable text.** If no JSON-LD, strip the page to main content
   (`trafilatura`) and hand that text to the local LLM to pull out the lines.

Output: `RecipeContent { source_url, title, servings, ingredient_lines[], raw_text }`.

### 3.2 Ingredient-line parsing (`extract/parse.py`)
The hard sub-task — turning `"2 cloves garlic, minced"` into structured fields —
is handled by **[`ingredient-parser`](https://github.com/strangetom/ingredient-parser)**,
a trained CRF sequence-labeling model purpose-built for exactly this. It runs
**locally, instantly, free**, needs no GPU, and is the component where accuracy
matters most. Output per line: `{ quantity, unit, name, comment }`.

This is deliberate division of labor: the CRF handles the fiddly quantity/unit/name
parse (where small general LLMs are weakest), so the LLM's job stays easy.

### 3.3 Local LLM extraction & categorization (`extract/local_llm.py`, `categorize.py`)
- **Runtime:** **`llama-cpp-python`** running a **quantized GGUF** model on CPU.
  Output is constrained with a **JSON schema / grammar**, so the model
  *physically cannot* emit invalid JSON (or a `<think>` preamble) — the key
  reliability fix for small models.
  - **Pinned to `llama-cpp-python==0.3.19`.** Newer prebuilt CPU wheels use
    AVX512 instructions this Surface CPU (AVX2, no AVX512) lacks — they crash on
    model load with `STATUS_ILLEGAL_INSTRUCTION`. 0.3.19's wheel is AVX2-safe.
- **Model (finalized): Qwen2.5-7B-Instruct** (Q4_K_M GGUF, ~4.7 GB). Chosen for
  quality; it's non-thinking (no `<think>` complication) and text-only. Model
  choice is a single setting in `config.py`; the eval alternates (Qwen2.5-3B for
  speed, Qwen3-4B) are one-line swaps.
- **Weights come from ModelScope, not Hugging Face.** The corporate Zscaler
  proxy blocks HF's and Ollama's model CDNs (403); ModelScope's CDN is reachable
  and hosts the Qwen GGUFs first-party. `extract/download.py` streams the file
  over `httpx` (truststore) into `models/` on first run.
- **The LLM's narrow job:**
  1. When there's no JSON-LD, extract the ingredient **lines** from readable page
     text (which then go to the CRF parser like any other line).
  2. **Categorization** — assign each ingredient a shopping aisle
     (produce / dairy / pantry / …). May be a static lookup table with the LLM
     as fallback for unknowns.
- **Swappable backend (`base.py`).** Extraction sits behind an `Extractor`
  protocol so a different local model — or a hosted model later — is a drop-in
  swap, chosen in `config.py`. The rest of the pipeline never knows which runs.
- **Extraction-time self-validation (Quality Check #1).** While the source text
  is still in context, the model re-checks that no ingredient line was dropped;
  low-confidence items are flagged (not silently guessed) so the UI can surface
  them.

### 3.4 Aggregation (`aggregate/combine.py`)
- Merge duplicate ingredients across recipes.
- Reconcile units where trivially compatible (same unit, or simple conversions).
- Leave genuinely ambiguous merges ("to taste", cups↔grams) as separate line
  items rather than guessing — correctness over cleverness in v1.
- Group by category for a shopping-friendly order.
- **Deterministic sanity rules (Quality Check #2).** Non-LLM validation on the
  assembled list: reject negative/zero quantities, flag absurd magnitudes
  (e.g. "500 cups"), catch unknown categories and empty names. Free, fast, and
  deterministic.

### 3.5 UI (`app.py`, Streamlit)
- Text area / list to paste one or more recipe URLs.
- "Build list" runs the pipeline, shows per-recipe extraction, then the combined list.
- List is **editable**: rename, adjust quantity, delete, check off. This edit step
  is the trust mechanism — the user can fix any miss before shopping
  **(Quality Check #3 — human review)**. Items flagged by Checks #1 and #2 are
  visually surfaced here so attention goes where it matters.

## 4. Quality & validation (v1)

Three layered checks, cheapest and earliest first:

| # | Check | Where | Type | Catches |
|---|---|---|---|---|
| 1 | Extraction self-validation | `extract/local_llm.py` | LLM (in-context) | Dropped ingredient lines — validated against the source text |
| 2 | Sanity rules | `aggregate/combine.py` | Deterministic | Negative/zero/absurd quantities, unknown categories, empty names |
| 3 | Human review | `app.py` | User | Everything else — the final safety net; flagged items are highlighted |

Rationale: validation has leverage only where the source is available (Check #1),
so it lives at extraction time, not on the finished list. Checks #2 and #3 are
cheap backstops. A standalone "LLM re-checks the final list" agent is **deferred**
— no source to compare against; earns its place only in a future hands-off mode.

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
| Language / UI | Python + Streamlit | Personal prototype, minimal frontend code |
| Fetch | `httpx` | Retrieve pages |
| Readable text | `trafilatura` | Strip page to main content (text fallback) |
| Structured line parse | `ingredient-parser` (CRF) | Local, instant, free — the accuracy-critical step |
| LLM runtime | `llama-cpp-python==0.3.19` + GGUF | CPU, **grammar-forced JSON**; pinned for AVX2 (no AVX512) |
| LLM model | Qwen2.5-7B-Instruct (Q4_K_M, ~4.7 GB) | from **ModelScope** (HF/Ollama CDNs blocked by Zscaler); set in `config.py` |
| Data models | `pydantic` | The shared contract |

No hosted API, so **no `ANTHROPIC_API_KEY` and no `.env` are needed** (the
scaffolding for those will be removed when the extract module lands). Runtime
dependencies are added per build step (YAGNI), not all up front.

## 7. Evaluation

We don't guess accuracy — we measure it. Build a small **eval set** of 10–20
hand-labeled recipes (mix of JSON-LD and messy sites) with the correct ingredient
list for each. A harness in `eval/` runs any `Extractor` backend against it and
reports accuracy. Uses:
- Pick between **Qwen3.5 4B** and **Phi-4-mini** on *our* recipes, not on a blog's benchmark.
- Regression guard as we tune prompts and grammar.
- Honest answer to "how good is it?" before trusting the tool.

## 8. Key risks & how we handle them (v1)

| Risk | v1 handling |
|---|---|
| Site blocks scraping / JS-only page | Prefer JSON-LD; on failure, clear error + let user paste text manually |
| Small local model misses a line | CRF does the hard parse; editable review catches the rest; eval set measures the gap |
| Invalid JSON from a small model | Grammar-constrained decoding guarantees well-formed output |
| Slow CPU inference | Short outputs (just the ingredient lines); drop to Qwen2.5-3B if 7B is too slow |
| Corporate proxy blocks HF/Ollama model CDNs | Download from ModelScope instead (`extract/download.py`), which Zscaler allows |
| Prebuilt wheel uses unsupported CPU instructions | Pin `llama-cpp-python==0.3.19` (AVX2-safe); this CPU has no AVX512 |
| First-run model download (~4.7 GB GGUF) | Downloaded once to `models/` (resumable), then reused |
| Unit conversion (cups↔grams) | Don't attempt density conversions; keep incompatible units separate |
| Same ingredient, different names | Canonicalize where easy; accept imperfect merges |

## 9. Build order (vertical slices)

1. `models.py` — lock the contract (incl. `confidence` / `flags`).
2. `ingest/web.py` — JSON-LD path first, text fallback second.
3. `extract/parse.py` — wire `ingredient-parser`; test on JSON-LD lines (no model needed yet).
4. `extract/base.py` + `local_llm.py` — `llama-cpp-python` loader, grammar-forced JSON,
   line extraction from messy pages **+ Check #1**.
5. `extract/categorize.py` — assign shopping categories.
6. Minimal `app.py` — paste one URL, see `Ingredient[]`. **First end-to-end demo.**
7. `aggregate/combine.py` — multi-recipe merge + categories **+ Check #2 sanity rules**.
8. `eval/` — labeled set + harness; compare Qwen3.5 4B vs Phi-4-mini.
9. Polish UI — editing, check-off, grouping **+ Check #3 flag highlighting**.

## 10. Explicitly out of scope for v1
PDFs, photos/vision, SMS/Twilio, email/PDF export, accounts/auth, persistence
beyond a session, deployment, hosted-LLM backend, **standalone final-list LLM
checker** (revisit for hands-off mode). Each is an adapter or add-on we can layer
on once the pipeline is proven.
```
