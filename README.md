# Grocery List Builder Bot

Turn recipe web links into a consolidated, editable grocery list — runs fully
**locally**, no cloud LLM API or key required.

See [docs/design.md](docs/design.md) for the design and [docs/design-principles.md](docs/design-principles.md)
for the coding principles all code here follows.

## Pipeline

```
URL → ingest → parse (CRF) + local LLM → categorize → aggregate → display/edit
```

## Setup

Requires [uv](https://docs.astral.sh/uv/). Then, from this folder:

```bash
uv sync --extra local   # full app, incl. the local LLM
# or:
uv sync                 # lite mode — no model (JSON-LD + lookup only)
```

The local model is an **optional** dependency (`--extra local`), so low-RAM
hosts like Streamlit Community Cloud install the base set and run lite mode.

With the model, first run downloads two things (once, then cached):
- a small NLTK data file for the ingredient parser, and
- the local GGUF model (~2 GB) from ModelScope.

## Running

```bash
uv run streamlit run app.py     # the UI
uv run python -m eval.run       # accuracy eval (P/R/F1 + category accuracy)
```

## Project layout

```
grocery/                  # the package, mirrors the pipeline
  config.py               # settings & knobs (model, TLS, categories)
  models.py               # shared data contract
  ingest/web.py           # URL -> recipe content (JSON-LD or readable text)
  extract/
    parse.py              # ingredient line -> {qty, unit, name, comment} (CRF)
    base.py               # Extractor protocol (swappable backend)
    local_llm.py          # local GGUF model, grammar-forced JSON
    lite.py               # model-free backend (Streamlit Cloud / low-RAM hosts)
    download.py           # fetch the GGUF model from ModelScope
    categorize.py         # assign shopping categories
  aggregate/combine.py    # ingredients -> grocery list
app.py                    # Streamlit UI
eval/                     # labeled recipes + accuracy harness
docs/                     # design docs
```
