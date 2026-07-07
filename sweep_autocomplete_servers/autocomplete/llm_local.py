import os
import threading
import time
from typing import Any

from llama_cpp import Llama
from llama_cpp.llama_speculative import LlamaPromptLookupDecoding

from loguru import logger

_model: Llama | None = None
_model_lock = threading.Lock()
_request_lock = threading.Lock()
_latest_request_id = 0


def _read_env_int(key: str, default: int) -> int:
    """Read an integer from environment variable."""
    val = os.environ.get(key)
    if val is None:
        return default
    try:
        return int(val)
    except ValueError:
        return default


def _read_env_bool(key: str, default: bool = False) -> bool:
    """Read a boolean from environment variable."""
    val = os.environ.get(key)
    if val is None:
        return default
    return val.lower() in ("1", "true", "yes", "on")


class RequestCancelled(Exception):
    """Raised when a queued request is superseded by a newer one."""
    pass


def get_model() -> Llama:
    global _model
    if _model is None:
        model_path = os.environ.get("MODEL_PATH", "")
        if not model_path:
            raise ValueError("MODEL_PATH environment variable is not set")

        logger.info(f"Loading model from {model_path}")

        n_ctx = _read_env_int("LOCAL_MODEL_N_CTX", 16384)
        n_batch = _read_env_int("LOCAL_MODEL_N_BATCH", 2048)
        n_gpu_layers = _read_env_int("LOCAL_MODEL_N_GPU_LAYERS", -1)
        n_threads = _read_env_int("LOCAL_MODEL_N_THREADS", 8)
        n_threads_batch = _read_env_int("LOCAL_MODEL_N_THREADS_BATCH", 16)
        n_ubatch = _read_env_int("LOCAL_MODEL_N_UBATCH", 1024)
        draft_tokens = _read_env_int("LOCAL_MODEL_DRAFT_TOKENS", 32)

        flash_attn = _read_env_bool("LOCAL_MODEL_FLASH_ATTN", True)
        use_mmap = _read_env_bool("LOCAL_MODEL_USE_MMAP", True)
        use_mlock = _read_env_bool("LOCAL_MODEL_USE_MLOCK", False)
        logits_all = _read_env_bool("LOCAL_MODEL_LOGITS_ALL", True)
        use_draft = _read_env_bool("LOCAL_MODEL_USE_DRAFT", True)
        offload_kqv = _read_env_bool("LOCAL_MODEL_OFFLOAD_KQV", True)
        mul_mat_q = _read_env_bool("LOCAL_MODEL_MUL_MAT_Q", True)

        logger.info(f"Model config: n_ctx={n_ctx}, n_batch={n_batch}, n_gpu_layers={n_gpu_layers}")
        logger.info(f"flash_attn={flash_attn}, use_draft={use_draft}, logits_all={logits_all}")

        model_kwargs = dict(
            model_path=model_path,
            n_ctx=n_ctx,
            n_batch=n_batch,
            n_gpu_layers=n_gpu_layers,
            flash_attn=flash_attn,
            logits_all=logits_all,
            use_mmap=use_mmap,
            use_mlock=use_mlock,
            n_threads=n_threads,
            n_threads_batch=n_threads_batch,
            n_ubatch=n_ubatch,
        )

        if use_draft and draft_tokens > 0:
            model_kwargs["draft_model"] = LlamaPromptLookupDecoding(num_pred_tokens=draft_tokens)

        _model = Llama(**model_kwargs)
        logger.info("Model loaded successfully")
    return _model


def generate_completion(
    prompt: str,
    stop: list[str],
    max_tokens: int,
    temperature: float,
    prefix: str = "",
) -> tuple[str, int, list[Any], str | None]:
    """Generate a completion using the local llama-cpp model.

    Only the latest request will actually run inference. If a newer request
    arrives while this one is waiting for the model lock, this request is
    cancelled (raises RequestCancelled).

    Returns (completion_text, elapsed_ms, logprobs, finish_reason)
    matching the signature of fetch_next_edits_http.
    """
    global _latest_request_id

    model = get_model()
    full_prompt = prompt + prefix if prefix else prompt

    # Claim a request ID — always monotonically increasing
    with _request_lock:
        _latest_request_id += 1
        my_id = _latest_request_id

    # Wait for the model. When we get the lock, check if we're still latest.
    with _model_lock:
        if my_id != _latest_request_id:
            logger.info(f"Request {my_id} cancelled (latest is {_latest_request_id})")
            raise RequestCancelled()

        tokens = model.tokenize(full_prompt.encode("utf-8"))
        logger.info(f"Prompt length: {len(full_prompt)} chars, {len(tokens)} tokens, n_ctx={model.n_ctx()}")

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
