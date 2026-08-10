# Grocery List App — Recipe Extraction Architecture

**Status:** Design decision made, implementation not yet started
**Last updated:** August 2026

## Problem

Original plan was a single small LLM doing OCR + parsing + structuring on-device, all in one shot. This was hitting real friction in testing on a phone. Diagnosis: wrong architecture, not a fundamentally unworkable idea. Decomposing the pipeline into narrower steps is the fix.

## Conclusion — Decomposed Pipeline

1. **Recipe links** — parse embedded JSON-LD (`schema.org/Recipe`) directly from the page. No model needed for most sites.
2. **Recipe photos** — ML Kit Text Recognition (OCR) extracts raw text. Runs on nearly all Android devices, free, no flagship/AICore gating.
3. **Structuring** — feed extracted text (from OCR or link scraping) to a small on-device LLM → structured JSON (ingredients, quantities, steps).

## Why Not Gemini Nano

Device support is too narrow/inconsistent right now:
- Newer Nano v3 tier ("Gemini Intelligence") is limited to a handful of 2026 flagships with 12GB+ RAM (Pixel 10, Galaxy S26, OnePlus 15, select others).
- Older ML Kit GenAI / Nano tier is broader but still flagship-only (Pixel 8+, Galaxy S24 series, Snapdragon 8 Gen 3 devices).

Decided to bundle our own model instead of depending on either tier.

## Runtime: LiteRT-LM

Google's on-device LLM runtime. Not gated by the AICore device list. Well-suited to bursty, one-shot inference — avoids the thermal throttling issues that hurt sustained on-device chat use cases (this was a real failure mode identified during research: sustained inference on some Android chipsets triggers aggressive thermal governors within minutes).

## Model: Qwen3 1.7B

- **Text-only** — confirmed. Separate from the multimodal Qwen3-VL line, which we don't need since OCR already handles the image step. Using the VL variant would add unnecessary size/RAM for a capability we don't use.
- **License:** Apache 2.0, no commercial restrictions.
- **Size:** ~1–1.2GB at Q4_K_M quantization — hits our size target.
- **Fit:** good for bounded extraction tasks (text-in, JSON-out). Not asking it for open-ended reasoning.

### Alternatives considered
- **Gemma 4 E2B** — strong option, Apache 2.0, purpose-built for edge, best LiteRT-LM tooling support (same publisher). Rejected for now because it lands around 3–3.5GB quantized, too big for our size target.
- **Llama 3.2 1B** — could get below 1GB, but carries licensing caveats (700M MAU cap, EU restrictions) — not worth the risk when Qwen3 gives similar capability under a cleaner license.
- **Gemma 3 1B** — lighter than Gemma 4, but noticeable capability tradeoff; would need direct testing against our prompts before trusting it.
- **Gemma 3 270M** — very small (~529MB) but likely too limited for varied, messy real-world recipe formatting; worth a quick eval only if size becomes a hard blocker.

## Cost

On-device inference is free (no per-call charges). A cloud fallback (for low-RAM/storage-constrained devices) would use normal metered API pricing (Claude/Gemini/GPT etc., billed per token).

## Open Items for Next Session

- [ ] Deliver model via first-launch download or Play Asset Delivery — **do not** bundle in APK (too large, Play size limits, bad first-install experience).
- [ ] Design fallback path for devices that can't or won't download ~1GB (storage-constrained, user declines, etc.).
- [ ] Validate extraction quality against a batch of real messy recipe photos (varied formatting, units, quantity ranges like "2–3 cloves") before finalizing model choice.
- [ ] Decide/design the JSON schema + grammar-constrained decoding setup for reliable structured output (avoid hallucinated ingredients).
- [ ] Prompt design for the structuring step.
- [ ] Error handling when OCR text comes back garbled or incomplete.
