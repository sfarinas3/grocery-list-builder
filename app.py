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
from grocery.models import DEFAULT_CATEGORY, Ingredient
from grocery.pipeline import process_document, process_image, process_url


@st.cache_resource(show_spinner=False)
def get_extractor():
    """The shared extraction backend, chosen by `config.BACKEND`.

    Tries the local model unless BACKEND is "lite"; falls back to the model-free
    `LiteExtractor` when `llama-cpp-python` isn't installed (e.g. on Streamlit
    Cloud). Imports are lazy so the heavy model library is only touched when the
    local backend is actually used.
    """
    if config.BACKEND != "lite":
        try:
            from grocery.extract.local_llm import LocalLLMExtractor

            return LocalLLMExtractor()
        except ImportError:
            if config.BACKEND == "local":
                raise  # explicitly requested the model but it isn't installed

    from grocery.extract.lite import LiteExtractor

    return LiteExtractor()


def running_lite() -> bool:
    """True when the active backend is the model-free LiteExtractor."""
    return type(get_extractor()).__name__ == "LiteExtractor"


@st.cache_resource(show_spinner=False)
def get_vision_extractor():
    """Shared vision model for reading recipe photos. Lazy import (pulls the model
    library) so it's only loaded when someone actually adds a photo."""
    from grocery.extract.vision import VisionExtractor

    return VisionExtractor()


@st.cache_data(show_spinner=False)
def run_pipeline(url: str, use_llm: bool):
    """Cached per (url, use_llm) so re-runs don't re-fetch or re-infer."""
    return process_url(url, get_extractor(), use_llm=use_llm)


@st.cache_data(show_spinner=False)
def run_image(image_bytes: bytes, name: str):
    """Cached per image so re-building doesn't re-run slow vision inference."""
    return process_image(image_bytes, name, get_vision_extractor())


@st.cache_data(show_spinner=False)
def run_document(file_bytes: bytes, filename: str, use_llm: bool):
    """Cached per document so re-building doesn't re-read/re-infer."""
    return process_document(file_bytes, filename, get_extractor(), use_llm=use_llm)


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


def grocery_csv(edited: pd.DataFrame, recipe_links: list[tuple[str, str]]) -> bytes:
    """The current (edited) list as CSV, dropping the internal Review flag column.

    Any recipe source links are appended as a second little table at the bottom.
    """
    body = edited.drop(columns=["Review"], errors="ignore").to_csv(index=False)
    if recipe_links:
        body += "\n" + pd.DataFrame(recipe_links, columns=["Recipe", "Link"]).to_csv(index=False)
    return body.encode("utf-8")


def grocery_text(edited: pd.DataFrame, recipe_links: list[tuple[str, str]]) -> str:
    """The current (edited) list as a readable, aisle-grouped shopping list.

    Recipe source links (for URL recipes) are listed at the bottom.
    """
    order = {category: i for i, category in enumerate(config.CATEGORIES)}
    rows = [r for r in edited.to_dict("records") if str(r.get("Ingredient") or "").strip()]
    rows.sort(key=lambda r: (order.get(r.get("Category"), len(order)), str(r["Ingredient"]).lower()))
    lines = ["Grocery List"]
    current_category = object()  # sentinel so the first real category prints a header
    for row in rows:
        category = row.get("Category") or "uncategorized"
        if category != current_category:
            lines += ["", category.upper()]
            current_category = category
        quantity = None if pd.isna(row.get("Quantity")) else row.get("Quantity")
        amount = f"{_format_quantity(quantity)} {row.get('Unit') or ''}".strip()
        item = f"{amount} {row['Ingredient']}".strip() if amount else str(row["Ingredient"])
        if str(row.get("Notes") or "").strip():
            item += f" ({row['Notes']})"
        lines.append(f"  {'[x]' if row.get('Done') else '[ ]'} {item}")
    if recipe_links:
        lines += ["", "Recipes:"]
        lines += [f"  - {name}: {url}" for name, url in recipe_links]
    return "\n".join(lines)


def send_email(to_addrs: list[str], body: str) -> None:
    """Email the plain-text list to one or more recipients via config.SMTP_*."""
    import smtplib
    from email.mime.text import MIMEText

    msg = MIMEText(body)
    msg["Subject"] = "Grocery List"
    msg["From"] = config.SMTP_FROM
    msg["To"] = ", ".join(to_addrs)
    with smtplib.SMTP(config.SMTP_HOST, config.SMTP_PORT) as server:
        server.starttls()
        server.login(config.SMTP_USER, config.SMTP_PASSWORD)
        server.sendmail(config.SMTP_FROM, to_addrs, msg.as_string())


st.set_page_config(page_title="Grocery List Builder", page_icon="🛒")
st.title("🛒 Grocery List Builder")
st.caption("Paste one or more recipe links — I'll pull out the ingredients. Runs entirely on your machine.")

with st.sidebar:
    st.header("Settings")
    if running_lite():
        use_llm = False
        st.info(
            "**Lite mode** — no local model. Recipes with structured data work fully; "
            "pages without it can't be auto-extracted here, and unusual ingredients "
            "are categorized by lookup only. Run locally for full extraction."
        )
    else:
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

# Photo input (needs the vision model, so only in full mode, not lite). Photos
# accumulate in session state so the user can add several — camera_input is
# single-shot, so each capture is appended via an "Add" button, which also resets
# the widget (via a bumped key) for the next one. Camera access needs a secure
# context — works on localhost; a deployed server would need HTTPS.
IMAGE_LIMIT = 10

if not running_lite():
    st.session_state.setdefault("photos", [])  # list of (label, bytes)
    st.session_state.setdefault("img_round", 0)  # bump to reset camera/uploader widgets
    photos = st.session_state.photos

    with st.expander(f"📷 Add recipes from photos (up to {IMAGE_LIMIT})"):
        st.caption(
            f"{len(photos)}/{IMAGE_LIMIT} added. Reading a photo runs a vision model on CPU, "
            "so it's slow (~a minute each)."
        )
        if photos:
            columns = st.columns(5)
            for i, (label, data) in enumerate(photos):
                columns[i % 5].image(data, caption=label, use_container_width=True)
            if st.button("🗑️ Clear photos"):
                st.session_state.photos = []
                st.session_state.img_round += 1
                st.rerun()

        if len(photos) >= IMAGE_LIMIT:
            st.info(f"Photo limit reached ({IMAGE_LIMIT}). Clear some to add more.")
        else:
            camera_tab, upload_tab = st.tabs(["📸 Take a photo", "📁 Upload"])
            with camera_tab:
                shot = st.camera_input("Camera", key=f"cam{st.session_state.img_round}", label_visibility="collapsed")
                if st.button("➕ Add this photo", disabled=shot is None):
                    photos.append((f"Photo {len(photos) + 1}", shot.getvalue()))
                    st.session_state.img_round += 1
                    st.rerun()
            with upload_tab:
                uploads = st.file_uploader(
                    "Upload", type=["png", "jpg", "jpeg", "webp"],
                    accept_multiple_files=True, key=f"up{st.session_state.img_round}",
                    label_visibility="collapsed",
                )
                if st.button("➕ Add these", disabled=not uploads):
                    for uploaded_file in uploads[: IMAGE_LIMIT - len(photos)]:
                        photos.append((uploaded_file.name, uploaded_file.getvalue()))
                    st.session_state.img_round += 1
                    st.rerun()

image_inputs = st.session_state.get("photos", [])

# Document input (PDF / Word / text) — same accumulate-then-build pattern as
# photos. Reading a document uses the text model, so full mode only.
DOC_LIMIT = 10
if not running_lite():
    st.session_state.setdefault("docs", [])  # list of (filename, bytes)
    st.session_state.setdefault("doc_round", 0)
    docs = st.session_state.docs

    with st.expander(f"📄 Add recipes from documents — PDF, Word, or text (up to {DOC_LIMIT})"):
        st.caption(f"{len(docs)}/{DOC_LIMIT} added. Reads the text, then uses the model to pull out ingredients.")
        if docs:
            for position, (fname, _) in enumerate(docs, start=1):
                st.write(f"{position}. {fname}")
            if st.button("🗑️ Clear documents"):
                st.session_state.docs = []
                st.session_state.doc_round += 1
                st.rerun()

        if len(docs) >= DOC_LIMIT:
            st.info(f"Document limit reached ({DOC_LIMIT}). Clear some to add more.")
        else:
            doc_uploads = st.file_uploader(
                "Upload documents", type=["pdf", "txt", "md", "docx"],
                accept_multiple_files=True, key=f"doc{st.session_state.doc_round}",
                label_visibility="collapsed",
            )
            if st.button("➕ Add these documents", disabled=not doc_uploads):
                for uploaded_file in doc_uploads[: DOC_LIMIT - len(docs)]:
                    docs.append((uploaded_file.name, uploaded_file.getvalue()))
                st.session_state.doc_round += 1
                st.rerun()

document_inputs = st.session_state.get("docs", [])

# Build on click. The result is stashed as a seed DataFrame in session state; a
# bumped `build_id` gives the editor a fresh key so a new build starts clean
# (rather than the keyed widget replaying edits from the previous list).
if st.button("Build list", type="primary"):
    urls = parse_urls(urls_text)
    if not urls and not image_inputs and not document_inputs:
        st.warning("Paste a recipe URL (http:// or https://), or add a photo or document, to build a list.")

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

        if not ingredients:
            if running_lite():
                st.warning(
                    f"No structured recipe data at {url}, and the AI model isn't available "
                    "in lite mode — skipped. Try a site with a structured recipe, or run locally."
                )
            else:
                st.warning(f"No ingredients found for {url} — the page may be unsupported.")
            continue

        results.append((content.title or url, content, ingredients))

    # Recipe photos → the vision model reads each into ingredient lines.
    for name, image_bytes in image_inputs:
        with st.spinner(f"Reading {name} … (vision model on CPU — this can take ~a minute)"):
            try:
                content, ingredients = run_image(image_bytes, name)
            except Exception as exc:  # noqa: BLE001 — surface anything else to the user
                st.error(f"Couldn't read {name}: {exc}")
                continue
        if not ingredients:
            st.warning(f"No ingredients read from {name} — try a clearer photo.")
            continue
        results.append((name, content, ingredients))

    # Recipe documents → read text, then the model pulls ingredient lines.
    for filename, file_bytes in document_inputs:
        with st.spinner(f"Reading {filename} …"):
            try:
                content, ingredients = run_document(file_bytes, filename, use_llm)
            except Exception as exc:  # noqa: BLE001 — surface anything else to the user
                st.error(f"Couldn't read {filename}: {exc}")
                continue
        if not ingredients:
            st.warning(f"No ingredients found in {filename} — it may not contain a recognizable recipe.")
            continue
        results.append((filename, content, ingredients))

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

    recipe_links = [
        (name, content.source_url)
        for name, content, _ in st.session_state.get("recipe_results", [])
        if content.source_url
    ]
    csv_col, txt_col = st.columns(2)
    csv_col.download_button(
        "⬇️ Download CSV", grocery_csv(edited, recipe_links), file_name="grocery_list.csv",
        mime="text/csv", use_container_width=True,
    )
    txt_col.download_button(
        "⬇️ Download text", grocery_text(edited, recipe_links), file_name="grocery_list.txt",
        mime="text/plain", use_container_width=True,
    )
    st.caption("⚠️ = flagged when built (low confidence, mixed units, or uncategorized).")

    with st.expander("📧 Email this list"):
        if not (config.SMTP_HOST and config.SMTP_USER and config.SMTP_PASSWORD):
            st.caption(
                "Not configured — set GROCERY_SMTP_HOST/PORT/USER/PASSWORD env vars "
                "(see grocery/config.py) to enable sending."
            )
        else:
            addrs_input = st.text_input("To (comma-separated for multiple)", key="email_addrs")
            if st.button("Send", disabled=not addrs_input.strip()):
                to_addrs = [a.strip() for a in addrs_input.split(",") if a.strip()]
                try:
                    send_email(to_addrs, grocery_text(edited, recipe_links))
                    st.success(f"Sent to {', '.join(to_addrs)}")
                except Exception as e:
                    st.error(f"Failed to send: {e}")

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
