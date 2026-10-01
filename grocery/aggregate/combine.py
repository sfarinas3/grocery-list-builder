"""Merge ingredients from several recipes into one grocery list (design §3.4).

The rule, kept deliberately simple (correctness over cleverness in v1):
  - One line per shopping name (case-insensitive) — set by the LLM in
    `extract/normalize.py`, so "yellow onions" and "onion" land on one line.
    The recipes' own wording is kept in the notes so a merge can be checked.
  - Amounts are summed when the contributions share a unit (folding trivial
    plurals, so "clove" + "cloves" combine). We do NOT convert between units
    (cups↔grams etc.); a genuinely mixed set of units is shown as a breakdown in
    the notes and flagged for review rather than guessed.
  - Each line records the recipe(s) it came from.

Also applies Quality Check #2 — deterministic sanity flags on the assembled list.
"""

import re

from grocery import config
from grocery.models import DEFAULT_CATEGORY, GroceryItem, GroceryList, Ingredient


def combine(recipes: list[tuple[str, list[Ingredient]]]) -> GroceryList:
    """Merge `(recipe_name, ingredients)` pairs into one consolidated list."""
    groups: dict[str, list[tuple[str, Ingredient]]] = {}
    order: list[str] = []  # preserve first-seen order for stable output
    for recipe_name, ingredients in recipes:
        for ing in ingredients:
            key = _merge_key(_shopping_name(ing)) or "(unnamed)"
            if _shopping_name(ing).lower() in config.EXCLUDE_FROM_LIST:
                continue  # e.g. plain/pasta water — not a thing you buy
            if key not in groups:
                groups[key] = []
                order.append(key)
            groups[key].append((recipe_name, ing))

    items = [_merge(groups[key]) for key in order]
    _apply_sanity_checks(items)
    return GroceryList(items=items)


def _merge(entries: list[tuple[str, Ingredient]]) -> GroceryItem:
    """Collapse all contributions of one ingredient into a single line."""
    ingredients = [ing for _, ing in entries]
    quantity, unit, amount_note, mixed_units = _combine_amounts(ingredients)

    # Category from the first contribution that actually got categorized.
    name = _shopping_name(ingredients[0])
    category = next(
        (ing.category for ing in ingredients if ing.category != DEFAULT_CATEGORY),
        DEFAULT_CATEGORY,
    )

    prep_notes = _unique(ing.notes for ing in ingredients if ing.notes)
    variants = _unique(ing.name for ing in ingredients if ing.name.lower() != name.lower())
    variant_note = ["as: " + ", ".join(variants)] if variants else []
    notes = "; ".join(([amount_note] if amount_note else []) + variant_note + prep_notes)

    flags = _unique(flag for ing in ingredients for flag in ing.flags)
    if mixed_units:
        flags.append("mixed_units")

    return GroceryItem(
        name=name,
        quantity=quantity,
        unit=unit,
        category=category,
        notes=notes,
        sources=_unique(recipe_name for recipe_name, _ in entries),
        flags=flags,
    )


def _shopping_name(ing: Ingredient) -> str:
    """The name to shop by — the normalized one if set, else the recipe's own."""
    return (ing.shopping_name or ing.name).strip()


def _merge_key(name: str) -> str:
    """Lowercase and fold plurals word by word, so "onions" and "onion" merge.

    Only used as a grouping key (never displayed), so it just has to be
    consistent, not good English: "molasses" -> "molass" on both sides is fine.
    """
    words = re.sub(r"[^a-z0-9 ]", " ", name.lower()).split()
    return " ".join(_singular(w) for w in words)


def _singular(word: str) -> str:
    """berries -> berry, tomatoes -> tomato, onions -> onion; "hummus"/"couscous" kept."""
    word = re.sub(r"ies$", "y", word)
    word = re.sub(r"oes$", "o", word)
    return re.sub(r"(?<=[^su])s$", "", word)


def _combine_amounts(ingredients: list[Ingredient]) -> tuple[float | None, str | None, str, bool]:
    """Return (quantity, unit, amount_note, mixed_units) for a merged ingredient.

    Contributions with no amount at all (e.g. "to taste") don't affect the unit
    decision — their wording is preserved in the prep notes instead.
    """
    amountful = [ing for ing in ingredients if ing.quantity is not None or (ing.unit or "").strip()]
    if not amountful:
        return None, None, "", False

    by_unit: dict[str, list[Ingredient]] = {}
    unit_order: list[str] = []
    for ing in amountful:
        key = _normalize_unit(ing.unit)
        if key not in by_unit:
            by_unit[key] = []
            unit_order.append(key)
        by_unit[key].append(ing)

    if len(unit_order) == 1:  # all one unit -> sum
        members = by_unit[unit_order[0]]
        quantities = [m.quantity for m in members if m.quantity is not None]
        total = float(sum(quantities)) if quantities else None
        return total, _display_unit(members), "", False

    # Mixed units: don't fabricate a conversion — show the breakdown, flag it.
    parts = [_format_amount(m.quantity, m.unit) for key in unit_order for m in by_unit[key]]
    parts = [p for p in parts if p]
    return None, None, ("amounts: " + " + ".join(parts) if parts else ""), True


def _normalize_unit(unit: str | None) -> str:
    """Lowercase and fold a single trailing 's' so 'cloves' and 'clove' match."""
    return re.sub(r"s$", "", (unit or "").strip().lower())


def _display_unit(members: list[Ingredient]) -> str | None:
    """Use the first non-empty original unit spelling for display."""
    return next((m.unit for m in members if (m.unit or "").strip()), None)


def _format_amount(quantity: float | None, unit: str | None) -> str:
    qty = _format_quantity(quantity)
    return f"{qty} {unit}".strip() if unit else qty


def _format_quantity(quantity: float | None) -> str:
    if quantity is None:
        return ""
    return str(int(quantity)) if quantity == int(quantity) else f"{quantity:g}"


def _unique(values) -> list[str]:
    """De-duplicate while preserving first-seen order."""
    seen: set[str] = set()
    out: list[str] = []
    for value in values:
        if value not in seen:
            seen.add(value)
            out.append(value)
    return out


# --- Quality Check #2: deterministic sanity rules -------------------------

def _apply_sanity_checks(items: list[GroceryItem]) -> None:
    """Flag obviously-wrong lines so the UI surfaces them for review."""
    for item in items:
        if not item.name.strip():
            _flag(item, "empty_name")
        if item.quantity is not None:
            if item.quantity <= 0:
                _flag(item, "bad_quantity")
            elif item.quantity > config.SANITY_MAX_QUANTITY:
                _flag(item, "absurd_quantity")
        if item.category not in config.CATEGORIES:  # includes the "uncategorized" sentinel
            _flag(item, "needs_category")


def _flag(item: GroceryItem, flag: str) -> None:
    if flag not in item.flags:
        item.flags.append(flag)
