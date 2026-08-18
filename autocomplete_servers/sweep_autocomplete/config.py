"""Server settings — single source of truth, overridable via environment variables.

Defaults are tuned for the lowest autocomplete latency (see README.md).
"""

import os

_TRUE = {"1", "true", "yes", "on"}


def _int(name: str, default: int) -> int:
    try:
        return int(os.environ.get(name, default))
    except (TypeError, ValueError):
        return default


def _bool(name: str, default: bool) -> bool:
    val = os.environ.get(name)
    return default if val is None else val.strip().lower() in _TRUE


# ---------------------------------------------------------------------------
# Endpoint
# ---------------------------------------------------------------------------
# Remote SGLang / OpenAI-compatible endpoint (e.g. "http://host:8000").
# Leave empty to run the local llama.cpp model.
NEXT_EDIT_AUTOCOMPLETE_ENDPOINT = os.environ.get("NEXT_EDIT_AUTOCOMPLETE_ENDPOINT") or None

# ---------------------------------------------------------------------------
# Local GGUF model
# ---------------------------------------------------------------------------
MODEL_PATH = os.environ.get(
    "MODEL_PATH",
    "/root/.cache/modelscope/hub/models/sweepai/sweep-next-edit-1.5B/sweep-next-edit-1.5b.q8_0.v2.gguf",
)

# ---------------------------------------------------------------------------
# llama.cpp runtime (tuned for best autocomplete speed on a GPU server)
# ---------------------------------------------------------------------------
# Context must comfortably fit the ~9.5k-token max prompt.
LOCAL_MODEL_N_CTX = _int("LOCAL_MODEL_N_CTX", 16384)
# Larger batches speed up prompt (prefill) evaluation.
LOCAL_MODEL_N_BATCH = _int("LOCAL_MODEL_N_BATCH", 4096)
LOCAL_MODEL_N_UBATCH = _int("LOCAL_MODEL_N_UBATCH", 2048)
# -1 = offload all layers to GPU; 0 = CPU only (required for CPU-only builds).
LOCAL_MODEL_N_GPU_LAYERS = _int("LOCAL_MODEL_N_GPU_LAYERS", -1)
# 0 = let llama.cpp auto-detect the best thread count.
LOCAL_MODEL_N_THREADS = _int("LOCAL_MODEL_N_THREADS", 0)
LOCAL_MODEL_N_THREADS_BATCH = _int("LOCAL_MODEL_N_THREADS_BATCH", 0)
LOCAL_MODEL_DRAFT_TOKENS = _int("LOCAL_MODEL_DRAFT_TOKENS", 32)

# Prompt-lookup speculative decoding; helps long generations on CPU,
# adds overhead on fast GPUs with short completions — off by default.
LOCAL_MODEL_USE_DRAFT = _bool("LOCAL_MODEL_USE_DRAFT", False)
# Requires a build with GGML_FLASH_ATTN (enabled in the official wheels / CUDA builds).
LOCAL_MODEL_FLASH_ATTN = _bool("LOCAL_MODEL_FLASH_ATTN", True)
LOCAL_MODEL_OFFLOAD_KQV = _bool("LOCAL_MODEL_OFFLOAD_KQV", True)
LOCAL_MODEL_MUL_MAT_Q = _bool("LOCAL_MODEL_MUL_MAT_Q", True)
LOCAL_MODEL_USE_MMAP = _bool("LOCAL_MODEL_USE_MMAP", True)
LOCAL_MODEL_USE_MLOCK = _bool("LOCAL_MODEL_USE_MLOCK", False)
# logits_all only matters when requesting per-token logprobs — keep off for speed.
LOCAL_MODEL_LOGITS_ALL = _bool("LOCAL_MODEL_LOGITS_ALL", False)
LOCAL_MODEL_VERBOSE = _bool("LOCAL_MODEL_VERBOSE", False)

# ---------------------------------------------------------------------------
# Behavior
# ---------------------------------------------------------------------------
# When no suggestion is produced, search the file for a matching block and
# retry. Adds latency only on the "no suggestion" path.
ENABLE_RETRIEVAL_FALLBACK = _bool("ENABLE_RETRIEVAL_FALLBACK", True)

# ---------------------------------------------------------------------------
# Diagnostics (keep off by default — they slow the hot path and bloat logs)
# ---------------------------------------------------------------------------
LOG_MODEL_PROMPT = _bool("LOG_MODEL_PROMPT", False)
LOG_MODEL_RAW_OUTPUT = _bool("LOG_MODEL_RAW_OUTPUT", False)
MODEL_LOG_MAX_CHARS = _int("MODEL_LOG_MAX_CHARS", 0)  # 0 = unlimited
