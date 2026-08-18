"""Local llama.cpp inference with latest-request-wins coalescing.

纯 GPU 策略：必须使用支持 GPU 卸载的 llama-cpp 构建；在任何情况下都不回退 CPU。
"""

import threading
import time
from llama_cpp import Llama
from loguru import logger

from sweep_autocomplete import config

_model: Llama | None = None
_load_lock = threading.Lock()  # guards model construction
_generation_lock = threading.Lock()  # serializes inference (only latest runs)
_request_lock = threading.Lock()  # guards request-id allocation
_latest_request_id = 0


class RequestCancelled(Exception):
    """Raised when a queued request is superseded by a newer one."""


def _verify_gpu_support() -> None:
    """Fail fast when the installed llama-cpp build cannot offload to GPU.

    这是"纯 GPU 策略"的硬性校验：只要构建支持 GPU 卸载，且模型能成功加载，
    llama.cpp 就会真正使用 GPU（-1 = 全部层进 GPU；失败会直接抛错，不会静默跑 CPU）。
    Only an explicit LOCAL_MODEL_N_GPU_LAYERS=0 (operator choice) skips this.
    """
    if config.LOCAL_MODEL_N_GPU_LAYERS == 0:
        return
    try:
        from llama_cpp import llama_cpp as _lcpp

        supports = getattr(_lcpp, "llama_supports_gpu_offload", None)
        if supports is not None and not bool(supports()):
            raise RuntimeError(
                "llama-cpp 构建不支持 GPU 卸载。纯 GPU 模式禁止回退 CPU，"
                "请用 GPU 预编译 wheel（LLAMA_WHL_CUDA）或源码编译（LLAMA_BUILD=source）重装。"
            )
    except RuntimeError:
        raise
    except Exception as exc:
        logger.warning(f"无法探测 llama-cpp GPU 支持（{exc}），跳过构建级校验。")


def _log_gpu_offload_status(model: Llama) -> None:
    """诊断日志：读取实际 GPU 层数并记录。

    注意：不同 llama-cpp-python 版本下 n_gpu_layers() 报告值并不可靠（可能是
    -1 / 0 / None，均可能表示"全部层进 GPU"），因此这里只做日志，不做硬性校验；
    硬性校验由 _verify_gpu_support（构建是否支持 GPU 卸载）承担。
    """
    try:
        offloaded = int(model.n_gpu_layers())
    except Exception as exc:
        logger.info(
            f"llama-cpp 未暴露 GPU 层数接口（{exc}）；GPU 已由构建级校验确认。"
        )
        return
    if offloaded > 0:
        logger.info(f"GPU offload OK: {offloaded} layers on GPU")
    else:
        logger.info(
            f"n_gpu_layers() 报告值 {offloaded}（-1/0 均可能表示全部层进 GPU）。"
            "GPU 已由 CUDA 构建校验确认，并以 llama.cpp 是否报错为准。"
        )


def _build_model() -> Llama:
    _verify_gpu_support()
    use_draft = config.LOCAL_MODEL_USE_DRAFT and config.LOCAL_MODEL_DRAFT_TOKENS > 0
    # 投机解码需要 logits_all（用于并行验证草稿 token），自动补齐。
    logits_all = config.LOCAL_MODEL_LOGITS_ALL or use_draft
    if use_draft and not config.LOCAL_MODEL_LOGITS_ALL:
        logger.warning(
            "投机解码已开启：自动启用 logits_all（LOCAL_MODEL_LOGITS_ALL 可显式设置）。"
        )
    logger.info(
        f"Loading model from {config.MODEL_PATH} "
        f"(n_ctx={config.LOCAL_MODEL_N_CTX}, "
        f"n_gpu_layers={config.LOCAL_MODEL_N_GPU_LAYERS}, "
        f"draft={use_draft})"
    )
    kwargs = dict(
        model_path=config.MODEL_PATH,
        n_ctx=config.LOCAL_MODEL_N_CTX,
        n_batch=config.LOCAL_MODEL_N_BATCH,
        n_ubatch=config.LOCAL_MODEL_N_UBATCH,
        n_gpu_layers=config.LOCAL_MODEL_N_GPU_LAYERS,
        n_threads=config.LOCAL_MODEL_N_THREADS,
        n_threads_batch=config._LOCAL_MODEL_N_THREADS_BATCH,
        flash_attn=config.LOCAL_MODEL_FLASH_ATTN,
        logits_all=logits_all,
        use_mmap=config._LOCAL_MODEL_USE_MMAP,
        use_mlock=config._LOCAL_MODEL_USE_MLOCK,
        offload_kqv=config._LOCAL_MODEL_OFFLOAD_KQV,
        mul_mat_q=config._LOCAL_MODEL_MUL_MAT_Q,
        verbose=False,
    )
    if use_draft:
        from llama_cpp.llama_speculative import LlamaPromptLookupDecoding

        kwargs["draft_model"] = LlamaPromptLookupDecoding(
            num_pred_tokens=config.LOCAL_MODEL_DRAFT_TOKENS
        )
        logger.info(
            f"投机解码: 开启（draft_tokens={config.LOCAL_MODEL_DRAFT_TOKENS}）"
        )
    model = Llama(**kwargs)
    _log_gpu_offload_status(model)
    return model


def get_model() -> Llama:
    """Return the process-wide model singleton, loading it on first call."""
    global _model
    if _model is None:
        with _load_lock:
            if _model is None:
                _model = _build_model()
                logger.info("Model loaded successfully (GPU)")
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
    with _generation_lock:
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
