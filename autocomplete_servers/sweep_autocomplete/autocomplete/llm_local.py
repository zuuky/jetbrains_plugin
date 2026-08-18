"""Local llama.cpp inference with latest-request-wins coalescing."""

import threading
import time
from llama_cpp import Llama
from llama_cpp.llama_speculative import LlamaPromptLookupDecoding
from loguru import logger

from sweep_autocomplete import config

_model: Llama | None = None
_load_lock = threading.Lock()  # guards model construction
_request_lock = threading.Lock()  # guards request-id allocation
_latest_request_id = 0


class RequestCancelled(Exception):
    """Raised when a queued request is superseded by a newer one."""


def _build_model() -> Llama:
    kwargs = dict(
        model_path=config.MODEL_PATH,
        n_ctx=config.LOCAL_MODEL_N_CTX,
        n_batch=config.LOCAL_MODEL_N_BATCH,
        n_ubatch=config.LOCAL_MODEL_N_UBATCH,
        n_gpu_layers=config.LOCAL_MODEL_N_GPU_LAYERS,
        n_threads=config.LOCAL_MODEL_N_THREADS,
        n_threads_batch=config.LOCAL_MODEL_N_THREADS_BATCH,
        flash_attn=config.LOCAL_MODEL_FLASH_ATTN,
        logits_all=config.LOCAL_MODEL_LOGITS_ALL,
        use_mmap=config.LOCAL_MODEL_USE_MMAP,
        use_mlock=config.LOCAL_MODEL_USE_MLOCK,
        offload_kqv=config.LOCAL_MODEL_OFFLOAD_KQV,
        mul_mat_q=config.LOCAL_MODEL_MUL_MAT_Q,
        verbose=config.LOCAL_MODEL_VERBOSE,
    )
    if config.LOCAL_MODEL_USE_DRAFT and config.LOCAL_MODEL_DRAFT_TOKENS > 0:
        kwargs["draft_model"] = LlamaPromptLookupDecoding(
            num_pred_tokens=config.LOCAL_MODEL_DRAFT_TOKENS
        )
    return Llama(**kwargs)


def _load_with_gpu_fallback() -> Llama:
    """Load the model; retry on CPU when this build lacks GPU offload support."""
    try:
        logger.info(
            f"Loading model from {config.MODEL_PATH} "
            f"(n_ctx={config.LOCAL_MODEL_N_CTX}, "
            f"n_batch={config.LOCAL_MODEL_N_BATCH}, "
            f"n_gpu_layers={config.LOCAL_MODEL_N_GPU_LAYERS})"
        )
        return _build_model()
    except ValueError as e:
        if config.LOCAL_MODEL_N_GPU_LAYERS == 0 or "gpu" not in str(e).lower():
            raise
        logger.warning(
            f"llama-cpp build has no GPU offload ({e}); retrying on CPU "
            f"(set LOCAL_MODEL_N_GPU_LAYERS=0 to silence this)"
        )
        config.LOCAL_MODEL_N_GPU_LAYERS = 0
        return _build_model()


def get_model() -> Llama:
    """Return the process-wide model singleton, loading it on first call."""
    global _model
    if _model is None:
        with _load_lock:
            if _model is None:
                _model = _load_with_gpu_fallback()
                logger.info("Model loaded successfully")
    return _model


def preload_model() -> None:
    """Start loading the model in the background so the first request is warm."""
    threading.Thread(target=get_model, name="model-preload", daemon=True).start()


def generate_completion(
        prompt: str,
        stop: list[str],
        max_tokens: int,
        temperature: float,
        prefix: str = "",
) -> tuple[str, int, list, str | None]:
    """Generate a completion using the local llama-cpp model.

    Only the latest request actually runs inference: if a newer request arrives
    while this one waits for the model lock, this request is cancelled
    (raises RequestCancelled).

    Returns (completion_text, elapsed_ms, logprobs, finish_reason).
    """
    global _latest_request_id

    model = get_model()
    full_prompt = prompt + prefix if prefix else prompt

    # Claim a monotonically increasing request ID.
    with _request_lock:
        _latest_request_id += 1
        my_id = _latest_request_id

    # Wait for the model; when we get the lock, check whether we are still latest.
    with _model_lock:
        if my_id != _latest_request_id:
            logger.info(f"Request {my_id} cancelled (latest is {_latest_request_id})")
            raise RequestCancelled()

        tokens = model.tokenize(full_prompt.encode("utf-8"))
        logger.debug(
            f"Prompt length: {len(full_prompt)} chars, {len(tokens)} tokens, "
            f"n_ctx={model.n_ctx()}"
        )

        start = time.time()
        result = model.create_completion(
            prompt=full_prompt,
            max_tokens=max_tokens,
            temperature=temperature,
            stop=stop,
        )
        elapsed_ms = int((time.time() - start) * 1000)

    text = result["choices"][0]["text"]
    if prefix:
        text = prefix + text

    finish_reason = result["choices"][0].get("finish_reason")
    return text, elapsed_ms, [], finish_reason
