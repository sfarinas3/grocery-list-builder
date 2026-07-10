# Deploying

Two paths: a **free lite deploy** (no model) on Streamlit Community Cloud, and a
**full-app deploy** (with the local model) that needs real RAM.

---

## Option A — Streamlit Community Cloud (lite, free)

Runs the **lite** version: JSON-LD recipes work fully; pages without structured
data can't be auto-extracted (no model), and categorization is lookup-only. The
app auto-detects that `llama-cpp-python` isn't installed and switches to lite —
no config needed. Streamlit Cloud runs a base `uv sync` from `pyproject.toml`,
and the local model is an **optional** dependency (`[project.optional-dependencies].local`),
so the cloud install skips it. (Local dev gets it with `uv sync --extra local`.)

1. **Push this repo to GitHub** (public is simplest for the free tier):
   ```bash
   # create an empty repo at github.com first, then:
   git remote add origin https://github.com/<your-username>/grocery-list-builder.git
   git push -u origin main      # username + a GitHub token/password when prompted
   ```
2. Go to **share.streamlit.io** → sign in with GitHub → **Create app** →
   pick the repo, **branch `main`**, **main file `app.py`** → Deploy.
3. It installs `requirements.txt`, boots in **lite mode**, and gives you a public
   `*.streamlit.app` URL. First load also downloads a small NLTK data file.

Nothing to configure — the sidebar will show a "Lite mode" notice.

---

## Option B — Hugging Face Space (full app, with the model)

> **Note:** HF now gates Docker/CPU-basic Spaces behind paid tiers for new free
> accounts (free = ZeroGPU, Gradio-only), so this is no longer a free path. Kept
> here for reference / a paid CPU Space or as a template for a self-hosted box.

This deploys the **full app** (including the local model) to Hugging Face Spaces'
free CPU tier (2 vCPU, 16 GB RAM). The model is baked into the image at build
time, so the repo push stays small and the Space survives sleep/wake without
re-downloading.

**Model on the Space:** Qwen2.5-3B (2.1 GB), chosen for speed on CPU. Your local
app is unaffected — it still defaults to 7B. (The Space overrides this via the
`GROCERY_LLM_REPO` / `GROCERY_LLM_FILE` env vars set in the `Dockerfile`.)

## Files this uses (already in the repo)
- `Dockerfile` — builds the image, bakes in the model, runs Streamlit on port 7860
- `requirements-deploy.txt` — container dependencies
- `.dockerignore` — keeps `.venv/`, `models/`, etc. out of the build

## Prerequisites
1. A free account at <https://huggingface.co>.
2. A **write** access token: HF → Settings → Access Tokens → New token (role: write).

## Steps

**1. Create the Space.** On huggingface.co: **New → Space**. Choose:
- Owner: your account · Name: e.g. `grocery-list-builder`
- SDK: **Docker** → *Blank* · Hardware: **CPU basic (free)** · Visibility: your choice

**2. Get the code into the Space repo.** From this project folder:
```bash
# Point a git remote at your new Space (use YOUR username/space name)
git init -b main            # if this folder isn't a git repo yet
git remote add space https://huggingface.co/spaces/<your-username>/grocery-list-builder
```

**3. Add the Space "card" to the top of `README.md`.** Hugging Face reads this
YAML front-matter to configure the Space. Prepend exactly this block (including
the `---` lines) to the very top of `README.md`:
```yaml
---
title: Grocery List Builder
emoji: 🛒
colorFrom: green
colorTo: blue
sdk: docker
app_port: 7860
pinned: false
---
```

**4. Commit and push.** The model is **not** in the repo (it's downloaded during
the HF build), so this push is small and goes through the corporate proxy fine
(only HF's large-file CDN is blocked, and we don't use it here):
```bash
git add Dockerfile requirements-deploy.txt .dockerignore app.py grocery README.md
git commit -m "Deploy grocery list builder to HF Space"
git push space main
# When prompted: username = your HF username, password = your HF write token
```

**5. Watch the build.** On the Space page, open the **Logs**. The build:
- installs deps and compiles `llama-cpp-python` (a few minutes),
- downloads the 2.1 GB model from ModelScope,
- then starts Streamlit.

When it finishes, the app is live at
`https://huggingface.co/spaces/<your-username>/grocery-list-builder`.

## What to expect (free tier)
- **Slow inference.** CPU-only 3B generation is tens of seconds. JSON-LD recipes
  (most sites) are fast because they don't need the model; the model is only used
  for non-structured pages and for categorizing unknown ingredients.
- **Sleeps after inactivity.** The Space pauses after ~48h idle; the next visit
  restarts it (fast — the model is baked in).
- **First build is slow** (compiling + model download). Subsequent restarts are quick.
- **Checked-off items reset** when the Space restarts (in-memory session state).

## Troubleshooting
- **Build fails compiling `llama-cpp-python`:** try a newer version in
  `requirements-deploy.txt` (e.g. `llama-cpp-python>=0.3,<0.4`) — on Linux it
  builds from source, so the AVX2 pin we need locally isn't required here.
- **Model download fails during build:** ModelScope was unreachable from the
  build. Retry the build; if it persists, switch to a Hugging Face-hosted repo
  (works from the Space — HF isn't blocked there, only from the office network).
- **Blank page / "Please wait":** give the first request time — it loads the
  model. Check the Space logs for errors.
- **`git push` rejected / hangs on large files:** make sure `models/` and `.venv/`
  weren't committed (they're in `.gitignore` / `.dockerignore`).
