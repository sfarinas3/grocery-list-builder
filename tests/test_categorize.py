from grocery.extract.categorize import lookup_category


def test_plural_ingredient_names_match_singular_keyword():
    assert lookup_category("green onions") == "produce"
    assert lookup_category("kidney beans") == "pantry"
    assert lookup_category("tomatoes") == "produce"


def test_singular_still_matches():
    assert lookup_category("garlic") == "produce"
    assert lookup_category("olive oil") == "condiments & sauces"


def test_unknown_ingredient_returns_none():
    assert lookup_category("unobtainium") is None
