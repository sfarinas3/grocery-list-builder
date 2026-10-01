"""Parse a raw ingredient line into a structured `Ingredient`.

This is the accuracy-critical step (docs/design.md §3.2). It delegates the hard
"2 cloves garlic, minced" -> fields split to `ingredient-parser`, a trained CRF
model purpose-built for ingredient phrases — far more reliable at this than a
small general LLM. Shopping name and aisle are deliberately left unset here;
that's `extract/normalize.py`'s job.

Note: `ingredient_parser` downloads a small NLTK data file on first use, and
loading the CRF model makes the first `parse_line` call slower than later ones.
"""

from ingredient_parser import parse_ingredient

from grocery.models import Ingredient


def parse_lines(lines: list[str], *, source_url: str | None = None) -> list[Ingredient]:
    """Parse many ingredient lines (a line can yield more than one `Ingredient`)."""
    return [ing for line in lines for ing in parse_line(line, source_url=source_url)]


def parse_line(line: str, *, source_url: str | None = None) -> list[Ingredient]:
    """Parse a single ingredient line (shopping name and category left unset)."""
    parsed = parse_ingredient(line)
    quantity, unit = _first_amount(parsed.amount)
    notes = _join_notes(parsed.preparation, parsed.comment)

    # The CRF returns a list of names because a line can name several things.
    # With no amount ("salt and pepper to taste") they're separate items to buy.
    # With an amount ("1 cup milk or cream") they're usually alternatives
    # sharing it, so keep one item rather than double-buying.
    names = [_clean_name(n.text) for n in parsed.name if _clean_name(n.text)]
    if len(names) > 1 and quantity is None and unit is None:
        groups = [[n] for n in names]
    else:
        groups = [names] if names else []

    if not groups:  # CRF found no name: keep the raw line and flag it for review
        return [Ingredient(name=line.strip(), notes=notes, source_url=source_url,
                           confidence=0.0, flags=["unparsed_name"])]

    # Confidence tracks the *name* — that's what you actually shop by, so it's
    # the signal worth surfacing.
    confidence = round(min(n.confidence for n in parsed.name), 4)
    return [
        Ingredient(name=" or ".join(group), quantity=quantity, unit=unit, notes=notes,
                   source_url=source_url, confidence=confidence)
        for group in groups
    ]


def _clean_name(text: str) -> str:
    """Strip stray punctuation the CRF leaves on names ("chicken broth.", "sauce*")."""
    return text.strip().strip(".,;:*†").strip()


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
