"""Rejects lines/names that look like device or app UI chrome, recipe-page
metadata, or a cooking temperature rather than an actual grocery ingredient —
e.g. "Partly Sunny" (a phone status-bar weather widget) or "82 degrees Celsius"
(an oven temperature), both of which can end up as their own OCR'd line right
next to real ingredients when a photo captures a phone/website screen.

A curated word/phrase list, not a general classifier — it catches the concrete
failure modes seen in practice, not everything imaginable. Ported from the
Android app's equivalent (GroceryNoiseFilter.kt), which hit these exact cases
during real on-device testing.
"""

import re

# Phone/OS UI chrome a photo of a screen can capture alongside the recipe --
# never legitimate grocery text.
_UI_CHROME_WORDS = {
    "search", "menu", "settings", "notification", "notifications", "wifi", "wi-fi",
    "bluetooth", "battery", "signal", "airplane", "hotspot", "brightness", "volume",
    "back", "home", "share", "bookmark", "widget", "app", "apps",
}
_UI_CHROME_PHRASES = {"log in", "sign in", "sign up", "log out"}

# Weather-widget conditions -- only ever noise here, never an ingredient.
_WEATHER_WORDS = {
    "sunny", "cloudy", "rainy", "snowy", "windy", "stormy", "foggy", "overcast",
    "humidity", "forecast", "precipitation", "partly", "mostly",
}

# Recipe-page metadata that often sits right next to (or gets OCR'd into the
# middle of) an ingredients section without itself being an ingredient.
_RECIPE_METADATA_WORDS = {
    "serves", "servings", "yield", "yields", "calories", "nutrition", "nutritional",
    "difficulty", "rating", "ratings", "review", "reviews", "advertisement",
}
_RECIPE_METADATA_PHRASES = {
    "prep time", "cook time", "total time", "ready in", "print recipe", "save recipe",
    "jump to recipe",
}

# Cooking temperatures ("82°C", "350 degrees F") describe *how* to cook
# something, not what to buy -- never a grocery item. Requires "degrees"/"°"
# explicitly, so this can't misfire on a quantity+unit like "2 c flour".
_TEMPERATURE_PATTERN = re.compile(
    r"\d+\s*°\s*[cf]\b|\d+\s*degrees?\s*(celsius|fahrenheit|[cf])?\b", re.IGNORECASE
)
_TEMPERATURE_PHRASES = {"internal temp", "internal temperature", "oven temperature"}

_NOISE_WORDS = _UI_CHROME_WORDS | _WEATHER_WORDS | _RECIPE_METADATA_WORDS
_NOISE_PHRASES = _UI_CHROME_PHRASES | _RECIPE_METADATA_PHRASES | _TEMPERATURE_PHRASES


def is_likely_noise(text: str) -> bool:
    """True if `text` (a candidate line, or a parsed ingredient name) looks like
    UI chrome, recipe metadata, or a cooking temperature rather than an
    ingredient."""
    normalized = text.lower().strip()
    if not normalized:
        return False

    if any(phrase in normalized for phrase in _NOISE_PHRASES):
        return True
    if _TEMPERATURE_PATTERN.search(normalized):
        return True

    words = [w.strip(",.:;!?") for w in normalized.split()]
    return any(w in _NOISE_WORDS for w in words)
