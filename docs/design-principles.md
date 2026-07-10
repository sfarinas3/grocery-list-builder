# Design Principles

These principles govern **all** code written for this project. Reference this file
before writing or changing code.

## 1. Concise
- Prefer the shortest clear solution. Fewer lines, fewer moving parts.
- Use Python's expressive features (comprehensions, standard library, dataclasses)
  instead of hand-rolled boilerplate.
- Don't add abstraction, config, or generality until a real need exists (YAGNI).

## 2. Python as much as possible
- Python is the default for everything: pipeline, logic, and UI (Streamlit).
- Reach for a well-known library over custom code when it does the job cleanly.
- Avoid other languages/tooling unless there's no reasonable Python option.

## 3. Well-commented for a data scientist
- The reader is a data scientist who will edit this code. Comments explain the
  **why** and the intent, not the obvious mechanics.
- Every module starts with a short docstring: what it does and where it sits in
  the pipeline (`URL → ingest → extract → aggregate → display`).
- Every function has a one-line docstring; non-trivial logic gets an inline note.
- Mark the spots most likely to be tweaked (prompts, categories, thresholds) with
  a clear comment so they're easy to find and change.

## 4. Easy to change
- Keep modules small and single-purpose, matching the architecture in the design doc.
- Put the knobs a data scientist will actually turn — prompts, model name,
  category lists, sanity thresholds — in obvious, well-labeled places.
- Favor readability over cleverness. If a trick needs explaining, prefer the plain
  version.
