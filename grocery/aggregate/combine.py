"""Merge ingredients from several recipes into one grocery list (design §3.4).

The rule, kept deliberately simple (correctness over cleverness in v1):
  - One line per ingredient name (case-insensitive).
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
            key = ing.name.strip().lower() or "(unnamed)"
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

    # Keep the first recipe's spelling of the name; category from the first
    # contribution that actually got categorized.
    name = ingredients[0].name
    category = next(
        (ing.category for ing in ingredients if ing.category != DEFAULT_CATEGORY),
        DEFAULT_CATEGORY,
    )

    prep_notes = _unique(ing.notes for ing in ingredients if ing.notes)
    notes = "; ".join(([amount_note] if amount_note else []) + prep_notes)

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
