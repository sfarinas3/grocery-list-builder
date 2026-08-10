"""Download the local GGUF model from ModelScope.

Only used by the dormant `local_llm.py`/`vision.py` backends (docs/design.md
§3.3) — not exercised by the live app.

Split out from the model code because it's a one-time, slow, resumable network
op. Hugging Face's and Ollama's CDNs are blocked by the corporate proxy; the
ModelScope CDN is reachable (see docs/design.md §8), and it serves the Qwen
GGUFs directly over HTTPS — so a plain streaming httpx download is all we need.
"""

import ssl
from collections.abc import Callable
from pathlib import Path

import httpx
import truststore

# Trust the OS cert store so the corporate TLS proxy doesn't break the download.
_SSL_CONTEXT = truststore.SSLContext(ssl.PROTOCOL_TLS_CLIENT)
_HEADERS = {"User-Agent": "Mozilla/5.0"}
_CHUNK = 1 << 20  # 1 MiB


def download(url: str, dest: Path, *, log: Callable[[str], None] = print) -> Path:
    """Stream `url` to `dest`, resuming a prior partial download if present.

    Writes to a `.part` file and renames on success, so `dest` only ever exists
    when the download is complete — the model loader can treat "file exists" as
    "ready".
    """
    dest.parent.mkdir(parents=True, exist_ok=True)
    part = dest.with_name(dest.name + ".part")
    have = part.stat().st_size if part.exists() else 0

    headers = dict(_HEADERS)
    if have:
        headers["Range"] = f"bytes={have}-"  # ask the server to resume

    timeout = httpx.Timeout(60.0, connect=30.0)
    with httpx.stream("GET", url, headers=headers, follow_redirects=True,
                      verify=_SSL_CONTEXT, timeout=timeout) as resp:
        resp.raise_for_status()

        # If we asked to resume but the server ignored it (200, not 206), start over.
        if have and resp.status_code == 200:
            have = 0
        mode = "ab" if have else "wb"

        total = have + int(resp.headers.get("content-length", 0))
        done = have
        next_log = have  # log roughly every 100 MiB
        log(f"Downloading {dest.name} ({total / 1e9:.2f} GB){' (resuming)' if have else ''}...")
        with open(part, mode) as fh:
            for chunk in resp.iter_bytes(_CHUNK):
                fh.write(chunk)
                done += len(chunk)
                if done >= next_log:
                    pct = f"{done / total * 100:.0f}%" if total else "?"
                    log(f"  {done / 1e9:.2f} / {total / 1e9:.2f} GB ({pct})")
                    next_log += 100 * (1 << 20)

    part.replace(dest)
    log(f"Done: {dest}")
    return dest
