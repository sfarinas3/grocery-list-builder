"""Streamlit UI (docs/design.md §3.5).

Paste one or more recipe URLs, build a consolidated grocery list, then edit it:
tick items off, fix quantities/categories/names, and add or remove rows. The
editable table is the human-review safety net (Quality Check #3). Its state lives
in the widget (keyed) and survives the reruns that editing triggers; a fresh
"Build list" resets it via a bumped key.

Run with:  uv run streamlit run app.py
"""

import re

import pandas as pd
import streamlit as st

from grocery import config
from grocery.aggregate.combine import combine
from grocery.ingest.web import IngestError
from grocery.extract.local_llm import LocalLLMExtractor
from grocery.models import DEFAULT_CATEGORY, Ingredient
from grocery.pipeline import process_url


@st.cache_resource(show_spinner=False)
def get_extractor() -> LocalLLMExtractor:
    """One shared model-backed extractor, kept loaded across Streamlit reruns."""
    return LocalLLMExtractor()


@st.cache_data(show_spinner=False)
def run_pipeline(url: str, use_llm: bool):
    """Cached per (url, use_llm) so re-runs don't re-fetch or re-infer."""
    return process_url(url, get_extractor(), use_llm=use_llm)


def parse_urls(text: str) -> list[str]:
    """Pull recipe URLs out of free text, separated by newlines, commas, or spaces.

    Forgiving on purpose — paste a whole block however it's formatted. Keeps only
    http(s) links and de-duplicates while preserving order.
    """
    seen: set[str] = set()
    urls: list[str] = []
    for token in re.split(r"[\s,]+", text.strip()):
        if token.startswith(("http://", "https://")) and token not in seen:
            seen.add(token)
            urls.append(token)
    return urls


def _format_quantity(quantity: float | None) -> str:
    if quantity is None:
        return ""
    return str(int(quantity)) if quantity == int(quantity) else f"{quantity:g}"


def ingredients_table(ingredients: list[Ingredient]) -> pd.DataFrame:
    """Read-only per-recipe table, rows ordered by shopping aisle then name."""
    order = {category: i for i, category in enumerate(config.CATEGORIES)}
    rows = sorted(ingredients, key=lambda ing: (order.get(ing.category, len(order)), ing.name.lower()))
    return pd.DataFrame(
        [
            {
                "Ingredient": ing.name,
                "Quantity": _format_quantity(ing.quantity),
                "Unit": ing.unit or "",
                "Category": ing.category,
                "Notes": ing.notes,
                "Review": "⚠️" if ing.flags else "",
            }
            for ing in rows
        ],
        columns=["Ingredient", "Quantity", "Unit", "Category", "Notes", "Review"],
    )


def editor_dataframe(items) -> pd.DataFrame:
    """Seed table for the editable grocery list (Quantity stays numeric)."""
    df = pd.DataFrame(
        [
            {
                "Done": it.checked,
                "Ingredient": it.name,
                "Quantity": it.quantity,
                "Unit": it.unit or "",
                "Category": it.category,
                "Recipe(s)": " / ".join(it.sources),
                "Notes": it.notes,
                "Review": "⚠️" if it.flags else "",
            }
            for it in items
        ],
        columns=["Done", "Ingredient", "Quantity", "Unit", "Category", "Recipe(s)", "Notes", "Review"],
    )
    # Ensure Quantity is a numeric column so the NumberColumn editor behaves.
    df["Quantity"] = pd.to_numeric(df["Quantity"], errors="coerce")
    return df


st.set_page_config(page_title="Grocery List Builder", page_icon="🛒")
st.title("🛒 Grocery List Builder")
st.caption("Paste one or more recipe links — I'll pull out the ingredients. Runs entirely on your machine.")

with st.sidebar:
    st.header("Settings")
    use_llm = st.checkbox(
        "Use the local AI model for unknown categories",
        value=True,
        help="The model is also required for pages that lack structured recipe data. "
        "The first time it runs, it loads the model (~30s on this machine).",
    )
    st.caption(f"Model: `{config.LLM_FILE}`")

urls_text = st.text_area(
    "Recipe URLs",
    height=140,
    placeholder=(
        "Paste one or more links, separated by new lines, commas, or spaces:\n\n"
        "https://www.budgetbytes.com/spaghetti-aglio-e-olio/\n"
        "https://www.budgetbytes.com/garlic-noodles/"
    ),
    help="New lines, commas, or spaces all work.",
)

# Build on click. The result is stashed as a seed DataFrame in session state; a
# bumped `build_id` gives the editor a fresh key so a new build starts clean
# (rather than the keyed widget replaying edits from the previous list).
if st.button("Build list", type="primary"):
    urls = parse_urls(urls_text)
    if not urls:
        st.warning("Paste at least one recipe URL above (must start with http:// or https://).")

    results = []  # (recipe_name, RecipeContent, ingredients) for each URL that succeeded
    for url in urls:
        with st.spinner(f"Processing {url} …"):
            try:
                content, ingredients = run_pipeline(url, use_llm)
            except IngestError as exc:
                st.error(str(exc))
                continue
            except Exception as exc:  # noqa: BLE001 — surface anything else to the user
                st.error(f"Unexpected error for {url}: {exc}")
                continue
        results.append((content.title or url, content, ingredients))

    if results:
        grocery_list = combine([(name, ingredients) for name, _, ingredients in results])
        aisle = {category: i for i, category in enumerate(config.CATEGORIES)}
        grocery_list.items.sort(key=lambda it: (aisle.get(it.category, len(aisle)), it.name.lower()))
        st.session_state.editor_base = editor_dataframe(grocery_list.items)
        st.session_state.recipe_results = results
        st.session_state.build_id = st.session_state.get("build_id", 0) + 1


if "editor_base" in st.session_state:
    st.header("🧾 Grocery list")
    st.caption("Edit any cell — quantity, category, name, notes. Tick ✓ to shop; use the table's toolbar to add or delete rows.")

    edited = st.data_editor(
        st.session_state.editor_base,
        key=f"grocery_editor_{st.session_state.build_id}",
        num_rows="dynamic",
        hide_index=True,
        use_container_width=True,
        column_config={
            "Done": st.column_config.CheckboxColumn("✓", help="Tick items off as you shop"),
            "Quantity": st.column_config.NumberColumn("Qty", min_value=0.0, format="%g"),
            "Category": st.column_config.SelectboxColumn(
                "Category", options=[*config.CATEGORIES, DEFAULT_CATEGORY]
            ),
            "Review": st.column_config.TextColumn(" ", width="small"),
        },
        disabled=["Recipe(s)", "Review"],  # provenance + flags aren't hand-edited
    )

    total = len(edited)
    checked = int(edited["Done"].sum()) if total else 0
    st.progress(checked / total if total else 0.0, text=f"{checked} of {total} items checked")
    st.caption("⚠️ = flagged when built (low confidence, mixed units, or uncategorized).")

    with st.expander("Per-recipe breakdown"):
        for name, content, ingredients in st.session_state.get("recipe_results", []):
            st.subheader(name)
            source = "structured recipe data" if content.ingredient_lines else "AI-extracted from page text"
            bits = [f"{len(ingredients)} ingredients", source]
            if content.servings:
                bits.insert(0, f"serves {content.servings}")
            st.caption(" · ".join(bits))
            st.dataframe(ingredients_table(ingredients), hide_index=True, use_container_width=True)

    # Keep the source links handy so the shopper can pull a recipe back up later.
    st.divider()
    st.subheader("Recipes")
    for name, content, _ in st.session_state.get("recipe_results", []):
        if content.source_url:
            st.markdown(f"- [{name}]({content.source_url})")
        else:
            st.markdown(f"- {name}")
