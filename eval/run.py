"""Measure extraction accuracy against a hand-labeled set (docs/design.md §7).

Runs the real pipeline (lines -> CRF parse -> normalize) over fixtures in
`cases.json` and reports precision / recall / F1 on shopping names, plus a
category-accuracy figure and the time per case. Fixtures store the *ingest
output* (JSON-LD lines or raw text), so the eval is deterministic and
network-free — it isolates the accuracy-critical parts and lets us compare
models fairly.

Run:
    uv run python -m eval.run                 # the configured model (config.py)
    uv run python -m eval.run --no-llm        # model-free fallback, as a baseline
    # compare a different model (downloads it on first run):
    GROCERY_LLM_REPO=Qwen/Qwen2.5-7B-Instruct-GGUF \
    GROCERY_LLM_FILE=qwen2.5-7b-instruct-q4_k_m.gguf uv run python -m eval.run

Purposes: pick between models, guard against regressions, and give an honest
accuracy number. It's small — read the per-case diffs, don't just trust the F1.
"""

import json
import re
import sys
import time
from pathlib import Path

from grocery import config
from grocery.extract.lines import ingredient_lines
from grocery.extract.llm import LocalLLM
from grocery.extract.normalize import normalize
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
    matched, missed, wrong_category = [], [], []
    for exp_name, exp_cat in expected:
        hit = next((p for p in remaining if _matches(p[0], exp_name)), None)
        if hit is None:
            missed.append(exp_name)
            continue
        remaining.remove(hit)
        matched.append((exp_name, hit[0]))
        if hit[1] != exp_cat:
            wrong_category.append(f"{hit[0]}={hit[1]}")
    return {
        "tp": len(matched),
        "fn": len(missed),
        "fp": len(remaining),
        "missed": missed,
        "extra": [p[0] for p in remaining],
        "wrong_category": wrong_category,
        "category_correct": len(matched) - len(wrong_category),
    }


def _predict(case: dict, llm: LocalLLM | None) -> list:
    """Run the pipeline on one fixture and return predicted (shopping name, category) pairs."""
    if case["source"] == "json-ld":
        content = RecipeContent(source_url=case["id"], ingredient_lines=case["lines"])
    else:
        content = RecipeContent(source_url=case["id"], raw_text=case["text"])
    ingredients = parse_lines(ingredient_lines(content, llm), source_url=case["id"])
    normalize(ingredients, llm)
    return [(ing.shopping_name, ing.category) for ing in ingredients]


def _prf(tp: int, fp: int, fn: int) -> tuple[float, float, float]:
    precision = tp / (tp + fp) if tp + fp else 0.0
    recall = tp / (tp + fn) if tp + fn else 0.0
    f1 = 2 * precision * recall / (precision + recall) if precision + recall else 0.0
    return precision, recall, f1


def main() -> None:
    cases = json.loads(_CASES.read_text(encoding="utf-8"))
    llm = None if "--no-llm" in sys.argv else LocalLLM()
    if llm:
        llm.load()  # load up front so the first case's time is inference only

    totals = {"tp": 0, "fp": 0, "fn": 0, "category_correct": 0}
    print(f"Model: {config.LLM_FILE if llm else '(none - model-free fallback)'}\n")
    print(f"{'case':22} {'P':>5} {'R':>5} {'F1':>5}  {'cat':>5}  {'secs':>5}   diffs")
    print("-" * 78)

    started = time.perf_counter()
    for case in cases:
        t0 = time.perf_counter()
        predicted = _predict(case, llm)
        secs = time.perf_counter() - t0
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
        if s["wrong_category"]:
            diffs.append("aisle=" + ", ".join(s["wrong_category"]))
        print(f"{case['id']:22} {p:5.2f} {r:5.2f} {f1:5.2f}  {cat:5.2f}  {secs:5.1f}   {' | '.join(diffs)}")

    print("-" * 78)
    p, r, f1 = _prf(totals["tp"], totals["fp"], totals["fn"])
    cat = totals["category_correct"] / totals["tp"] if totals["tp"] else 0.0
    print(f"{'OVERALL':22} {p:5.2f} {r:5.2f} {f1:5.2f}  {cat:5.2f}  {time.perf_counter() - started:5.0f}")
    print(
        f"\nnames: {totals['tp']} matched, {totals['fn']} missed, {totals['fp']} extra   "
        f"| category accuracy on matched: {cat:.0%}"
    )


if __name__ == "__main__":
    main()
