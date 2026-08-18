#Requires -Version 5.1
<#
.SYNOPSIS
  Sweep next-edit autocomplete 服务启动脚本 (Windows / PowerShell)

  用法:
    .\start_use_uv.ps1 start   [host] [port]   # 后台启动（默认 0.0.0.0:8006）
    .\start_use_uv.ps1 stop                    # 停止
    .\start_use_uv.ps1 restart [host] [port]
    .\start_use_uv.ps1 status                  # 状态与关键参数
    .\start_use_uv.ps1 logs                    # 实时日志

  依赖策略:
    - 全部用 uv 安装；
    - llama-cpp 默认装官方预编译 GPU wheel（LLAMA_WHL_CUDA 对应 CUDA 版本）；
    - 网络差/下载慢时用源码编译：$env:LLAMA_BUILD='source'; .\start_use_uv.ps1 start
    - 无 CPU 回退（纯 GPU）；切换安装模式后请用 FORCE_REINSTALL=true 重装。
    - 使用本地模型时必须设置 MODEL_PATH（Windows 上无固定默认路径），
      或设置 NEXT_EDIT_AUTOCOMPLETE_ENDPOINT 走远程端点。
#>
[CmdletBinding()]
param(
    [ValidateSet('start', 'stop', 'restart', 'status', 'logs')]
    [string]$Command = 'start',
    [string]$HostAddr = '0.0.0.0',
    [int]$Port = 8006
)

$ErrorActionPreference = 'Stop'
$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$VenvDir = Join-Path $ScriptDir '.venv'
$PythonExe = Join-Path $VenvDir 'Scripts\python.exe'
$LogDir = Join-Path $ScriptDir 'logs'
$StdoutLog = Join-Path $LogDir 'server.log'
$StderrLog = Join-Path $LogDir 'server.err.log'
$PidFile = Join-Path $LogDir 'server.pid'
$MaxLogSize = 10MB

# ---------------------------------------------------------------------------
# ENV（少数常用项，均可覆盖）
# ---------------------------------------------------------------------------
if (-not $env:MODEL_PATH)
{
    $env:MODEL_PATH = ''
}  # Windows 下必须显式指定
if (-not $env:NEXT_EDIT_AUTOCOMPLETE_ENDPOINT)
{
    $env:NEXT_EDIT_AUTOCOMPLETE_ENDPOINT = ''
}
if (-not $env:LOCAL_MODEL_N_CTX)
{
    $env:LOCAL_MODEL_N_CTX = '16384'
}
if (-not $env:LOCAL_MODEL_N_GPU_LAYERS)
{
    $env:LOCAL_MODEL_N_GPU_LAYERS = '-1'
} # -1=全部层进 GPU
if (-not $env:LOCAL_MODEL_N_THREADS)
{
    $env:LOCAL_MODEL_N_THREADS = '8'
}
# 提速可调项（默认稳妥）
if (-not $env:LOCAL_MODEL_N_BATCH)
{
    $env:LOCAL_MODEL_N_BATCH = '2048'
} # 调大可加速 prefill
if (-not $env:LOCAL_MODEL_N_UBATCH)
{
    $env:LOCAL_MODEL_N_UBATCH = '1024'
} # 需 <= N_BATCH
if (-not $env:LOCAL_MODEL_FLASH_ATTN)
{
    $env:LOCAL_MODEL_FLASH_ATTN = 'false'
} # 需构建带 GGML_FLASH_ATTN
# 投机解码（默认关，方便对比；开启时自动补 logits_all）
if (-not $env:LOCAL_MODEL_USE_DRAFT)
{
    $env:LOCAL_MODEL_USE_DRAFT = 'false'
}
if (-not $env:LOCAL_MODEL_DRAFT_TOKENS)
{
    $env:LOCAL_MODEL_DRAFT_TOKENS = '32'
}
if (-not $env:LOCAL_MODEL_LOGITS_ALL)
{
    $env:LOCAL_MODEL_LOGITS_ALL = 'false'
}
if (-not $env:ENABLE_RETRIEVAL_FALLBACK)
{
    $env:ENABLE_RETRIEVAL_FALLBACK = 'true'
}
if (-not $env:LOG_MODEL_PROMPT)
{
    $env:LOG_MODEL_PROMPT = 'false'
}
if (-not $env:LOG_MODEL_RAW_OUTPUT)
{
    $env:LOG_MODEL_RAW_OUTPUT = 'false'
}
if (-not $env:MODEL_LOG_MAX_CHARS)
{
    $env:MODEL_LOG_MAX_CHARS = '0'
}

# 安装相关
# LLAMA_BUILD: wheel = 官方预编译 GPU wheel（默认） | source = CUDA 源码编译（网络差时用）
if (-not $env:LLAMA_BUILD)
{
    $env:LLAMA_BUILD = 'wheel'
}
if (-not $env:LLAMA_WHL_CUDA)
{
    $env:LLAMA_WHL_CUDA = 'cu124'
} # GPU wheel 标签（cu121~cu126）
if (-not $env:LLAMA_CMAKE_ARGS)
{
    $env:LLAMA_CMAKE_ARGS = '-DGGML_CUDA=on -DCMAKE_CUDA_ARCHITECTURES=80'
} # source 模式 CMake 参数（A100=80）
if (-not $env:FORCE_REINSTALL)
{
    $env:FORCE_REINSTALL = 'false'
} # true=强制重装（切换模式时用）

function Test-True([string]$v)
{
    return $v -match '^(1|true|yes|on)$'
}

function Assert-Config
{
    if (-not $env:MODEL_PATH -and -not $env:NEXT_EDIT_AUTOCOMPLETE_ENDPOINT)
    {
        Write-Error @'
未配置模型：请任选其一
  1) 设置环境变量 MODEL_PATH 指向本地 GGUF 文件
  2) 设置环境变量 NEXT_EDIT_AUTOCOMPLETE_ENDPOINT 使用远程端点
例如: $env:MODEL_PATH = 'D:\models\sweep-next-edit-1.5b.q8_0.v2.gguf'
'@
        exit 1
    }
}

function Test-LlamaInstalled
{
    if (-not (Test-Path $PythonExe))
    {
        return $false
    }
    & $PythonExe -c 'import llama_cpp' 2> $null | Out-Null
    return ($LASTEXITCODE -eq 0)
}

function Test-GpuSupported
{
    # llama-cpp 是否支持 GPU 卸载（纯 GPU 策略）
    if (-not (Test-LlamaInstalled))
    {
        return $false
    }
    & $PythonExe -c @'
from llama_cpp import llama_cpp as l
fn = getattr(l, 'llama_supports_gpu_offload', None)
import sys; sys.exit(0 if (callable(fn) and fn()) else 1)
'@ 2> $null | Out-Null
    return ($LASTEXITCODE -eq 0)
}

# ---------------------------------------------------------------------------
# 环境准备：全部用 uv；llama-cpp 默认装 GPU 预编译 wheel（LLAMA_BUILD=source 则源码编译）
# ---------------------------------------------------------------------------
function Install-LlamaSource
{
    # CUDA 源码编译（LLAMA_BUILD=source 时使用）
    Write-Host '[deps] CUDA 源码编译 llama-cpp-python（LLAMA_BUILD=source）...'
    if (-not (Get-Command nvcc -ErrorAction SilentlyContinue))
    {
        Write-Error '未找到 nvcc，请先安装 CUDA Toolkit（源码编译必需）'
        exit 1
    }
    Write-Host '[deps] 安装编译工具 cmake/ninja ...'
    & uv pip install --python $PythonExe cmake ninja
    Write-Host "[deps] 开始编译（CMAKE_ARGS=$env:LLAMA_CMAKE_ARGS），耗时较长请耐心等待..."
    $env:CMAKE_ARGS = $env:LLAMA_CMAKE_ARGS
    $env:FORCE_CMAKE = '1'
    & uv pip install --python $PythonExe --no-cache-dir --no-binary :all: `
        --reinstall-package llama-cpp-python llama-cpp-python
    & uv pip install --python $PythonExe numpy diskcache jinja2 typing-extensions
}

function Ensure-Env
{
    if (-not (Get-Command uv -ErrorAction SilentlyContinue))
    {
        Write-Error '未找到 uv 命令，请先安装: https://docs.astral.sh/uv/getting-started/installation/'
        exit 1
    }
    if (-not (Test-Path $PythonExe))
    {
        & uv venv --seed $VenvDir
    }
    & $PythonExe -c 'import fastapi,uvicorn' 2> $null | Out-Null
    if ($LASTEXITCODE -ne 0 -or (Test-True $env:FORCE_REINSTALL))
    {
        Write-Host '[deps] 用 uv 安装项目依赖 (pyproject.toml)...'
        & uv pip install --python $PythonExe -e $ScriptDir
    }
    if (-not (Test-LlamaInstalled) -or -not (Test-GpuSupported) -or (Test-True $env:FORCE_REINSTALL))
    {
        if ($env:LLAMA_BUILD -eq 'source')
        {
            Install-LlamaSource
        }
        else
        {
            Write-Host "[deps] 用 uv 安装 GPU 版 llama-cpp-python（$env:LLAMA_WHL_CUDA 预编译 wheel）..."
            & uv pip install --python $PythonExe `
                --index-url "https://abetlen.github.io/llama-cpp-python/whl/$env:LLAMA_WHL_CUDA" `
                --no-deps --reinstall-package llama-cpp-python llama-cpp-python
            # llama-cpp 的运行时依赖（numpy 已由项目依赖安装，其余补上）
            & uv pip install --python $PythonExe numpy diskcache jinja2 typing-extensions
        }
        if (-not (Test-GpuSupported))
        {
            Write-Error @'
安装/编译后仍不支持 GPU 卸载。
  wheel  模式请确认 LLAMA_WHL_CUDA 与本机 CUDA 版本匹配；
  source 模式请检查上方 CMake 日志与 LLAMA_CMAKE_ARGS。
'@
            exit 1
        }
    }
}

function Verify-Gpu
{
    # 纯 GPU 策略：必须支持 GPU 卸载，否则拒绝启动。
    if (-not (Test-GpuSupported))
    {
        Write-Error @'
llama-cpp 不支持 GPU 卸载（纯 GPU 模式禁止 CPU 回退）。
请用 GPU 预编译 wheel 重装：
  $env:FORCE_REINSTALL = 'true'; $env:LLAMA_WHL_CUDA = 'cu124'; .\start_use_uv.ps1 restart
'@
        exit 1
    }
    Write-Host '[gpu] llama-cpp GPU 卸载: 支持'
}

function Get-RunningPid
{
    if (-not (Test-Path $PidFile))
    {
        return $null
    }
    $pid2 = Get-Content $PidFile -ErrorAction SilentlyContinue
    if ($pid2 -and (Get-Process -Id $pid2 -ErrorAction SilentlyContinue))
    {
        return $pid2
    }
    return $null
}

function Start-Server
{
    Assert-Config
    $running = Get-RunningPid
    if ($running)
    {
        Write-Host "服务已在运行 PID=$running"; return
    }

    Ensure-Env
    Verify-Gpu
    New-Item -ItemType Directory -Force -Path $LogDir | Out-Null
    if ((Test-Path $StdoutLog) -and ((Get-Item $StdoutLog).Length -ge $MaxLogSize))
    {
        Move-Item $StdoutLog "$StdoutLog.$( Get-Date -Format yyyyMMdd_HHmmss )" -Force
        if (Test-Path $StderrLog)
        {
            Move-Item $StderrLog "$StderrLog.$( Get-Date -Format yyyyMMdd_HHmmss )" -Force
        }
    }

    $args = @('-m', 'sweep_autocomplete.cli', '--host', $HostAddr, '--port', "$Port")
    $proc = Start-Process -FilePath $PythonExe -ArgumentList $args -WorkingDirectory $ScriptDir `
        -WindowStyle Hidden -PassThru `
        -RedirectStandardOutput $StdoutLog -RedirectStandardError $StderrLog
    $proc.Id | Set-Content $PidFile
    Write-Host "已启动 http://$HostAddr`:$Port (PID=$( $proc.Id ))，日志: $StdoutLog"
}

function Stop-Server
{
    $running = Get-RunningPid
    if ($running)
    {
        Stop-Process -Id $running -Force -ErrorAction SilentlyContinue
        Write-Host "已停止 PID=$running"
    }
    else
    {
        Write-Host '服务未在运行'
    }
    Remove-Item $PidFile -ErrorAction SilentlyContinue
}

function Show-Status
{
    $running = Get-RunningPid
    if ($running)
    {
        Write-Host "状态: 运行中  PID=$running"
    }
    else
    {
        Write-Host '状态: 已停止'
    }
    Write-Host "日志: $StdoutLog"
    $llamaText = if (-not (Test-LlamaInstalled))
    {
        '未安装（重启时自动装 GPU wheel）'
    }
    elseif (Test-GpuSupported)
    {
        '已安装, GPU 卸载支持'
    }
    else
    {
        '已安装但非 GPU 版（重启时自动换装）'
    }
    Write-Host "llama-cpp: $llamaText"
    Write-Host "安装模式: LLAMA_BUILD=$env:LLAMA_BUILD (wheel:$env:LLAMA_WHL_CUDA / cmake:$env:LLAMA_CMAKE_ARGS)"
    Write-Host "关键参数: MODEL_PATH=$env:MODEL_PATH"
    Write-Host "           N_CTX=$env:LOCAL_MODEL_N_CTX N_GPU_LAYERS=$env:LOCAL_MODEL_N_GPU_LAYERS N_THREADS=$env:LOCAL_MODEL_N_THREADS"
}

switch ($Command)
{
    'start'   {
        Start-Server
    }
    'stop'    {
        Stop-Server
    }
    'restart' {
        Stop-Server; Start-Server
    }
    'status'  {
        Show-Status
    }
    'logs'    {
        Get-Content -Path $StdoutLog, $StderrLog -Wait -Tail 30 -ErrorAction SilentlyContinue
    }
    default   {
        throw "未知命令: $Command"
    }
}
