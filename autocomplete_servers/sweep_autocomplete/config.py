"""Server settings — 只保留常用项，环境变量可覆盖。

其余 llama.cpp 细节参数已内部固定为稳妥的 GPU 默认值，不再暴露。
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
# 端点
# ---------------------------------------------------------------------------
# 远程 OpenAI 兼容端点；留空 = 使用本地模型
NEXT_EDIT_AUTOCOMPLETE_ENDPOINT = os.environ.get("NEXT_EDIT_AUTOCOMPLETE_ENDPOINT") or None

# ---------------------------------------------------------------------------
# 本地 GGUF 模型
# ---------------------------------------------------------------------------
MODEL_PATH = os.environ.get(
    "MODEL_PATH",
    "/root/.cache/modelscope/hub/models/sweepai/sweep-next-edit-1.5B/sweep-next-edit-1.5b.q8_0.v2.gguf",
)

# ---------------------------------------------------------------------------
# llama.cpp —— 常用参数（可覆盖）
# ---------------------------------------------------------------------------
LOCAL_MODEL_N_CTX = _int("LOCAL_MODEL_N_CTX", 16384)  # 上下文长度
LOCAL_MODEL_N_GPU_LAYERS = _int("LOCAL_MODEL_N_GPU_LAYERS", -1)  # -1 = 全部层进 GPU
LOCAL_MODEL_N_THREADS = _int("LOCAL_MODEL_N_THREADS", 8)  # 0 = 自动

# 提速相关（谨慎调整；默认值为稳妥基线）
LOCAL_MODEL_N_BATCH = _int("LOCAL_MODEL_N_BATCH", 2048)  # 每批最大 token 数；调大可加速 prefill
LOCAL_MODEL_N_UBATCH = _int("LOCAL_MODEL_N_UBATCH", 1024)  # 内部计算批，需 <= N_BATCH
LOCAL_MODEL_FLASH_ATTN = _bool("LOCAL_MODEL_FLASH_ATTN", False)  # 需构建带 GGML_FLASH_ATTN，否则加载失败

# 投机解码（prompt-lookup，默认关，方便对比；开启时代码会自动补 logits_all）
LOCAL_MODEL_USE_DRAFT = _bool("LOCAL_MODEL_USE_DRAFT", False)
LOCAL_MODEL_DRAFT_TOKENS = _int("LOCAL_MODEL_DRAFT_TOKENS", 32)
LOCAL_MODEL_LOGITS_ALL = _bool("LOCAL_MODEL_LOGITS_ALL", False)

# 内部固定取值（GPU 下稳妥）
_LOCAL_MODEL_N_THREADS_BATCH = 16
_LOCAL_MODEL_OFFLOAD_KQV = True
_LOCAL_MODEL_MUL_MAT_Q = True
_LOCAL_MODEL_USE_MMAP = True
_LOCAL_MODEL_USE_MLOCK = False
_LOCAL_MODEL_VERBOSE = False

# ---------------------------------------------------------------------------
# 行为
# ---------------------------------------------------------------------------
# 无建议时检索文件重试（仅在"无建议"路径上增加延迟）
ENABLE_RETRIEVAL_FALLBACK = _bool("ENABLE_RETRIEVAL_FALLBACK", True)

# ---------------------------------------------------------------------------
# 诊断日志（默认关，避免拖慢热路径/刷屏）
# ---------------------------------------------------------------------------
LOG_MODEL_PROMPT = _bool("LOG_MODEL_PROMPT", False)
LOG_MODEL_RAW_OUTPUT = _bool("LOG_MODEL_RAW_OUTPUT", False)
MODEL_LOG_MAX_CHARS = _int("MODEL_LOG_MAX_CHARS", 0)  # 0 = 不限
