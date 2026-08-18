#!/usr/bin/env bash
# Sweep next-edit autocomplete server — 简洁启动器（Linux/GPU 服务器）。
#
# 用法:
#   ./start_use_uv.sh start   [host] [port]   # 后台启动（默认 0.0.0.0:8006）
#   ./start_use_uv.sh stop                    # 停止
#   ./start_use_uv.sh restart [host] [port]
#   ./start_use_uv.sh status                  # 状态与关键参数
#   ./start_use_uv.sh logs                    # 实时日志
#
# 依赖策略：
#   - 全部用 uv 安装；
#   - llama-cpp 默认装官方预编译 GPU wheel（LLAMA_WHL_CUDA 对应 CUDA 版本）；
#   - 网络差/下载慢时用源码编译：LLAMA_BUILD=source ./start_use_uv.sh start
#   - 无 CPU 回退（纯 GPU）。
#   切换安装模式后请用 FORCE_REINSTALL=true 重新安装。
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
VENV_DIR="$SCRIPT_DIR/.venv"
PYTHON_EXE="$VENV_DIR/bin/python"
LOG_DIR="$SCRIPT_DIR/logs"
LOG_FILE="$LOG_DIR/server.log"
PID_FILE="$LOG_DIR/server.pid"
MAX_LOG_SIZE=$((10 * 1024 * 1024)) # 10MB，超过则启动时轮转

CMD="${1:-start}"
HOST="${2:-0.0.0.0}"
PORT="${3:-8006}"

# ---------------------------------------------------------------------------
# ENV（少数常用项，均可覆盖）
# ---------------------------------------------------------------------------
: "${MODEL_PATH:=/root/.cache/modelscope/hub/models/sweepai/sweep-next-edit-1.5B/sweep-next-edit-1.5b.q8_0.v2.gguf}"
: "${NEXT_EDIT_AUTOCOMPLETE_ENDPOINT:=}"   # 留空=本地模型；填远程端点走 HTTP
: "${LOCAL_MODEL_N_CTX:=16384}"            # 上下文长度
: "${LOCAL_MODEL_N_GPU_LAYERS:=-1}"        # -1=全部层进 GPU
: "${LOCAL_MODEL_N_THREADS:=8}"            # 0=自动
# 提速可调项（默认稳妥）
: "${LOCAL_MODEL_N_BATCH:=8192}"           # 每批最大 token；调大可加速 prefill
: "${LOCAL_MODEL_N_UBATCH:=4096}"          # 内部计算批，需 <= N_BATCH
: "${LOCAL_MODEL_FLASH_ATTN:=true}"       # 需构建带 GGML_FLASH_ATTN，否则加载失败
# 投机解码（默认关，方便对比；开启时自动补 logits_all）
: "${LOCAL_MODEL_USE_DRAFT:=false}"
: "${LOCAL_MODEL_DRAFT_TOKENS:=32}"
: "${LOCAL_MODEL_LOGITS_ALL:=false}"
: "${ENABLE_RETRIEVAL_FALLBACK:=true}"
: "${LOG_MODEL_PROMPT:=false}"
: "${LOG_MODEL_RAW_OUTPUT:=false}"
: "${MODEL_LOG_MAX_CHARS:=0}"
: "${CUDA_VISIBLE_DEVICES:=0}"

# 安装相关
# LLAMA_BUILD: wheel = 官方预编译 GPU wheel（默认） | source = CUDA 源码编译（网络差/下载慢时用）
: "${LLAMA_BUILD:=source}"
: "${LLAMA_WHL_CUDA:=cu124}"   # GPU wheel 标签，对应本机 CUDA 版本（cu121~cu126）
: "${LLAMA_CMAKE_ARGS:=-DGGML_CUDA=on -DCMAKE_CUDA_ARCHITECTURES=80 -DGGML_CUDA_FA_ALL_QUANTS=ON}"   # source 模式 CMake 参数（A100=80）
: "${FORCE_REINSTALL:=false}"  # true=强制重装依赖与 llama-cpp（切换安装模式时需用）

export MODEL_PATH NEXT_EDIT_AUTOCOMPLETE_ENDPOINT
export LOCAL_MODEL_N_CTX LOCAL_MODEL_N_GPU_LAYERS LOCAL_MODEL_N_THREADS
export LOCAL_MODEL_N_BATCH LOCAL_MODEL_N_UBATCH LOCAL_MODEL_FLASH_ATTN
export LOCAL_MODEL_USE_DRAFT LOCAL_MODEL_DRAFT_TOKENS LOCAL_MODEL_LOGITS_ALL
export ENABLE_RETRIEVAL_FALLBACK LOG_MODEL_PROMPT LOG_MODEL_RAW_OUTPUT MODEL_LOG_MAX_CHARS
export CUDA_VISIBLE_DEVICES

# ---------------------------------------------------------------------------
# 工具函数
# ---------------------------------------------------------------------------
is_true() {
  case "${1,,}" in
    1|true|yes|on) return 0 ;;
    *) return 1 ;;
  esac
}

llama_installed() {
  "$PYTHON_EXE" -c "import llama_cpp" >/dev/null 2>&1
}

llama_gpu_supported() {
  "$PYTHON_EXE" -c "
from llama_cpp import llama_cpp as l
fn = getattr(l, 'llama_supports_gpu_offload', None)
import sys; sys.exit(0 if (callable(fn) and fn()) else 1)
" >/dev/null 2>&1
}

# 源码 CUDA 编译 llama-cpp（LLAMA_BUILD=source 时使用）
install_llama_source() {
  echo "[deps] CUDA 源码编译 llama-cpp-python（LLAMA_BUILD=source）..."
  command -v nvcc >/dev/null 2>&1 || {
    echo "[deps] 错误: 未找到 nvcc，请先安装 CUDA Toolkit（源码编译必需）" >&2
    exit 1
  }
  echo "[deps] 安装编译工具 cmake/ninja ..."
  uv pip install --python "$PYTHON_EXE" cmake ninja
  echo "[deps] 开始编译（CMAKE_ARGS=$LLAMA_CMAKE_ARGS），耗时较长请耐心等待..."
  CMAKE_ARGS="$LLAMA_CMAKE_ARGS" FORCE_CMAKE=1 \
    uv pip install --python "$PYTHON_EXE" --no-cache-dir --no-binary :all: \
    --reinstall-package llama-cpp-python llama-cpp-python
  # llama-cpp 的运行时依赖（numpy 已由项目依赖安装，其余补上）
  uv pip install --python "$PYTHON_EXE" numpy diskcache jinja2 typing-extensions
}

# ---------------------------------------------------------------------------
# 环境准备：全部用 uv；llama-cpp 默认装 GPU 预编译 wheel（LLAMA_BUILD=source 则源码编译）
# ---------------------------------------------------------------------------
ensure_env() {
  command -v uv >/dev/null 2>&1 || {
    echo "uv 未安装，请先安装: https://docs.astral.sh/uv/getting-started/installation/" >&2
    exit 1
  }
  [ -x "$PYTHON_EXE" ] || uv venv --seed "$VENV_DIR"

  if ! "$PYTHON_EXE" -c "import fastapi,uvicorn" >/dev/null 2>&1 || is_true "$FORCE_REINSTALL"; then
    echo "[deps] 用 uv 安装项目依赖 (pyproject.toml)..."
    uv pip install --python "$PYTHON_EXE" -e "$SCRIPT_DIR"
  fi

  if ! llama_installed || ! llama_gpu_supported || is_true "$FORCE_REINSTALL"; then
    if [ "$LLAMA_BUILD" = "source" ]; then
      install_llama_source
    else
      echo "[deps] 用 uv 安装 GPU 版 llama-cpp-python（$LLAMA_WHL_CUDA 预编译 wheel）..."
      uv pip install --python "$PYTHON_EXE" \
        --index-url "https://abetlen.github.io/llama-cpp-python/whl/$LLAMA_WHL_CUDA" \
        --no-deps --reinstall-package llama-cpp-python llama-cpp-python
      # llama-cpp 的运行时依赖（numpy 已由项目依赖安装，其余补上）
      uv pip install --python "$PYTHON_EXE" numpy diskcache jinja2 typing-extensions
    fi
    llama_gpu_supported || {
      echo "[deps] 错误: 安装/编译后仍不支持 GPU 卸载。" >&2
      echo "       wheel 模式请确认 LLAMA_WHL_CUDA 与本机 CUDA 版本匹配；" >&2
      echo "       source 模式请检查上方 CMake 日志与 LLAMA_CMAKE_ARGS。" >&2
      exit 1
    }
  fi
}

verify_gpu() {
  # 纯 GPU 策略：必须支持 GPU 卸载，否则拒绝启动。
  llama_gpu_supported || {
    echo "错误: llama-cpp 不支持 GPU 卸载（纯 GPU 模式禁止 CPU 回退）。" >&2
    echo "      请用 GPU 预编译 wheel 重装：FORCE_REINSTALL=true LLAMA_WHL_CUDA=<cuXXX> $0 restart" >&2
    exit 1
  }
  echo "[gpu] llama-cpp GPU 卸载: 支持 ✓"
}

is_running() {
  [ -f "$PID_FILE" ] && kill -0 "$(cat "$PID_FILE")" >/dev/null 2>&1
}

# ---------------------------------------------------------------------------
# 服务控制
# ---------------------------------------------------------------------------
start_server() {
  if is_running; then
    echo "服务已在运行，PID=$(cat "$PID_FILE")"
    return
  fi
  ensure_env
  verify_gpu
  mkdir -p "$LOG_DIR"
  if [ -f "$LOG_FILE" ] && [ "$(wc -c < "$LOG_FILE" | tr -d ' ')" -ge "$MAX_LOG_SIZE" ]; then
    mv "$LOG_FILE" "$LOG_FILE.$(date +%F_%H%M%S)"
  fi
  (
    cd "$SCRIPT_DIR"
    nohup env CUDA_VISIBLE_DEVICES="$CUDA_VISIBLE_DEVICES" \
      "$PYTHON_EXE" -m sweep_autocomplete.cli --host "$HOST" --port "$PORT" \
      >>"$LOG_FILE" 2>&1 &
    echo $! > "$PID_FILE"
  )
  echo "已启动 http://$HOST:$PORT (PID=$(cat "$PID_FILE"))，日志: $LOG_FILE"
}

stop_server() {
  if is_running; then
    kill "$(cat "$PID_FILE")" 2>/dev/null || true
    rm -f "$PID_FILE"
    echo "已停止"
  else
    rm -f "$PID_FILE"
    echo "服务未在运行"
  fi
}

show_status() {
  if is_running; then
    echo "状态: 运行中  PID=$(cat "$PID_FILE")"
  else
    echo "状态: 已停止"
  fi
  echo "日志: $LOG_FILE ($([ -f "$LOG_FILE" ] && wc -c < "$LOG_FILE" | tr -d ' ' || echo 0) bytes)"
  if [ ! -x "$PYTHON_EXE" ]; then
    echo "llama-cpp: 环境未就绪（尚无 .venv）"
  elif llama_installed; then
    if llama_gpu_supported; then
      echo "llama-cpp: 已安装, GPU 卸载支持 ✓"
    else
      echo "llama-cpp: 已安装但非 GPU 版（重启时将自动换装 GPU wheel）"
    fi
  else
    echo "llama-cpp: 未安装（重启时将自动安装 GPU wheel）"
  fi
  echo "关键参数: MODEL_PATH=$MODEL_PATH"
  echo "           安装模式: LLAMA_BUILD=$LLAMA_BUILD (wheel:$LLAMA_WHL_CUDA / cmake:$LLAMA_CMAKE_ARGS)"
  echo "           N_CTX=$LOCAL_MODEL_N_CTX N_GPU_LAYERS=$LOCAL_MODEL_N_GPU_LAYERS N_THREADS=$LOCAL_MODEL_N_THREADS"
}

case "$CMD" in
  start)   start_server ;;
  stop)    stop_server ;;
  restart) stop_server; start_server ;;
  status)  show_status ;;
  logs)    [ -f "$LOG_FILE" ] || touch "$LOG_FILE"; tail -n 200 -f "$LOG_FILE" ;;
  *)
    echo "用法: $0 {start|stop|restart|status|logs} [host] [port]" >&2
    exit 1
    ;;
esac
