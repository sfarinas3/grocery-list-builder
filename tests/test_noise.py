from grocery.extract.noise import is_likely_noise


def test_flags_ui_chrome():
    assert is_likely_noise("Q Search")
    assert is_likely_noise("Wi-Fi")
    assert is_likely_noise("Sign in")


def test_flags_weather_widget_text():
    assert is_likely_noise("Partly Sunny")


def test_flags_recipe_metadata():
    assert is_likely_noise("Prep Time: 10 minutes")
    assert is_likely_noise("Serves 4")


def test_flags_cooking_temperatures():
    assert is_likely_noise("82 degrees Celsius")
    assert is_likely_noise("350°F")
    assert is_likely_noise("Internal temperature")


def test_does_not_flag_real_ingredients():
    for line in ["2 cloves garlic, minced", "1 cup sugar", "salt to taste", "green onions"]:
        assert not is_likely_noise(line)


def test_does_not_flag_quantity_plus_unit():
    # A bare unit like "6 oz" or "2 c flour" must not trip the temperature check.
    assert not is_likely_noise("2 c flour")
    assert not is_likely_noise("6 oz chicken breast")


def test_empty_string_is_not_noise():
    assert not is_likely_noise("")
    assert not is_likely_noise("   ")
