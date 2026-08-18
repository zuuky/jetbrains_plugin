# sweep-autocomplete 服务

后台运行 llama.cpp 跑 **sweep-next-edit** 模型的补全服务。插件通过
`POST /backend/next_edit_autocomplete`（NDJSON 流式）获取 next-edit 建议，
`GET /health` 用于健康检查。

## 目录结构

```
autocomplete_servers/
├── pyproject.toml            # 项目定义（依赖 + sweep-autocomplete 命令入口）
├── start_use_uv.sh           # Linux/GPU 服务器启动脚本
├── start_use_uv.ps1          # Windows 启动脚本
├── README.md
└── sweep_autocomplete/
    ├── cli.py                # 命令行入口（uvicorn）
    ├── app.py                # FastAPI 应用（启动时后台预热模型）
    ├── config.py             # 配置唯一来源（仅保留常用项）
    └── autocomplete/         # 补全核心逻辑、本地 llama.cpp 推理
```

## 快速开始

**Linux（GPU 服务器，推荐）：**

```bash
export MODEL_PATH=/你的路径/sweep-next-edit-1.5b.q8_0.v2.gguf   # 默认指向 /root/... 可省略
./start_use_uv.sh start        # 首次运行自动用 uv 建环境并装 GPU wheel；默认 0.0.0.0:8006
./start_use_uv.sh status
./start_use_uv.sh logs
./start_use_uv.sh stop
```

**Windows：**

```powershell
$env:MODEL_PATH = 'D:\models\sweep-next-edit-1.5b.q8_0.v2.gguf'
.\start_use_uv.ps1 start
```

> ⚠️ **纯 GPU 策略**：只使用支持 GPU 卸载的 llama-cpp（官方预编译 GPU wheel）。
> 启动前强制校验 `llama_supports_gpu_offload`，不满足即**拒绝启动**；模型加载后还会
> 校验实际 GPU 层数 > 0 —— **无源码编译、无 CPU 回退**。

## 依赖安装方式（脚本自动完成，二选一）

**默认：GPU 预编译 wheel（`LLAMA_BUILD=wheel`）**

```bash
uv pip install --python .venv/bin/python \
  --index-url "https://abetlen.github.io/llama-cpp-python/whl/<cuXXX>" \
  --no-deps --reinstall-package llama-cpp-python llama-cpp-python
uv pip install --python .venv/bin/python numpy diskcache jinja2 typing-extensions
```

`<cuXXX>` 由 `LLAMA_WHL_CUDA` 指定（对应本机 CUDA 版本：`cu121`~`cu126`），默认 `cu124`。

**网络差/下载慢：CUDA 源码编译（`LLAMA_BUILD=source`）**

```bash
LLAMA_BUILD=source ./start_use_uv.sh start
# 可选：指定 GPU 架构（A100=80，V100=70，H100=90）
LLAMA_CMAKE_ARGS="-DGGML_CUDA=on -DCMAKE_CUDA_ARCHITECTURES=80" LLAMA_BUILD=source ./start_use_uv.sh start
```

> 源码编译需要 `nvcc`（CUDA Toolkit）与 cmake/ninja（脚本自动安装）；耗时较长。
> **切换安装模式后**请加 `FORCE_REINSTALL=true`（或删除 `.venv`）重新安装。

## 配置（环境变量，已精简）

| 变量                                          | 默认                                              | 说明                                                          |
|---------------------------------------------|-------------------------------------------------|-------------------------------------------------------------|
| `MODEL_PATH`                                | `/root/.cache/modelscope/.../1.5b.q8_0.v2.gguf` | 本地 GGUF 路径                                                  |
| `NEXT_EDIT_AUTOCOMPLETE_ENDPOINT`           | 空                                               | 远程 OpenAI 兼容端点；留空用本地模型                                      |
| `LOCAL_MODEL_N_CTX`                         | `16384`                                         | 上下文长度                                                       |
| `LOCAL_MODEL_N_GPU_LAYERS`                  | `-1`                                            | `-1` = 全部层进 GPU                                             |
| `LOCAL_MODEL_N_THREADS`                     | `8`                                             | 0 = 自动                                                      |
| `LOCAL_MODEL_N_BATCH`                       | `2048`                                          | 每批最大 token；调大可加速 prefill（见下方提速）                             |
| `LOCAL_MODEL_N_UBATCH`                      | `1024`                                          | 内部计算批，需 ≤ `N_BATCH`                                         |
| `LOCAL_MODEL_FLASH_ATTN`                    | `false`                                         | 需构建带 `GGML_FLASH_ATTN`，否则加载失败                               |
| `LOCAL_MODEL_USE_DRAFT`                     | `false`                                         | 投机解码（prompt-lookup），默认关；开启自动补 logits_all                    |
| `LOCAL_MODEL_DRAFT_TOKENS`                  | `32`                                            | 投机草稿长度（配合 `USE_DRAFT=true`）                                 |
| `LOCAL_MODEL_LOGITS_ALL`                    | `false`                                         | 是否全 token 算 logits；投机解码开启时自动为 true                          |
| `LLAMA_BUILD`                               | `wheel`                                         | `wheel`=预编译 GPU wheel；`source`=CUDA 源码编译                    |
| `LLAMA_WHL_CUDA`                            | `cu124`                                         | GPU wheel 的 CUDA 标签（cu121~cu126）                            |
| `LLAMA_CMAKE_ARGS`                          | `-DGGML_CUDA=on`                                | source 模式 CMake 参数（A100 可加 `-DCMAKE_CUDA_ARCHITECTURES=80`） |
| `FORCE_REINSTALL`                           | `false`                                         | `true` 时强制重装依赖与 llama-cpp（切换安装模式时用）                         |
| `ENABLE_RETRIEVAL_FALLBACK`                 | `true`                                          | 无建议时检索文件重试                                                  |
| `LOG_MODEL_PROMPT` / `LOG_MODEL_RAW_OUTPUT` | `false`                                         | 诊断日志，默认关                                                    |
| `MODEL_LOG_MAX_CHARS`                       | `0`                                             | 模型日志截断长度，`0`=不限                                             |

> 其余 llama.cpp 细节参数（threads_batch、mmap、mlock、kqv、mul_mat_q 等）已内部固定为
> 稳妥的 GPU 默认值，不再暴露。

## 提速调优（可选）

next-edit 场景延迟主要来自 **prompt 预填充（prefill）**，其次短词生成。可按需尝试：

```bash
# 方案 A：加大批次，减少 prefill 内部切块（A100 80G 无压力）
LOCAL_MODEL_N_BATCH=4096 LOCAL_MODEL_N_UBATCH=2048 ./start_use_uv.sh restart

# 方案 B：开启 flash attention（前提：当前构建带 GGML_FLASH_ATTN；不带会加载失败）
LOCAL_MODEL_FLASH_ATTN=true ./start_use_uv.sh restart

# A+B 组合
LOCAL_MODEL_N_BATCH=4096 LOCAL_MODEL_N_UBATCH=2048 \
LOCAL_MODEL_FLASH_ATTN=true ./start_use_uv.sh restart
```

对比方法：用接口返回里的 `elapsed_time_ms` 字段（或压测脚本）在改动前后各测多次取中位数。

- 源码编译版建议直接用 `LLAMA_CMAKE_ARGS="-DGGML_CUDA=on -DCMAKE_CUDA_ARCHITECTURES=80 -DGGML_CUDA_FA_ALL_QUANTS=ON"`
  （默认已含 A100=80；`FA_ALL_QUANTS` 让 flash_attn 在 Q8 上也生效）。
- 若开启 `LOCAL_MODEL_FLASH_ATTN=true` 后模型 **加载报错**，说明构建不含 flash attn，改回 `false` 即可。

## 说明

- **启动即预热**：应用启动后台线程加载模型，`/health` 立即返回、首个补全不慢。
- **纯 GPU 强制**：安装只用 GPU 预编译 wheel；启动前校验 GPU 卸载；加载后校验实际
  GPU 层数 > 0；任一不满足即报错退出，**无 CPU 自动回退**。
- **请求合并**：同时到达的多个请求只让最新的跑推理，旧的直接取消。
- **workers 保持 1**：模型是进程级单例，多 worker 会重复加载模型。
- 插件侧通过 `uvx sweep-autocomplete` 启动时，命令入口由 `pyproject.toml` 的
  `[project.scripts]` 提供；本地部署需先把本项目发布到可用索引，或直接在服务器上
  用 `./start_use_uv.sh start` 启动。
