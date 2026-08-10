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


# --- Local LLM (docs/design.md §3.3) --------------------------------------
# Weights live in models/ (gitignored), downloaded once on first run. We fetch
# from ModelScope rather than Hugging Face because the corporate Zscaler proxy
# blocks HF's and Ollama's model CDNs (see docs/design.md §6) — ModelScope's CDN
# is reachable, and it hosts the Qwen models first-party.
#
# To try a different model, change these two lines (repo, file) — nothing else
# in the code changes. They can also be overridden by env vars so a deployment
# (e.g. the Hugging Face Space) can pick a smaller/faster model without touching
# the code; locally, the defaults below are used.
MODELSCOPE_REPO = os.environ.get("GROCERY_LLM_REPO", "Qwen/Qwen2.5-7B-Instruct-GGUF")
LLM_FILE = os.environ.get("GROCERY_LLM_FILE", "qwen2.5-7b-instruct-q4_k_m.gguf")
LLM_URL = f"https://modelscope.cn/models/{MODELSCOPE_REPO}/resolve/master/{LLM_FILE}"

# Where weights are cached. Override with GROCERY_MODELS_DIR on hosts where the
# project dir isn't writable (the Space writes to a baked-in /app/models).
MODELS_DIR = Path(os.environ.get("GROCERY_MODELS_DIR", Path(__file__).resolve().parent.parent / "models"))
LLM_PATH = MODELS_DIR / LLM_FILE

# --- Vision model: recipe photos -> ingredients (docs/design.md, Approach B) ---
# A multimodal GGUF (Qwen2.5-VL) + its image projector (mmproj), run via
# llama-cpp-python's Qwen25VLChatHandler. Reads a photo and returns ingredient
# lines, which then flow through the same parse/categorize/merge pipeline.
# Both files download from ModelScope on first use (HF CDN is proxy-blocked).
VISION_REPO = "ggml-org/Qwen2.5-VL-3B-Instruct-GGUF"
VISION_FILE = "Qwen2.5-VL-3B-Instruct-Q4_K_M.gguf"
VISION_MMPROJ_FILE = "mmproj-Qwen2.5-VL-3B-Instruct-f16.gguf"
VISION_URL = f"https://modelscope.cn/models/{VISION_REPO}/resolve/master/{VISION_FILE}"
VISION_MMPROJ_URL = f"https://modelscope.cn/models/{VISION_REPO}/resolve/master/{VISION_MMPROJ_FILE}"
VISION_PATH = MODELS_DIR / VISION_FILE
VISION_MMPROJ_PATH = MODELS_DIR / VISION_MMPROJ_FILE
VISION_CONTEXT = 8192  # images consume many tokens; give the context room

# Which extraction backend to use:
#   "auto"  — use the local model if `llama-cpp-python` is installed, else lite
#   "local" — require the local model (error if it isn't installed)
#   "lite"  — model-free (JSON-LD + lookup only); for low-RAM hosts like the
#             free Streamlit Community Cloud tier
BACKEND = os.environ.get("GROCERY_BACKEND", "auto")

# Context window. Recipes are short; a modest window keeps CPU inference quick.
LLM_CONTEXT = 8192
# Cap on the readable text we feed the model (chars). Guards against a giant
# page overflowing the context; trafilatura's main-content extract is usually
# well under this.
LLM_MAX_INPUT_CHARS = 16000


# --- Shopping categories (docs/design.md §3.3) ----------------------------
# The canonical set of aisles the list is grouped by. This is a knob: reorder to
# match your store's layout, or add/remove aisles. Quality Check #2 flags any
# category outside this list; the keyword→aisle mapping lives in categorize.py.
# `models.DEFAULT_CATEGORY` ("uncategorized") is the sentinel for "not yet
# assigned" and is intentionally NOT in this list.
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

# --- Sanity rules (Quality Check #2, docs/design.md §3.4) -----------------
# A merged quantity above this is almost certainly a parse error (e.g. a year or
# a stray page number that got read as an amount) — flag it for review.
SANITY_MAX_QUANTITY = 1000

# Ingredients recipes list but you don't actually buy — dropped from the combined
# grocery list (they still appear in the per-recipe breakdown). Matched on the
# exact normalized (lowercased) ingredient name, so real products like "coconut
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

# --- Email export (app.py "Send email" button) ----------------------------
# SMTP creds for emailing the built list to one or more addresses. Unset by
# default — the button is disabled with a setup hint until these are provided.
# For Gmail: SMTP_HOST=smtp.gmail.com, SMTP_PORT=587, SMTP_USER=you@gmail.com,
# SMTP_PASSWORD=<16-char app password> (not your login password).
SMTP_HOST = os.environ.get("GROCERY_SMTP_HOST", "")
SMTP_PORT = int(os.environ.get("GROCERY_SMTP_PORT", "587"))
SMTP_USER = os.environ.get("GROCERY_SMTP_USER", "")
SMTP_PASSWORD = os.environ.get("GROCERY_SMTP_PASSWORD", "")
SMTP_FROM = os.environ.get("GROCERY_SMTP_FROM", SMTP_USER)
