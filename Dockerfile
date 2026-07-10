# Hugging Face Space (Docker SDK). See DEPLOY.md for the deploy steps.
#
# The GGUF model is downloaded during the image *build* and baked into the image,
# so (a) `git push` to the Space stays small (no large files in the repo) and
# (b) the free tier's frequent sleep/wake cycles don't re-download it each time.
FROM python:3.12-slim

ENV DEBIAN_FRONTEND=noninteractive \
    STREAMLIT_SERVER_HEADLESS=true \
    NLTK_DATA=/usr/local/share/nltk_data \
    # Smaller/faster model for the free CPU tier (local dev still defaults to 7B).
    GROCERY_LLM_REPO="Qwen/Qwen2.5-3B-Instruct-GGUF" \
    GROCERY_LLM_FILE="qwen2.5-3b-instruct-q4_k_m.gguf" \
    GROCERY_MODELS_DIR="/app/models"

# Build tools: llama-cpp-python compiles from source here (matched to this CPU).
RUN apt-get update && apt-get install -y --no-install-recommends \
        build-essential cmake git ca-certificates \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /app

# 1. Python deps
COPY requirements-deploy.txt .
RUN pip install --no-cache-dir -r requirements-deploy.txt

# 2. NLTK data the ingredient parser needs (so it isn't fetched at runtime)
RUN python -c "import nltk; nltk.download('averaged_perceptron_tagger_eng', download_dir='/usr/local/share/nltk_data')"

# 3. App code
COPY . .

# 4. Bake the model into the image (downloaded from ModelScope during build)
RUN python -c "from grocery import config; from grocery.extract import download; download.download(config.LLM_URL, config.LLM_PATH)"

EXPOSE 7860
CMD ["streamlit", "run", "app.py", \
     "--server.port=7860", "--server.address=0.0.0.0", \
     "--server.enableCORS=false", "--server.enableXsrfProtection=false"]
