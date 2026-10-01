from grocery.aggregate.combine import combine
from grocery.extract.normalize import normalize
from grocery.models import Ingredient
from tests.fakes import FakeLLM


def _ings(*names):
    return [Ingredient(name=n, quantity=1, unit="cup") for n in names]


def test_known_names_skip_the_model():
    llm = FakeLLM()
    ings = normalize(_ings("garlic", "olive oil"), llm)
    assert llm.prompts == []
    assert [i.category for i in ings] == ["produce", "condiments & sauces"]


def test_model_handles_unknown_names_only():
    llm = FakeLLM({"items": [["tahini", "condiments & sauces"]]})
    ings = normalize(_ings("garlic", "tahini", "tahini"), llm)
    assert llm.prompts[0].count("tahini") == 1 and "garlic" not in llm.prompts[0]
    assert ings[1].category == "condiments & sauces"


def test_table_overrides_model_aisle_for_a_cleaned_name():
    # The model fixes the typo; the table then files "garlic" itself, even
    # though the model said pantry.
    ings = normalize(_ings("garIic"), FakeLLM({"items": [["garlic", "pantry"]]}))
    assert (ings[0].shopping_name, ings[0].category) == ("garlic", "produce")


def test_wrong_length_answer_falls_back():
    ings = normalize(_ings("tahini", "sumac"), FakeLLM({"items": [["tahini", "pantry"]]}))
    assert [i.shopping_name for i in ings] == ["tahini", "sumac"]
    assert all("needs_category" in i.flags for i in ings)


def test_unknown_aisle_from_model_is_ignored():
    ings = normalize(_ings("tahini"), FakeLLM({"items": [["tahini", "spaceship"]]}))
    assert "needs_category" in ings[0].flags


def test_no_model_flags_unplaceable_items():
    ings = normalize(_ings("unobtainium"), None)
    assert ings[0].shopping_name == "unobtainium"
    assert "needs_category" in ings[0].flags


def test_synonyms_merge_and_keep_original_wording():
    ings = normalize(_ings("spring onions", "green onion"), None)
    grocery = combine([("A", ings[:1]), ("B", ings[1:])])
    assert len(grocery.items) == 1
    item = grocery.items[0]
    assert (item.name, item.quantity, item.sources) == ("green onion", 2.0, ["A", "B"])
    assert "as: spring onions" in item.notes


def test_plurals_merge_without_a_model():
    ings = normalize(_ings("onions", "onion"), None)
    assert len(combine([("A", ings)]).items) == 1
