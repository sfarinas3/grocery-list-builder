from grocery.extract.lines import PROSE_LINE_CHARS, ingredient_lines
from grocery.models import RecipeContent
from tests.fakes import FakeLLM

TEXT = """Grandma's Chili
Ingredients
- 2 lbs ground beef
- 1 onion, diced
salt to taste
Instructions
Brown the beef."""
# Numbered as the model sees it (blank lines dropped, bullets stripped):
# 1 Grandma's Chili, 2 Ingredients, 3 2 lbs ground beef, 4 1 onion, diced,
# 5 salt to taste, 6 Instructions, 7 Brown the beef.


def test_json_ld_lines_skip_the_model():
    llm = FakeLLM()
    content = RecipeContent(ingredient_lines=["1 cup rice"])
    assert ingredient_lines(content, llm) == ["1 cup rice"]
    assert llm.prompts == []


def test_model_picks_lines_by_number():
    llm = FakeLLM({"ingredient_lines": [3, 4, 5]})
    lines = ingredient_lines(RecipeContent(raw_text=TEXT), llm)
    assert lines == ["2 lbs ground beef", "1 onion, diced", "salt to taste"]
    assert len(llm.prompts) == 1  # nothing dropped -> no recall check


def test_recall_check_recovers_a_dropped_line():
    # Model forgot "salt to taste" (line 5); the heuristic found it, so it's
    # asked about again and confirmed (it's #1 of the re-asked lines).
    llm = FakeLLM({"ingredient_lines": [3, 4]}, {"ingredient_lines": [1]})
    lines = ingredient_lines(RecipeContent(raw_text=TEXT), llm)
    assert lines == ["2 lbs ground beef", "1 onion, diced", "salt to taste"]
    assert llm.prompts[1] == "1: salt to taste"


def test_out_of_range_numbers_are_ignored():
    llm = FakeLLM({"ingredient_lines": [3, 4, 5, 99, 0]})
    assert len(ingredient_lines(RecipeContent(raw_text=TEXT), llm)) == 3


def test_no_model_falls_back_to_heuristic():
    lines = ingredient_lines(RecipeContent(raw_text=TEXT), None)
    assert lines == ["2 lbs ground beef", "1 onion, diced", "salt to taste"]


def test_empty_model_answer_falls_back_to_heuristic():
    lines = ingredient_lines(RecipeContent(raw_text=TEXT), FakeLLM({}))
    assert lines == ["2 lbs ground beef", "1 onion, diced", "salt to taste"]


def test_prose_line_is_rewritten():
    prose = "To make it you'll need a can of coconut milk and two tablespoons of curry paste. " * 2
    assert len(prose) > PROSE_LINE_CHARS
    llm = FakeLLM(
        {"ingredient_lines": [1]},
        {"ingredient_lines": ["1 can coconut milk", "2 tbsp curry paste"]},
    )
    lines = ingredient_lines(RecipeContent(raw_text=prose), llm)
    assert lines == ["1 can coconut milk", "2 tbsp curry paste"]
