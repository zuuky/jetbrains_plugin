#Requires -Version 5.1
<#
.SYNOPSIS
  Sweep next-edit autocomplete 服务启动脚本 (Windows / PowerShell)

  用法:
    .\start_use_uv.ps1 start   [host] [port]   # 后台启动（默认 0.0.0.0:8006）
    .\start_use_uv.ps1 stop                    # 停止
    .\start_use_uv.ps1 restart [host] [port]
    .\start_use_uv.ps1 status                  # 状态与关键参数
    .\start_use_uv.ps1 log                     # 实时日志

  说明:
    - 首次运行自动用 uv 创建 .venv、安装 pyproject.toml 依赖和 llama-cpp-python（CPU 版）。
    - 所有配置通过环境变量覆盖；Windows 下默认使用 CPU 构建（LOCAL_MODEL_N_GPU_LAYERS=0）。
    - 使用本地模型时必须设置 MODEL_PATH（Windows 上无固定默认路径），
      或设置 NEXT_EDIT_AUTOCOMPLETE_ENDPOINT 走远程端点。
#>
[CmdletBinding()]
param(
    [ValidateSet('start', 'stop', 'restart', 'status', 'log')]
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
# ENV - 默认值即最优速度配置，用环境变量覆盖
# ---------------------------------------------------------------------------
if (-not $env:MODEL_PATH)
{
    $env:MODEL_PATH = ''
}   # Windows 下必须显式指定
if (-not $env:NEXT_EDIT_AUTOCOMPLETE_ENDPOINT)
{
    $env:NEXT_EDIT_AUTOCOMPLETE_ENDPOINT = ''
}
if (-not $env:LOCAL_MODEL_N_CTX)
{
    $env:LOCAL_MODEL_N_CTX = '16384'
}
if (-not $env:LOCAL_MODEL_N_BATCH)
{
    $env:LOCAL_MODEL_N_BATCH = '4096'
}
if (-not $env:LOCAL_MODEL_N_UBATCH)
{
    $env:LOCAL_MODEL_N_UBATCH = '2048'
}
if (-not $env:LOCAL_MODEL_N_GPU_LAYERS)
{
    $env:LOCAL_MODEL_N_GPU_LAYERS = '0'
} # Windows 默认 CPU
if (-not $env:LOCAL_MODEL_N_THREADS)
{
    $env:LOCAL_MODEL_N_THREADS = '0'
}
if (-not $env:LOCAL_MODEL_N_THREADS_BATCH)
{
    $env:LOCAL_MODEL_N_THREADS_BATCH = '0'
}
if (-not $env:LOCAL_MODEL_FLASH_ATTN)
{
    $env:LOCAL_MODEL_FLASH_ATTN = 'true'
}
if (-not $env:LOCAL_MODEL_OFFLOAD_KQV)
{
    $env:LOCAL_MODEL_OFFLOAD_KQV = 'true'
}
if (-not $env:LOCAL_MODEL_MUL_MAT_Q)
{
    $env:LOCAL_MODEL_MUL_MAT_Q = 'true'
}
if (-not $env:LOCAL_MODEL_USE_MMAP)
{
    $env:LOCAL_MODEL_USE_MMAP = 'true'
}
if (-not $env:LOCAL_MODEL_USE_MLOCK)
{
    $env:LOCAL_MODEL_USE_MLOCK = 'false'
}
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
if (-not $env:LOCAL_MODEL_VERBOSE)
{
    $env:LOCAL_MODEL_VERBOSE = 'false'
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
if (-not $env:LLAMA_CUDA_BUILD)
{
    $env:LLAMA_CUDA_BUILD = 'false'
}
if (-not $env:FORCE_REINSTALL)
{
    $env:FORCE_REINSTALL = 'false'
}

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
    $depsOk = & $PythonExe -c 'import fastapi,uvicorn' 2> $null
    if ($LASTEXITCODE -ne 0 -or (Test-True $env:FORCE_REINSTALL))
    {
        Write-Host '[deps] 安装项目依赖 (pyproject.toml)...'
        & uv pip install --python $PythonExe -e $ScriptDir
    }
    & $PythonExe -c 'import llama_cpp' 2> $null | Out-Null
    if ($LASTEXITCODE -ne 0 -or (Test-True $env:FORCE_REINSTALL))
    {
        Write-Host '[deps] 安装 llama-cpp-python ...'
        if (Test-True $env:LLAMA_CUDA_BUILD)
        {
            $env:CMAKE_ARGS = if (-not $env:CMAKE_ARGS)
            {
                '-DGGML_CUDA=on'
            }
            else
            {
                $env:CMAKE_ARGS
            }
            $env:FORCE_CMAKE = '1'
            & $PythonExe -m pip install --no-cache-dir --no-binary :all: llama-cpp-python
        }
        else
        {
            & $PythonExe -m pip install --extra-index-url https://abetlen.github.io/llama-cpp-python/whl/cpu llama-cpp-python
        }
    }
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
    Write-Host "关键参数: MODEL_PATH=$env:MODEL_PATH"
    Write-Host "           N_CTX=$env:LOCAL_MODEL_N_CTX N_BATCH=$env:LOCAL_MODEL_N_BATCH N_GPU_LAYERS=$env:LOCAL_MODEL_N_GPU_LAYERS"
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
    'log'     {
        Get-Content -Path $StdoutLog, $StderrLog -Wait -Tail 30 -ErrorAction SilentlyContinue
    }
    default   {
        throw "未知命令: $Command"
    }
}
