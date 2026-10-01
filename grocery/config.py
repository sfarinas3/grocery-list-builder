"""Central settings — the knobs a data scientist will actually turn.

Importing this module also performs one-time TLS setup for the whole process
(see below), so it's imported early by anything that reaches the network.
"""

import os
from pathlib import Path

import truststore

# --- Network / TLS (corporate proxy) --------------------------------------
# Route all of Python's TLS through the OS certificate store — the store that
# already trusts the corporate root CA. Fixes httpx fetches AND the model
# download in one call. Verification stays ON (never disable it).
truststore.inject_into_ssl()


# --- Local LLM (extract/llm.py) --------------------------------------------
# A small instruct model, run on CPU through llama-cpp-python. Qwen3-4B was
# picked over Qwen2.5-3B on the eval set (eval/run.py): about the same speed
# with the LLM-for-unknowns-only design, but more precise — the 3B model
# leaked the prompt's worked examples into lists as phantom items. ~2.5 GB.
# To try another model, e.g. the 3B:
#   GROCERY_LLM_REPO=Qwen/Qwen2.5-3B-Instruct-GGUF
#   GROCERY_LLM_FILE=qwen2.5-3b-instruct-q4_k_m.gguf
# and measure it with `uv run python -m eval.run` before switching.
#
# Weights download once on first run. We fetch from ModelScope rather than
# Hugging Face because the corporate Zscaler proxy blocks HF's and Ollama's
# model CDNs — ModelScope's CDN is reachable and hosts the Qwen GGUFs first-party.
LLM_REPO = os.environ.get("GROCERY_LLM_REPO", "Qwen/Qwen3-4B-GGUF")
LLM_FILE = os.environ.get("GROCERY_LLM_FILE", "Qwen3-4B-Q4_K_M.gguf")
LLM_URL = f"https://modelscope.cn/models/{LLM_REPO}/resolve/master/{LLM_FILE}"

MODELS_DIR = Path(os.environ.get("GROCERY_MODELS_DIR", Path(__file__).resolve().parent.parent / "models"))
LLM_PATH = MODELS_DIR / LLM_FILE

# Context window. Recipes are short; a modest window keeps CPU inference quick.
LLM_CONTEXT = 8192
# Cap on the raw text fed to the model (chars) so a giant page can't overflow
# the context; trafilatura's main-content extract is usually well under this.
LLM_MAX_INPUT_CHARS = 16000
# CPU threads for inference. None lets llama.cpp pick (physical core count).
LLM_THREADS: int | None = None


# --- Shopping categories (extract/normalize.py) ----------------------------
# The canonical set of aisles the list is grouped by, in display order. This is
# a knob: reorder to match your store's layout, or add/remove aisles. The LLM
# can only pick from this list (it's an enum in its output schema), and Quality
# Check #2 flags anything outside it. `models.DEFAULT_CATEGORY`
# ("uncategorized") is the "not yet assigned" sentinel, intentionally NOT here.
CATEGORIES = [
    "produce",
    "meat & seafood",
    "dairy & eggs",
    "bakery",
    "pantry",                 # flour, rice, pasta, grains, dried beans
    "canned & jarred",
    "condiments & sauces",    # oils, vinegars, soy/fish sauce, honey, syrup
    "spices & seasonings",
    "baking",                 # sugar, leaveners, vanilla, cocoa
    "frozen",
    "beverages",
    "other",
]

# Different names for the same product, mapped to one shopping name so they
# merge on the list (extract/normalize.py). Keys are matched on the whole
# lowercased name, singular or plural. Names the keyword table doesn't know
# get this treatment from the LLM instead; this list is for common items the
# table does know, which skip the LLM. Add pairs as you notice them.
SYNONYMS = {
    "scallion": "green onion",
    "spring onion": "green onion",
    "coriander leaves": "cilantro",
    "fresh coriander": "cilantro",
    "garbanzo bean": "chickpea",
    "garbanzo": "chickpea",
    "parmigiano reggiano": "parmesan cheese",
    "parmesan": "parmesan cheese",
    "confectioners sugar": "powdered sugar",
    "icing sugar": "powdered sugar",
    "caster sugar": "sugar",
    "white sugar": "sugar",
    "granulated sugar": "sugar",
    "aubergine": "eggplant",
    "courgette": "zucchini",
    "kosher salt": "salt",
    "sea salt": "salt",
    "table salt": "salt",
    "freshly ground black pepper": "black pepper",
    "ground black pepper": "black pepper",
}

# --- Sanity rules (Quality Check #2, aggregate/combine.py) -----------------
# A merged quantity above this is almost certainly a parse error (e.g. a year or
# a stray page number that got read as an amount) — flag it for review.
SANITY_MAX_QUANTITY = 1000

# Ingredients recipes list but you don't actually buy — dropped from the combined
# grocery list (they still appear in the per-recipe breakdown). Matched on the
# exact normalized (lowercased) shopping name, so real products like "coconut
# water" or "rose water" are NOT affected. Add your own as you notice them.
EXCLUDE_FROM_LIST = {
    "water",
    "pasta water",
    "cold water",
    "warm water",
    "hot water",
    "ice water",
    "boiling water",
    "cooking water",
    "reserved pasta water",
    "lukewarm water",
    "room temperature water",
    "tap water",
    "filtered water",
}

# --- Email export (app.py "Email…" button) ---------------------------------
# SMTP creds for emailing the built list. Unset by default — the button is
# disabled with a setup hint until these are provided.
# For Gmail: SMTP_HOST=smtp.gmail.com, SMTP_PORT=587, SMTP_USER=you@gmail.com,
# SMTP_PASSWORD=<16-char app password> (not your login password).
SMTP_HOST = os.environ.get("GROCERY_SMTP_HOST", "")
SMTP_PORT = int(os.environ.get("GROCERY_SMTP_PORT", "587"))
SMTP_USER = os.environ.get("GROCERY_SMTP_USER", "")
SMTP_PASSWORD = os.environ.get("GROCERY_SMTP_PASSWORD", "")
SMTP_FROM = os.environ.get("GROCERY_SMTP_FROM", SMTP_USER)
