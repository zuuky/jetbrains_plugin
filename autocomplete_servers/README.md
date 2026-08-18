# sweep-autocomplete 服务

后台运行 llama.cpp 跑 **sweep-next-edit** 模型的补全服务。插件通过
`POST /backend/next_edit_autocomplete`（SSE/NDJSON 流式）获取 next-edit 建议，
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
    ├── config.py             # 全部配置的唯一来源（环境变量可覆盖）
    └── autocomplete/         # 补全核心逻辑、本地 llama.cpp 推理
```

## 快速开始

**Linux（GPU 服务器，推荐）：**

```bash
export MODEL_PATH=/你的路径/sweep-next-edit-1.5b.q8_0.v2.gguf   # 默认指向 /root/... 可省略
./start_use_uv.sh start        # 首次运行自动装依赖；默认 0.0.0.0:8006
./start_use_uv.sh status
./start_use_uv.sh log
./start_use_uv.sh stop
```

**Windows（本地 CPU 调试）：**

```powershell
$env:MODEL_PATH = 'D:\models\sweep-next-edit-1.5b.q8_0.v2.gguf'
.\start_use_uv.ps1 start
```

**手动前台运行：**

```bash
uv sync --extra llama   # 或按脚本逻辑安装 llama-cpp-python
uv run python -m sweep_autocomplete.cli --host 0.0.0.0 --port 8006
```

## 配置（环境变量，默认即为最优速度）

| 变量                                             | 默认                                              | 说明                                    |
|------------------------------------------------|-------------------------------------------------|---------------------------------------|
| `MODEL_PATH`                                   | `/root/.cache/modelscope/.../1.5b.q8_0.v2.gguf` | 本地 GGUF 路径                            |
| `NEXT_EDIT_AUTOCOMPLETE_ENDPOINT`              | 空                                               | 远程 OpenAI 兼容端点；留空用本地模型                |
| `LOCAL_MODEL_N_CTX`                            | `16384`                                         | 上下文，需容纳 ~9.5k token 的最大提示词            |
| `LOCAL_MODEL_N_BATCH` / `LOCAL_MODEL_N_UBATCH` | `4096` / `2048`                                 | 越大 prefill 越快（吃显存）                    |
| `LOCAL_MODEL_N_GPU_LAYERS`                     | `-1`（Windows 脚本默认 `0`）                          | `-1` 全部进 GPU；CPU 构建必须 `0`             |
| `LOCAL_MODEL_N_THREADS`                        | `0`                                             | `0` = 自动                              |
| `LOCAL_MODEL_FLASH_ATTN`                       | `true`                                          | 需 `GGML_FLASH_ATTN` 的构建               |
| `LOCAL_MODEL_USE_DRAFT`                        | `false`                                         | 投机解码；GPU 短补全默认关                       |
| `LOCAL_MODEL_USE_MLOCK`                        | `false`                                         | 锁内存防交换                                |
| `LOG_MODEL_PROMPT` / `LOG_MODEL_RAW_OUTPUT`    | `false`                                         | 诊断日志，默认关（避免拖慢与刷屏）                     |
| `MODEL_LOG_MAX_CHARS`                          | `0`                                             | 模型日志截断长度，`0`=不限                       |
| `ENABLE_RETRIEVAL_FALLBACK`                    | `true`                                          | 无建议时检索文件重试                            |
| `LLAMA_CUDA_BUILD`                             | `false`                                         | `true` 时从源码编译 CUDA 版 llama-cpp-python |

## 说明

- **启动即预热**：应用启动后台线程加载模型，`/health` 立即返回、首个补全不慢。
- **GPU 自动回退**：`N_GPU_LAYERS=-1` 但当前构建无 GPU 支持时自动降级 CPU 并告警。
- **请求合并**：同时到达的多个请求只让最新的跑推理，旧的直接取消（返回快）。
- **workers 保持 1**：模型是进程级单例，多 worker 会重复加载模型。
- 插件侧通过 `uvx sweep-autocomplete` 启动时，命令入口由 `pyproject.toml` 的
  `[project.scripts]` 提供；本地部署需先把本项目发布到可用索引，或直接在服务器上
  用 `./start_use_uv.sh start` 启动。
