from grocery.extract.heuristic import find_candidate_lines


def test_heading_scoped_extraction():
    text = """Grandma's Chili

Ingredients:
2 lbs ground beef
1 can kidney beans
salt to taste

Instructions:
1. Brown the beef.
2. Add the beans.
"""
    assert find_candidate_lines(text) == [
        "2 lbs ground beef",
        "1 can kidney beans",
        "salt to taste",
    ]


def test_heading_scoped_extraction_strips_bullets():
    text = """Ingredients:
- 2 cups flour
* 1 tsp salt

Directions:
Mix it all together.
"""
    assert find_candidate_lines(text) == ["2 cups flour", "1 tsp salt"]


def test_heading_scoped_extraction_rejects_ui_chrome():
    text = """Ingredients:
Partly Sunny
2 cups flour
Q Search

Instructions:
Bake it.
"""
    assert find_candidate_lines(text) == ["2 cups flour"]


def test_fallback_filter_without_heading():
    text = """Chicken Soup

2 cups chicken broth
1 carrot, diced
Preheat the oven to 350 degrees.
Bring to a boil and simmer for 20 minutes.
1 tsp salt
"""
    lines = find_candidate_lines(text)
    assert "2 cups chicken broth" in lines
    assert "1 carrot, diced" in lines
    assert "1 tsp salt" in lines
    assert not any("preheat" in line.lower() for line in lines)
    assert not any("simmer" in line.lower() for line in lines)


def test_fallback_filter_rejects_temperature_lines():
    text = """Some recipe text with no heading.

2 cups flour
82 degrees Celsius
1 tsp salt
"""
    lines = find_candidate_lines(text)
    assert "82 degrees Celsius" not in lines


def test_no_candidates_in_plain_prose():
    text = "This is just a paragraph of text with no ingredients or headings at all."
    assert find_candidate_lines(text) == []
