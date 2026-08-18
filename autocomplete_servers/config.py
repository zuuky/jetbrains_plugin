import os

# Remote endpoint (if using external service)
NEXT_EDIT_AUTOCOMPLETE_ENDPOINT = os.environ.get(
    "NEXT_EDIT_AUTOCOMPLETE_ENDPOINT", None
)

# Local model configuration (read from environment or use defaults)
MODEL_PATH = os.environ.get(
    "MODEL_PATH",
    "/root/.cache/modelscope/hub/models/sweepai/sweep-next-edit-1.5B/sweep-next-edit-1.5b.q8_0.v2.gguf"
)
MODEL_FILENAME = os.environ.get("MODEL_FILENAME", "sweep-next-edit-0.5b.q8_0.gguf")

# Model runtime parameters
LOCAL_MODEL_N_CTX = int(os.environ.get("LOCAL_MODEL_N_CTX", 16384))
LOCAL_MODEL_N_BATCH = int(os.environ.get("LOCAL_MODEL_N_BATCH", 2048))
LOCAL_MODEL_N_UBATCH = int(os.environ.get("LOCAL_MODEL_N_UBATCH", 1024))
LOCAL_MODEL_N_GPU_LAYERS = int(os.environ.get("LOCAL_MODEL_N_GPU_LAYERS", -1))
LOCAL_MODEL_N_THREADS = int(os.environ.get("LOCAL_MODEL_N_THREADS", 8))
LOCAL_MODEL_N_THREADS_BATCH = int(os.environ.get("LOCAL_MODEL_N_THREADS_BATCH", 16))
LOCAL_MODEL_DRAFT_TOKENS = int(os.environ.get("LOCAL_MODEL_DRAFT_TOKENS", 32))

LOCAL_MODEL_FLASH_ATTN = os.environ.get("LOCAL_MODEL_FLASH_ATTN", "true").lower() in ("1", "true", "yes", "on")
LOCAL_MODEL_OFFLOAD_KQV = os.environ.get("LOCAL_MODEL_OFFLOAD_KQV", "true").lower() in ("1", "true", "yes", "on")
LOCAL_MODEL_MUL_MAT_Q = os.environ.get("LOCAL_MODEL_MUL_MAT_Q", "true").lower() in ("1", "true", "yes", "on")
LOCAL_MODEL_USE_MMAP = os.environ.get("LOCAL_MODEL_USE_MMAP", "true").lower() in ("1", "true", "yes", "on")
LOCAL_MODEL_USE_MLOCK = os.environ.get("LOCAL_MODEL_USE_MLOCK", "false").lower() in ("1", "true", "yes", "on")
LOCAL_MODEL_USE_DRAFT = os.environ.get("LOCAL_MODEL_USE_DRAFT", "true").lower() in ("1", "true", "yes", "on")
LOCAL_MODEL_LOGITS_ALL = os.environ.get("LOCAL_MODEL_LOGITS_ALL", "true").lower() in ("1", "true", "yes", "on")
LOCAL_MODEL_VERBOSE = os.environ.get("LOCAL_MODEL_VERBOSE", "false").lower() in ("1", "true", "yes", "on")

# Retrieval fallback
ENABLE_RETRIEVAL_FALLBACK = os.environ.get("ENABLE_RETRIEVAL_FALLBACK", "true").lower() in ("1", "true", "yes", "on")

# Logging
LOG_MODEL_PROMPT = os.environ.get("LOG_MODEL_PROMPT", "true").lower() in ("1", "true", "yes", "on")
LOG_MODEL_RAW_OUTPUT = os.environ.get("LOG_MODEL_RAW_OUTPUT", "true").lower() in ("1", "true", "yes", "on")
MODEL_LOG_MAX_CHARS = int(os.environ.get("MODEL_LOG_MAX_CHARS", 0))