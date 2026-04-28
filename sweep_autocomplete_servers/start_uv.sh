#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
VENV_DIR="$SCRIPT_DIR/.venv"
PYTHON_EXE="$VENV_DIR/bin/python"
MAX_LOG_SIZE=$((10 * 1024 * 1024))
LOG_DIR="$SCRIPT_DIR/logs"
LOG_FILE="$LOG_DIR/server.log"
PID_FILE="$LOG_DIR/llama_server.pid"
MONITOR_PID_FILE="$LOG_DIR/llama_server_log_monitor.pid"
LOG_DATE_FILE="$LOG_DIR/.llama_server_log_date"
LEGACY_LOG_FILE="$SCRIPT_DIR/server.log"
LEGACY_PID_FILE="$SCRIPT_DIR/server.pid"
LEGACY_MONITOR_PID_FILE="$SCRIPT_DIR/server_log_monitor.pid"

CMD="${1:-start}"
HOST="${2:-0.0.0.0}"
PORT="${3:-8006}"

# ----------------------
# Tunables (edit here)
# ----------------------
: "${MODEL_PROFILE:=quality}" # latency | quality
# Service behavior
: "${ENABLE_RETRIEVAL_FALLBACK:=true}"
: "${NEXT_EDIT_AUTOCOMPLETE_ENDPOINT:=}"
: "${MODEL_PATH:=/root/.cache/modelscope/hub/models/sweepai/sweep-next-edit-1.5B/sweep-next-edit-1.5b.q8_0.v2.gguf}"
#: "${MODEL_PATH:=/root/.cache/modelscope/hub/models/sweepai/sweep-next-edit-0.5B/sweep-next-edit-0.5b.q8_0.gguf}"
: "${MODEL_FILENAME:=sweep-next-edit-0.5b.q8_0.gguf}"
: "${LOG_MODEL_PROMPT:=true}"
: "${LOG_MODEL_RAW_OUTPUT:=true}"
: "${MODEL_LOG_MAX_CHARS:=0}"

# llama.cpp runtime (A100 profiles)
if [ "$MODEL_PROFILE" = "quality" ]; then
  : "${LOCAL_MODEL_N_CTX:=16384}"
  : "${LOCAL_MODEL_N_BATCH:=2048}"
  : "${LOCAL_MODEL_N_UBATCH:=1024}"
  : "${LOCAL_MODEL_N_GPU_LAYERS:=-1}"
  : "${LOCAL_MODEL_N_THREADS:=8}"
  : "${LOCAL_MODEL_N_THREADS_BATCH:=16}"
  : "${LOCAL_MODEL_FLASH_ATTN:=true}"
  : "${LOCAL_MODEL_OFFLOAD_KQV:=true}"
  : "${LOCAL_MODEL_MUL_MAT_Q:=true}"
  : "${LOCAL_MODEL_USE_MMAP:=true}"
  : "${LOCAL_MODEL_USE_MLOCK:=false}"
  : "${LOCAL_MODEL_USE_DRAFT:=true}"
  : "${LOCAL_MODEL_DRAFT_TOKENS:=32}"
  : "${LOCAL_MODEL_LOGITS_ALL:=true}"
  : "${LOCAL_MODEL_VERBOSE:=false}"
else
  : "${LOCAL_MODEL_N_CTX:=8192}"
  : "${LOCAL_MODEL_N_BATCH:=4096}"
  : "${LOCAL_MODEL_N_UBATCH:=2048}"
  : "${LOCAL_MODEL_N_GPU_LAYERS:=-1}"
  : "${LOCAL_MODEL_N_THREADS:=8}"
  : "${LOCAL_MODEL_N_THREADS_BATCH:=16}"
  : "${LOCAL_MODEL_FLASH_ATTN:=true}"
  : "${LOCAL_MODEL_OFFLOAD_KQV:=true}"
  : "${LOCAL_MODEL_MUL_MAT_Q:=true}"
  : "${LOCAL_MODEL_USE_MMAP:=true}"
  : "${LOCAL_MODEL_USE_MLOCK:=false}"
  : "${LOCAL_MODEL_USE_DRAFT:=false}"
  : "${LOCAL_MODEL_DRAFT_TOKENS:=32}"
  : "${LOCAL_MODEL_LOGITS_ALL:=false}"
  : "${LOCAL_MODEL_VERBOSE:=false}"
fi

# Dependency/bootstrap behavior
: "${INSTALL_DEPS_ON_START:=false}"
: "${FORCE_REINSTALL_DEPS:=false}"
: "${LLAMA_CUDA_BUILD:=false}"
: "${LLAMA_FORCE_REBUILD:=false}"
: "${LLAMA_AUTO_REBUILD_IF_NO_GPU:=true}"
: "${LLAMA_CMAKE_ARGS:=-DGGML_CUDA=on}"

export ENABLE_RETRIEVAL_FALLBACK
export NEXT_EDIT_AUTOCOMPLETE_ENDPOINT
export MODEL_PATH
export MODEL_FILENAME
export LOG_MODEL_PROMPT
export LOG_MODEL_RAW_OUTPUT
export MODEL_LOG_MAX_CHARS
export LOCAL_MODEL_N_CTX
export LOCAL_MODEL_N_BATCH
export LOCAL_MODEL_N_UBATCH
export LOCAL_MODEL_N_GPU_LAYERS
export LOCAL_MODEL_N_THREADS
export LOCAL_MODEL_N_THREADS_BATCH
export LOCAL_MODEL_FLASH_ATTN
export LOCAL_MODEL_OFFLOAD_KQV
export LOCAL_MODEL_MUL_MAT_Q
export LOCAL_MODEL_USE_MMAP
export LOCAL_MODEL_USE_MLOCK
export LOCAL_MODEL_USE_DRAFT
export LOCAL_MODEL_DRAFT_TOKENS
export LOCAL_MODEL_LOGITS_ALL
export LOCAL_MODEL_VERBOSE

is_true() {
  case "${1,,}" in
    1|true|yes|on) return 0 ;;
    *) return 1 ;;
  esac
}

ensure_uv() {
  if ! command -v uv >/dev/null 2>&1; then
    echo "uv is not installed. Install first: https://docs.astral.sh/uv/getting-started/installation/" >&2
    exit 1
  fi
}

ensure_venv() {
  mkdir -p "$LOG_DIR"
  if [ ! -x "$PYTHON_EXE" ]; then
    echo "[1/4] Creating uv virtualenv..."
    uv venv --seed "$VENV_DIR" >/dev/null
  fi
}

deps_ok() {
  uv run --python "$PYTHON_EXE" python -c "import fastapi,uvicorn,httptools,loguru,requests,regex,pydantic,numpy,scipy,brotli" >/dev/null 2>&1
}

llama_installed() {
  uv run --python "$PYTHON_EXE" python -c "import llama_cpp" >/dev/null 2>&1
}

llama_gpu_supported() {
  uv run --python "$PYTHON_EXE" python -c "import sys; from llama_cpp import llama_cpp as l; fn=getattr(l,'llama_supports_gpu_offload',None); sys.exit(0 if (callable(fn) and fn()) else 1)" >/dev/null 2>&1
}

has_pip_module() {
  "$PYTHON_EXE" -m pip --version >/dev/null 2>&1
}

ensure_pip_module() {
  if has_pip_module; then
    return
  fi
  echo "[3/4] pip module missing in venv, seeding via uv..."
  if ! uv venv --seed "$VENV_DIR" >/dev/null 2>&1; then
    echo "[3/4] ERROR: failed to seed pip into venv via uv." >&2
    exit 1
  fi
  if ! has_pip_module; then
    echo "[3/4] ERROR: pip still unavailable in venv after uv seeding." >&2
    exit 1
  fi
}

install_base_deps() {
  echo "[2/4] Installing base dependencies..."
  uv pip install --python "$PYTHON_EXE" \
    fastapi \
    uvicorn \
    httptools \
    loguru \
    requests \
    regex \
    pydantic \
    numpy \
    scipy \
    brotli \
    openai \
    distro \
    jiter \
    sniffio >/dev/null
}

install_llama_build_deps() {
  ensure_pip_module
  echo "[3/4] Installing llama build toolchain..."
  "$PYTHON_EXE" -m pip install --upgrade --no-cache-dir \
    pip setuptools wheel \
    cmake ninja \
    scikit-build-core \
    pybind11 \
    flit_core >/dev/null
}

install_llama() {
  if is_true "$LLAMA_CUDA_BUILD"; then
    if ! command -v nvcc >/dev/null 2>&1; then
      echo "[3/4] ERROR: nvcc not found in PATH. CUDA build cannot proceed." >&2
      echo "        Install CUDA toolkit and ensure nvcc is available." >&2
      exit 1
    fi
  fi

  if ! llama_installed || is_true "$LLAMA_FORCE_REBUILD"; then
    echo "[3/4] Installing llama-cpp-python..."
    if is_true "$LLAMA_CUDA_BUILD"; then
      install_llama_build_deps
      CMAKE_ARGS="$LLAMA_CMAKE_ARGS" FORCE_CMAKE=1 "$PYTHON_EXE" -m pip install \
        --upgrade --force-reinstall --no-cache-dir --no-binary :all: \
        -v llama-cpp-python
    else
      uv pip install --python "$PYTHON_EXE" llama-cpp-python >/dev/null
    fi
    return
  fi

  if is_true "$LLAMA_CUDA_BUILD" && ! llama_gpu_supported; then
    if is_true "$LLAMA_AUTO_REBUILD_IF_NO_GPU"; then
      echo "[3/4] llama-cpp has no GPU offload, rebuilding with CUDA backend..."
      install_llama_build_deps
      CMAKE_ARGS="$LLAMA_CMAKE_ARGS" FORCE_CMAKE=1 "$PYTHON_EXE" -m pip install \
        --upgrade --force-reinstall --no-cache-dir --no-binary :all: \
        -v llama-cpp-python
    else
      echo "[3/4] WARNING: llama-cpp has no GPU offload. Skip auto-rebuild on startup."
      echo "        Run with LLAMA_FORCE_REBUILD=true to rebuild manually."
    fi
  fi
}

ensure_env() {
  ensure_uv
  ensure_venv

  if is_true "$INSTALL_DEPS_ON_START"; then
    if ! deps_ok || is_true "$FORCE_REINSTALL_DEPS"; then
      install_base_deps
    fi
  fi

  # Always check llama GPU capability on startup:
  # quick when healthy; auto-rebuild only when GPU offload is missing.
  install_llama
}

is_running() {
  [ -f "$PID_FILE" ] && kill -0 "$(cat "$PID_FILE")" >/dev/null 2>&1
}

rotate_log_if_needed() {
  local today current_date size backup
  today="$(date +%F)"
  current_date="$today"
  [ -f "$LOG_DATE_FILE" ] && current_date="$(cat "$LOG_DATE_FILE")"

  [ -f "$LOG_FILE" ] || touch "$LOG_FILE"
  size="$(wc -c < "$LOG_FILE" | tr -d ' ')"

  if [ "$size" -ge "$MAX_LOG_SIZE" ] || [ "$today" != "$current_date" ]; then
    backup="$LOG_DIR/server_$(date +%F_%H%M%S).log"
    cp "$LOG_FILE" "$backup"
    : > "$LOG_FILE"
    echo "$today" > "$LOG_DATE_FILE"
    echo "[log-rotate] Backup log to: $backup"
  fi

  [ -f "$LOG_DATE_FILE" ] || echo "$today" > "$LOG_DATE_FILE"
}

rotate_log_on_start() {
  local today backup
  today="$(date +%F)"
  mkdir -p "$LOG_DIR"

  if [ -f "$LOG_FILE" ] && [ -s "$LOG_FILE" ]; then
    backup="$LOG_DIR/server_$(date +%F_%H%M%S).log"
    cp "$LOG_FILE" "$backup"
    echo "[startup-rotate] Backup log to: $backup"
  fi

  : > "$LOG_FILE"
  echo "$today" > "$LOG_DATE_FILE"
}

start_monitor() {
  (
    while true; do
      rotate_log_if_needed
      sleep 30
    done
  ) >/dev/null 2>&1 &
  echo $! > "$MONITOR_PID_FILE"
}

stop_monitor() {
  if [ -f "$MONITOR_PID_FILE" ] && kill -0 "$(cat "$MONITOR_PID_FILE")" >/dev/null 2>&1; then
    kill "$(cat "$MONITOR_PID_FILE")" >/dev/null 2>&1 || true
  fi
  rm -f "$MONITOR_PID_FILE"
}

stop_legacy_processes() {
  if [ -f "$LEGACY_MONITOR_PID_FILE" ] && kill -0 "$(cat "$LEGACY_MONITOR_PID_FILE")" >/dev/null 2>&1; then
    kill "$(cat "$LEGACY_MONITOR_PID_FILE")" >/dev/null 2>&1 || true
  fi
  rm -f "$LEGACY_MONITOR_PID_FILE"

  if [ -f "$LEGACY_PID_FILE" ] && kill -0 "$(cat "$LEGACY_PID_FILE")" >/dev/null 2>&1; then
    kill "$(cat "$LEGACY_PID_FILE")" >/dev/null 2>&1 || true
  fi
  rm -f "$LEGACY_PID_FILE"
}

start_server() {
  if is_running; then
    echo "Service already running, PID=$(cat "$PID_FILE")"
    exit 0
  fi

  stop_legacy_processes
  ensure_env
  rotate_log_on_start

  echo "[4/4] Starting service at http://$HOST:$PORT"
  (
    nohup env CUDA_VISIBLE_DEVICES=0 uv run --python "$PYTHON_EXE" python -m cli --host "$HOST" --port "$PORT" >>"$LOG_FILE" 2>&1 &
    echo $! > "$PID_FILE"
  )

  start_monitor
  echo "Service started, PID=$(cat "$PID_FILE")"
  echo "Log file: $LOG_FILE"
}

stop_server() {
  if is_running; then
    kill "$(cat "$PID_FILE")" >/dev/null 2>&1 || true
    rm -f "$PID_FILE"
    echo "Service stopped"
  else
    rm -f "$PID_FILE"
    echo "Service not running"
  fi
  stop_monitor
  stop_legacy_processes
}

restart_server() {
  stop_server
  start_server
}

show_status() {
  if is_running; then
    local pid
    pid="$(cat "$PID_FILE")"
    echo "Service status: running"
    echo "PID: $pid"
  else
    echo "Service status: stopped"
  fi

  echo "Log file: $LOG_FILE"
  if [ -f "$LOG_FILE" ]; then
    echo "Log size (bytes): $(wc -c < "$LOG_FILE" | tr -d ' ')"
    echo "Last updated: $(date -r "$LOG_FILE" "+%F %T")"
  fi

  if [ -x "$PYTHON_EXE" ] && llama_installed; then
    if llama_gpu_supported; then
      echo "llama-cpp GPU offload: supported"
    else
      echo "llama-cpp GPU offload: NOT supported (will be slow; rebuild CUDA backend)"
    fi
  fi

  echo "Current key parameters:"
  echo "  MODEL_PROFILE=$MODEL_PROFILE"
  echo "  ENABLE_RETRIEVAL_FALLBACK=$ENABLE_RETRIEVAL_FALLBACK"
  echo "  MODEL_PATH=$MODEL_PATH"
  echo "  LOG_MODEL_PROMPT=$LOG_MODEL_PROMPT"
  echo "  LOG_MODEL_RAW_OUTPUT=$LOG_MODEL_RAW_OUTPUT"
  echo "  MODEL_LOG_MAX_CHARS=$MODEL_LOG_MAX_CHARS"
  echo "  LLAMA_AUTO_REBUILD_IF_NO_GPU=$LLAMA_AUTO_REBUILD_IF_NO_GPU"
  echo "  LOCAL_MODEL_N_CTX=$LOCAL_MODEL_N_CTX"
  echo "  LOCAL_MODEL_N_BATCH=$LOCAL_MODEL_N_BATCH"
  echo "  LOCAL_MODEL_N_UBATCH=$LOCAL_MODEL_N_UBATCH"
  echo "  LOCAL_MODEL_N_THREADS=$LOCAL_MODEL_N_THREADS"
  echo "  LOCAL_MODEL_N_THREADS_BATCH=$LOCAL_MODEL_N_THREADS_BATCH"
  echo "  LOCAL_MODEL_N_GPU_LAYERS=$LOCAL_MODEL_N_GPU_LAYERS"
  echo "  LOCAL_MODEL_FLASH_ATTN=$LOCAL_MODEL_FLASH_ATTN"
  echo "  LOCAL_MODEL_USE_DRAFT=$LOCAL_MODEL_USE_DRAFT"
}

show_log() {
  touch "$LOG_FILE"
  tail -n 200 -f "$LOG_FILE"
}

case "$CMD" in
  start)
    start_server
    ;;
  stop)
    stop_server
    ;;
  restart)
    restart_server
    ;;
  status)
    show_status
    ;;
  log)
    show_log
    ;;
  *)
    echo "Usage: $0 {start|stop|restart|status|log} [host] [port]"
    exit 1
    ;;
esac
