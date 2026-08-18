#!/usr/bin/env bash
# Sweep next-edit autocomplete server — simple launcher (Linux/GPU server).
#
# Usage:
#   ./start_use_uv.sh start [host] [port]    # 启动（默认 0.0.0.0:8006）
#   ./start_use_uv.sh stop                   # 停止
#   ./start_use_uv.sh restart [host] [port]
#   ./start_use_uv.sh status                 # 查看状态与关键参数
#   ./start_use_uv.sh log                    # 实时查看日志
#
# 所有配置都可通过环境变量覆盖，默认值即最优速度配置（见下方 ENV 区）。
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
# ENV — 默认值即最优速度配置，按需用环境变量覆盖
# ---------------------------------------------------------------------------
: "${MODEL_PATH:=/root/.cache/modelscope/hub/models/sweepai/sweep-next-edit-1.5B/sweep-next-edit-1.5b.q8_0.v2.gguf}"
: "${NEXT_EDIT_AUTOCOMPLETE_ENDPOINT:=}"   # 留空=本地模型；填远程端点则走 HTTP

# llama.cpp 运行时
: "${LOCAL_MODEL_N_CTX:=16384}"
: "${LOCAL_MODEL_N_BATCH:=4096}"
: "${LOCAL_MODEL_N_UBATCH:=2048}"
: "${LOCAL_MODEL_N_GPU_LAYERS:=-1}"        # -1=全部进 GPU；CPU 构建置 0
: "${LOCAL_MODEL_N_THREADS:=0}"            # 0=自动
: "${LOCAL_MODEL_N_THREADS_BATCH:=0}"
: "${LOCAL_MODEL_FLASH_ATTN:=true}"
: "${LOCAL_MODEL_OFFLOAD_KQV:=true}"
: "${LOCAL_MODEL_MUL_MAT_Q:=true}"
: "${LOCAL_MODEL_USE_MMAP:=true}"
: "${LOCAL_MODEL_USE_MLOCK:=false}"
: "${LOCAL_MODEL_USE_DRAFT:=false}"        # 投机解码：GPU 短补全反而有开销，默认关
: "${LOCAL_MODEL_DRAFT_TOKENS:=32}"
: "${LOCAL_MODEL_LOGITS_ALL:=false}"
: "${LOCAL_MODEL_VERBOSE:=false}"

# 日志（默认关，避免拖慢热路径/刷屏）
: "${LOG_MODEL_PROMPT:=false}"
: "${LOG_MODEL_RAW_OUTPUT:=false}"
: "${MODEL_LOG_MAX_CHARS:=0}"
: "${CUDA_VISIBLE_DEVICES:=0}"

# 依赖安装
: "${LLAMA_CUDA_BUILD:=false}"             # true=从源码编译 CUDA 版
: "${LLAMA_CMAKE_ARGS:=-DGGML_CUDA=on}"
: "${FORCE_REINSTALL:=false}"              # true=强制重装依赖

export MODEL_PATH NEXT_EDIT_AUTOCOMPLETE_ENDPOINT
export LOCAL_MODEL_N_CTX LOCAL_MODEL_N_BATCH LOCAL_MODEL_N_UBATCH LOCAL_MODEL_N_GPU_LAYERS
export LOCAL_MODEL_N_THREADS LOCAL_MODEL_N_THREADS_BATCH LOCAL_MODEL_FLASH_ATTN
export LOCAL_MODEL_OFFLOAD_KQV LOCAL_MODEL_MUL_MAT_Q LOCAL_MODEL_USE_MMAP LOCAL_MODEL_USE_MLOCK
export LOCAL_MODEL_USE_DRAFT LOCAL_MODEL_DRAFT_TOKENS LOCAL_MODEL_LOGITS_ALL LOCAL_MODEL_VERBOSE
export LOG_MODEL_PROMPT LOG_MODEL_RAW_OUTPUT MODEL_LOG_MAX_CHARS CUDA_VISIBLE_DEVICES

# ---------------------------------------------------------------------------
# 环境准备
# ---------------------------------------------------------------------------
ensure_env() {
  command -v uv >/dev/null 2>&1 || {
    echo "uv 未安装，请先安装: https://docs.astral.sh/uv/getting-started/installation/" >&2
    exit 1
  }
  [ -x "$PYTHON_EXE" ] || uv venv --seed "$VENV_DIR"
  if ! "$PYTHON_EXE" -c "import fastapi,uvicorn" >/dev/null 2>&1 || is_true "$FORCE_REINSTALL"; then
    echo "[deps] 安装项目依赖 (pyproject.toml)..."
    uv pip install --python "$PYTHON_EXE" -e "$SCRIPT_DIR"
  fi
  if ! "$PYTHON_EXE" -c "import llama_cpp" >/dev/null 2>&1 || is_true "$FORCE_REINSTALL"; then
    echo "[deps] 安装 llama-cpp-python ..."
    if is_true "$LLAMA_CUDA_BUILD"; then
      command -v nvcc >/dev/null 2>&1 || { echo "LLAMA_CUDA_BUILD=true 但未找到 nvcc，请先安装 CUDA Toolkit" >&2; exit 1; }
      CMAKE_ARGS="$LLAMA_CMAKE_ARGS" FORCE_CMAKE=1 \
        "$PYTHON_EXE" -m pip install --no-cache-dir --no-binary :all: llama-cpp-python
    else
      "$PYTHON_EXE" -m pip install \
        --extra-index-url https://abetlen.github.io/llama-cpp-python/whl/cpu \
        llama-cpp-python
    fi
  fi
}

is_true() {
  case "${1,,}" in
    1|true|yes|on) return 0 ;;
    *) return 1 ;;
  esac
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
  if [ -x "$PYTHON_EXE" ]; then
    "$PYTHON_EXE" -c "import llama_cpp" >/dev/null 2>&1 && echo "llama-cpp: 已安装" || echo "llama-cpp: 未安装"
  fi
  echo "关键参数: MODEL_PATH=$MODEL_PATH"
  echo "           N_CTX=$LOCAL_MODEL_N_CTX N_BATCH=$LOCAL_MODEL_N_BATCH "
  echo "           N_GPU_LAYERS=$LOCAL_MODEL_N_GPU_LAYERS FLASH_ATTN=$LOCAL_MODEL_FLASH_ATTN"
}

case "$CMD" in
  start)   start_server ;;
  stop)    stop_server ;;
  restart) stop_server; start_server ;;
  status)  show_status ;;
  log)     [ -f "$LOG_FILE" ] || touch "$LOG_FILE"; tail -n 200 -f "$LOG_FILE" ;;
  *)
    echo "用法: $0 {start|stop|restart|status|log} [host] [port]" >&2
    exit 1
    ;;
esac
