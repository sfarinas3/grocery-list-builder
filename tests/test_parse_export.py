from grocery import export
from grocery.extract.parse import parse_lines
from grocery.models import GroceryItem, GroceryList


def test_unquantified_multi_name_line_splits():
    assert [i.name for i in parse_lines(["salt and pepper to taste"])] == ["salt", "pepper"]


def test_quantified_alternatives_stay_one_item():
    ings = parse_lines(["1 cup milk or cream"])
    assert [(i.name, i.quantity) for i in ings] == [("milk or cream", 1.0)]


def test_trailing_punctuation_stripped_from_names():
    assert parse_lines(["1 cup chicken broth."])[0].name == "chicken broth"


def test_text_export_groups_by_aisle_in_config_order():
    grocery = GroceryList(items=[
        GroceryItem(name="salt", category="spices & seasonings"),
        GroceryItem(name="garlic", quantity=3, unit="cloves", category="produce", checked=True),
    ])
    text = export.as_text(grocery, [("Chili", "https://example.com")])
    assert text.index("PRODUCE") < text.index("SPICES & SEASONINGS")
    assert "[x] 3 cloves garlic" in text
    assert "Chili: https://example.com" in text
