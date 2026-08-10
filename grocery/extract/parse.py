"""Parse a raw ingredient line into a structured `Ingredient`.

This is the accuracy-critical step (docs/design.md §3.2). It delegates the hard
"2 cloves garlic, minced" -> fields split to `ingredient-parser`, a trained CRF
model purpose-built for ingredient phrases — far more reliable at this than a
small general LLM. Category is deliberately left unset here; assigning shopping
aisles is a separate step (docs/design.md §3.3 / build step 4).

Note: `ingredient_parser` downloads a small NLTK data file on first use, and
loading the CRF model makes the first `parse_line` call slower than later ones.
"""

from ingredient_parser import parse_ingredient

from grocery.models import Ingredient


def parse_lines(lines: list[str], *, source_url: str | None = None) -> list[Ingredient]:
    """Parse many ingredient lines. One `Ingredient` per line."""
    return [parse_line(line, source_url=source_url) for line in lines]


def parse_line(line: str, *, source_url: str | None = None) -> Ingredient:
    """Parse a single ingredient line into an `Ingredient` (category left as default)."""
    parsed = parse_ingredient(line)

    # `name` is a list (a line can name more than one ingredient, e.g. "salt and
    # pepper"). v1 keeps one Ingredient per line and joins the names so nothing
    # is silently dropped; the human-review step can split it if needed. If the
    # CRF found no name, fall back to the raw line and flag it for review.
    name = ", ".join(n.text for n in parsed.name).strip()
    flags: list[str] = []
    if not name:
        name = line.strip()
        flags.append("unparsed_name")

    quantity, unit = _first_amount(parsed.amount)
    notes = _join_notes(parsed.preparation, parsed.comment)

    # Confidence tracks the *name* — that's what you actually shop by, so it's
    # the signal worth surfacing. No name parsed -> zero confidence.
    confidence = round(parsed.name[0].confidence, 4) if parsed.name else 0.0

    return Ingredient(
        name=name,
        quantity=quantity,
        unit=unit,
        notes=notes,
        source_url=source_url,
        confidence=confidence,
        flags=flags,
    )


# --- Field extraction helpers ---------------------------------------------

def _first_amount(amounts: list) -> tuple[float | None, str | None]:
    """Take the first parsed amount as (quantity, unit).

    v1 keeps only the first amount — compound amounts like "1 cup plus 2 tbsp"
    are rare and better handled by human review than by guessing a merge.
    An unquantified line ("salt to taste") has no amounts -> (None, None).
    """
    if not amounts:
        return None, None
    amount = amounts[0]
    return _to_float(amount.quantity), _clean_unit(amount.unit)


def _to_float(quantity: object) -> float | None:
    """Quantities come back as `Fraction` (e.g. 1/2); coerce to float, or None."""
    if quantity is None or quantity == "":
        return None
    try:
        return float(quantity)  # handles Fraction, int, float, and numeric strings
    except (TypeError, ValueError):
        return None


def _clean_unit(unit: object) -> str | None:
    """Units are usually plain strings; coerce and treat empty as None."""
    text = str(unit).strip() if unit is not None else ""
    return text or None


def _join_notes(*fields: object) -> str:
    """Combine the parser's `preparation` and `comment` into one notes string.

    This is where prep instructions ("minced") and stray asides land — including
    the price tags some sites append to ingredient lines ("($0.98)"), keeping
    them out of the name and quantity.
    """
    parts = [f.text.strip() for f in fields if f is not None and f.text.strip()]
    return ", ".join(parts)
