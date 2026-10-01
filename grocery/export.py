"""Turn the (edited) grocery list into something to take shopping.

Pipeline position: the last step, after the user has reviewed and edited the
list in the app — `GroceryList` -> plain text / CSV / email. No UI code here,
so these stay easy to test and reuse.
"""

import csv
import io

from grocery import config
from grocery.models import GroceryItem, GroceryList


def format_quantity(quantity: float | None) -> str:
    """2.0 -> "2", 0.5 -> "0.5", None -> ""."""
    if quantity is None:
        return ""
    return str(int(quantity)) if quantity == int(quantity) else f"{quantity:g}"


def sorted_items(grocery: GroceryList) -> list[GroceryItem]:
    """Items in shopping order: by aisle (config.CATEGORIES order), then name."""
    order = {category: i for i, category in enumerate(config.CATEGORIES)}
    return sorted(
        (it for it in grocery.items if it.name.strip()),
        key=lambda it: (order.get(it.category, len(order)), it.name.lower()),
    )


def as_text(grocery: GroceryList, recipe_links: list[tuple[str, str]] = ()) -> str:
    """A readable, aisle-grouped shopping list, with recipe links at the bottom."""
    lines = ["Grocery List"]
    current = None
    for item in sorted_items(grocery):
        if item.category != current:
            lines += ["", item.category.upper()]
            current = item.category
        amount = f"{format_quantity(item.quantity)} {item.unit or ''}".strip()
        text = f"{amount} {item.name}".strip()
        if item.notes:
            text += f" ({item.notes})"
        lines.append(f"  {'[x]' if item.checked else '[ ]'} {text}")
    if recipe_links:
        lines += ["", "Recipes:"] + [f"  - {name}: {url}" for name, url in recipe_links]
    return "\n".join(lines)


def as_csv(grocery: GroceryList, recipe_links: list[tuple[str, str]] = ()) -> str:
    """The list as CSV; recipe links appended as a second small table."""
    buffer = io.StringIO()
    writer = csv.writer(buffer)
    writer.writerow(["Done", "Item", "Quantity", "Unit", "Aisle", "Recipe(s)", "Notes"])
    for it in sorted_items(grocery):
        writer.writerow([it.checked, it.name, format_quantity(it.quantity), it.unit or "",
                         it.category, " / ".join(it.sources), it.notes])
    if recipe_links:
        writer.writerow([])
        writer.writerow(["Recipe", "Link"])
        writer.writerows(recipe_links)
    return buffer.getvalue()


def email_configured() -> bool:
    """True when the SMTP settings in config.py are filled in."""
    return bool(config.SMTP_HOST and config.SMTP_USER and config.SMTP_PASSWORD)


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
