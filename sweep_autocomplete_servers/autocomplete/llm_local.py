import threading
import time
from typing import Any

from huggingface_hub import hf_hub_download
from llama_cpp import Llama
from llama_cpp.llama_speculative import LlamaPromptLookupDecoding

from sweep_autocomplete.config import MODEL_REPO, MODEL_FILENAME
from loguru import logger

_model: Llama | None = None
_model_lock = threading.Lock()
_request_lock = threading.Lock()
_latest_request_id = 0


class RequestCancelled(Exception):
    """Raised when a queued request is superseded by a newer one."""

    pass


def get_model() -> Llama:
    global _model
    if _model is None:
        logger.info(f"Downloading model {MODEL_FILENAME} from {MODEL_REPO}")
        model_path = hf_hub_download(repo_id=MODEL_REPO, filename=MODEL_FILENAME)
        logger.info(f"Loading model from {model_path}")
        _model = Llama(
            model_path=model_path,
            n_ctx=16384,
            n_batch=4096,
            n_gpu_layers=-1,
            flash_attn=True,
            draft_model=LlamaPromptLookupDecoding(num_pred_tokens=32),
            logits_all=True,
        )
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
        logger.info(
            f"Prompt length: {len(full_prompt)} chars, {len(tokens)} tokens, n_ctx={model.n_ctx()}"
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
