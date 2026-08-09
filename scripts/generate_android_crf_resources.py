#!/usr/bin/env python3
"""Generate static resources for the Kotlin CRF port (android/ Phase 3).

Run once (offline, from the repo's .venv) whenever `ingredient_parser_nlp` is
upgraded. Writes plain/gzipped JSON into
android/core/src/main/resources/ingredient_parser/, which the Kotlin port
loads at runtime via getResourceAsStream — no Python/NLTK/pint dependency
survives into the Android app.

Usage: .venv/Scripts/python.exe scripts/generate_android_crf_resources.py
"""

import gzip
import json
import shutil
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parent.parent
OUT_DIR = REPO_ROOT / "android" / "core" / "src" / "main" / "resources" / "ingredient_parser"
NLTK_TAGGER_DIR = Path.home() / "AppData" / "Roaming" / "nltk_data" / "taggers" / "averaged_perceptron_tagger_eng"


def write_gzipped_json(obj, path: Path) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with gzip.open(path, "wt", encoding="utf-8") as f:
        json.dump(obj, f, separators=(",", ":"))
    print(f"wrote {path} ({path.stat().st_size:,} bytes)")


def copy_gzipped(src: Path, dst: Path) -> None:
    """Read plain JSON from src, write gzipped to dst (re-gzip, don't just copy —
    src files here are plain JSON, not already gzipped)."""
    with open(src, encoding="utf-8") as f:
        obj = json.load(f)
    write_gzipped_json(obj, dst)


def export_crf_model() -> None:
    import ingredient_parser

    model_path = Path(ingredient_parser.__file__).parent / "en" / "data" / "model.en.json.gz"
    with gzip.open(model_path, "rt", encoding="utf-8") as f:
        model = json.load(f)
    write_gzipped_json(model, OUT_DIR / "model.en.json.gz")


def export_pos_tagger() -> None:
    copy_gzipped(NLTK_TAGGER_DIR / "averaged_perceptron_tagger_eng.weights.json", OUT_DIR / "pos_tagger.weights.json.gz")
    copy_gzipped(NLTK_TAGGER_DIR / "averaged_perceptron_tagger_eng.tagdict.json", OUT_DIR / "pos_tagger.tagdict.json.gz")
    copy_gzipped(NLTK_TAGGER_DIR / "averaged_perceptron_tagger_eng.classes.json", OUT_DIR / "pos_tagger.classes.json.gz")


def export_ingredient_tagdict() -> None:
    import ingredient_parser

    tagdict_path = Path(ingredient_parser.__file__).parent / "en" / "data" / "ingredient_tagdict.json.gz"
    if not tagdict_path.exists():
        # Fall back to searching, in case the packaged layout differs from assumption.
        candidates = list((Path(ingredient_parser.__file__).parent).rglob("ingredient_tagdict.json.gz"))
        if not candidates:
            raise FileNotFoundError("ingredient_tagdict.json.gz not found under ingredient_parser package")
        tagdict_path = candidates[0]
    with gzip.open(tagdict_path, "rt", encoding="utf-8") as f:
        tagdict = json.load(f)
    write_gzipped_json(tagdict, OUT_DIR / "ingredient_tagdict.json.gz")


def export_unit_canonicalization() -> None:
    """Run the real convert_to_pint_unit() across every unit string this app cares
    about, recording {input: canonical_str_or_null}. This is the ONLY thing that
    needs pint at all -- we capture its string-formatting behavior once, so the
    Kotlin port never needs pint itself (parse.py never calls .convert_to()).

    Critically, `null` here means "pint did NOT recognize this unit" (it stayed a
    plain string) -- NOT "recognized but happened to equal the input". Recognized
    units are never pluralized downstream (ingredient_amount_factory only
    pluralizes `_unit` when `isinstance(_unit, str)` is True, i.e. pint rejected
    it); unrecognized ones are. Conflating these two cases was a real bug caught
    by the Kotlin differential test ("2 cups" -> unit should stay singular "cup",
    not become "cups", because pint recognizes "cup").
    """
    import pint
    from ingredient_parser.en._utils import convert_to_pint_unit
    from ingredient_parser.en._constants import UNITS, FLATTENED_UNITS_LIST

    candidates: set[str] = set()
    candidates.update(UNITS.keys())
    candidates.update(UNITS.values())
    candidates.update(FLATTENED_UNITS_LIST)

    table: dict[str, str | None] = {}
    for unit in sorted(candidates):
        if not unit:
            continue
        result = convert_to_pint_unit(unit)
        # A recognized unit's canonical string; None if pint didn't recognize it at all.
        table[unit] = str(result) if isinstance(result, pint.Unit) else None

    write_gzipped_json(table, OUT_DIR / "unit_canonicalization.json.gz")
    recognized = sum(1 for v in table.values() if v is not None)
    print(f"unit canonicalization: {recognized}/{len(table)} units recognized by pint")


def main() -> None:
    OUT_DIR.mkdir(parents=True, exist_ok=True)
    export_crf_model()
    export_pos_tagger()
    export_ingredient_tagdict()
    export_unit_canonicalization()
    print("done")


if __name__ == "__main__":
    main()
