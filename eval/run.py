"""Measure ingredient-extraction accuracy against a hand-labeled set (design §7).

Runs the real pipeline (extract lines -> CRF parse -> categorize) over fixtures
in `cases.json` and reports precision / recall / F1 on ingredient names, plus a
category-accuracy figure. Fixtures store the *ingest output* (JSON-LD lines or
raw page text), so the eval is deterministic and network-free — it isolates the
accuracy-critical parts and lets us compare models fairly.

Run:
    uv run python -m eval.run                 # uses the configured model (7B)
    # compare a different model:
    GROCERY_LLM_REPO=Qwen/Qwen2.5-3B-Instruct-GGUF \
    GROCERY_LLM_FILE=qwen2.5-3b-instruct-q4_k_m.gguf uv run python -m eval.run

Purposes: pick between models, guard against regressions, and give an honest
accuracy number. It's small — read the per-case diffs, don't just trust the F1.
"""

import json
import re
from pathlib import Path

from grocery import config
from grocery.extract.categorize import categorize
from grocery.extract.local_llm import LocalLLMExtractor
from grocery.extract.parse import parse_lines
from grocery.models import RecipeContent

_CASES = Path(__file__).with_name("cases.json")


def _normalize(name: str) -> set[str]:
    """Lowercase, drop punctuation, fold trailing plurals, return the word set.

    Comparing word-sets (rather than exact strings) lets "flour" match
    "all-purpose flour" and "sesame oil" match "toasted sesame oil"; folding a
    trailing 's' per word lets "pita breads" match "pita bread" — the kind of
    near-misses that are correct for our purposes.
    """
    cleaned = re.sub(r"[^a-z0-9 ]", " ", name.lower())
    return {re.sub(r"s$", "", word) for word in cleaned.split()}


def _matches(predicted: str, expected: str) -> bool:
    """True if two names refer to the same ingredient (word-set subset either way)."""
    p, e = _normalize(predicted), _normalize(expected)
    if not p or not e:
        return False
    return p == e or p <= e or e <= p


def _score_case(predicted: list, expected: list) -> dict:
    """Greedily match predicted items to expected; return counts + diffs.

    `predicted` / `expected` are lists of (name, category). Each prediction and
    each expectation is used at most once (one-to-one), so merging two expected
    ingredients into one prediction correctly costs a recall miss.
    """
    remaining = list(predicted)
    matched, missed = [], []
    correct_category = 0
    for exp_name, exp_cat in expected:
        hit = next((p for p in remaining if _matches(p[0], exp_name)), None)
        if hit is None:
            missed.append(exp_name)
            continue
        remaining.remove(hit)
        matched.append((exp_name, hit[0]))
        if hit[1] == exp_cat:
            correct_category += 1
    extra = [p[0] for p in remaining]
    return {
        "tp": len(matched),
        "fn": len(missed),
        "fp": len(extra),
        "missed": missed,
        "extra": extra,
        "category_correct": correct_category,
    }


def _predict(case: dict, extractor: LocalLLMExtractor) -> list:
    """Run the pipeline on one fixture and return predicted (name, category) pairs."""
    if case["source"] == "json-ld":
        content = RecipeContent(source_url=case["id"], ingredient_lines=case["lines"])
    else:
        content = RecipeContent(source_url=case["id"], raw_text=case["text"])
    lines = extractor.ingredient_lines(content)
    ingredients = parse_lines(lines, source_url=case["id"])
    categorize(ingredients, extractor=extractor)
    return [(ing.name, ing.category) for ing in ingredients]


def _prf(tp: int, fp: int, fn: int) -> tuple[float, float, float]:
    precision = tp / (tp + fp) if tp + fp else 0.0
    recall = tp / (tp + fn) if tp + fn else 0.0
    f1 = 2 * precision * recall / (precision + recall) if precision + recall else 0.0
    return precision, recall, f1


def main() -> None:
    cases = json.loads(_CASES.read_text(encoding="utf-8"))
    extractor = LocalLLMExtractor()

    totals = {"tp": 0, "fp": 0, "fn": 0, "category_correct": 0}
    print(f"Model: {config.LLM_FILE}\n")
    print(f"{'case':22} {'P':>5} {'R':>5} {'F1':>5}  {'cat':>5}   diffs")
    print("-" * 78)

    for case in cases:
        predicted = _predict(case, extractor)
        expected = [(item["name"], item["category"]) for item in case["expected"]]
        s = _score_case(predicted, expected)
        for key in totals:
            totals[key] += s[key]
        p, r, f1 = _prf(s["tp"], s["fp"], s["fn"])
        cat = s["category_correct"] / s["tp"] if s["tp"] else 0.0
        diffs = []
        if s["missed"]:
            diffs.append("missed=" + ", ".join(s["missed"]))
        if s["extra"]:
            diffs.append("extra=" + ", ".join(s["extra"]))
        print(f"{case['id']:22} {p:5.2f} {r:5.2f} {f1:5.2f}  {cat:5.2f}   {' | '.join(diffs)}")

    print("-" * 78)
    p, r, f1 = _prf(totals["tp"], totals["fp"], totals["fn"])
    cat = totals["category_correct"] / totals["tp"] if totals["tp"] else 0.0
    print(f"{'OVERALL':22} {p:5.2f} {r:5.2f} {f1:5.2f}  {cat:5.2f}")
    print(
        f"\nnames: {totals['tp']} matched, {totals['fn']} missed, {totals['fp']} extra   "
        f"| category accuracy on matched: {cat:.0%}"
    )


if __name__ == "__main__":
    main()
