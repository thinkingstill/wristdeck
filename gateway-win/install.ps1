<#
    WristDeck Windows 网关 —— 一键安装

      powershell -ExecutionPolicy Bypass -File gateway-win\install.ps1

    它做四件事：
      1. 在 <仓库>\.venv 建独立虚拟环境（不污染系统 Python）
      2. 装 Python 依赖（bleak / websockets / pystray / pillow）
      3. 在 bridge\ 里 npm install（Bridge 只依赖 ws）
      4. 写入 HKCU 开机自启项（托盘常驻），**全程不需要管理员**

    可选：
      -NoAutostart    不写自启项
      -NoBridgeDeps   跳过 npm install
      -Firewall       额外放行 8787 入站（**只有**手表走 Wi-Fi 直连时才需要；需要管理员）
      -NoVenv         直接装进当前 Python，不建 venv
#>
#Requires -Version 5.1
[CmdletBinding()]
param(
    [string]$PythonExe = "",
    [switch]$NoAutostart,
    [switch]$NoBridgeDeps,
    [switch]$Firewall,
    [switch]$NoVenv
)

$ErrorActionPreference = 'Stop'
$Here = Split-Path -Parent $MyInvocation.MyCommand.Path
$Repo = Split-Path -Parent $Here
$Venv = Join-Path $Repo '.venv'

function Say($msg) { Write-Host $msg }
function Ok($msg)  { Write-Host "  [OK] $msg" -ForegroundColor Green }
function Warn($msg){ Write-Host "  [!]  $msg" -ForegroundColor Yellow }
function Die($msg) { Write-Host "  [x]  $msg" -ForegroundColor Red; exit 1 }

Say ""
Say "=============================================="
Say " WristDeck Windows 网关 · 安装"
Say "=============================================="
Say " 仓库 $Repo"
Say ""

# ---------- 1. 找 Python ----------
if (-not $PythonExe) {
    $cand = @()
    if (Get-Command py -ErrorAction SilentlyContinue) { $cand += 'py' }
    if (Get-Command python -ErrorAction SilentlyContinue) { $cand += 'python' }
    if ($cand.Count -eq 0) {
        Die "找不到 Python。请先安装 Python 3.10+（勾选 Add to PATH），或用 -PythonExe 指定完整路径。"
    }
    $PythonExe = $cand[0]
}
Say "[1/5] Python"
if ($PythonExe -eq 'py') {
    & py -3 -c "import sys; print('  ', sys.version.split()[0], sys.executable)"
    if ($LASTEXITCODE -ne 0) { Die "py -3 不可用" }
    $BaseArgs = @('-3')
} else {
    & $PythonExe -c "import sys; print('  ', sys.version.split()[0], sys.executable)"
    if ($LASTEXITCODE -ne 0) { Die "$PythonExe 不可用" }
    $BaseArgs = @()
}

# ---------- 2. 虚拟环境 ----------
$PyExe = $PythonExe
if (-not $NoVenv) {
    Say "[2/5] 虚拟环境 $Venv"
    if (-not (Test-Path (Join-Path $Venv 'Scripts\python.exe'))) {
        & $PythonExe @BaseArgs -m venv $Venv
        if ($LASTEXITCODE -ne 0) { Die "创建 venv 失败" }
    }
    $PyExe = Join-Path $Venv 'Scripts\python.exe'
    Ok "venv 就绪"
} else {
    Say "[2/5] 跳过 venv（-NoVenv），直接装进系统 Python"
}

# ---------- 3. Python 依赖 ----------
Say "[3/5] 安装 Python 依赖"
& $PyExe -m pip install --upgrade pip --quiet
& $PyExe -m pip install -r (Join-Path $Here 'requirements.txt') --quiet
if ($LASTEXITCODE -ne 0) { Die "pip 安装失败" }
Ok "bleak / websockets / pystray / pillow 已就绪"

# ---------- 4. Bridge 依赖 ----------
Say "[4/5] Bridge 依赖"
$NodeExe = (Get-Command node -ErrorAction SilentlyContinue)
if (-not $NodeExe) {
    Warn "找不到 node。Bridge 跑不起来 —— 请安装 Node.js 18+，然后重跑本脚本。"
    Warn "（BLE 网关本身不依赖 node，可以先用 gateway-win\start.ps1 单独跑起来验证蓝牙链路）"
} elseif ($NoBridgeDeps) {
    Say "  已跳过（-NoBridgeDeps）"
} else {
    Push-Location (Join-Path $Repo 'bridge')
    try {
        & npm install --no-audit --no-fund
        if ($LASTEXITCODE -ne 0) { Warn "npm install 返回 $LASTEXITCODE，请手动到 bridge\ 下执行 npm install" }
        else { Ok "bridge\node_modules 就绪" }
    } finally { Pop-Location }
}

# ---------- 5. 开机自启 ----------
Say "[5/5] 开机自启"
if ($NoAutostart) {
    Say "  已跳过（-NoAutostart）"
} else {
    $RunKey = 'HKCU:\Software\Microsoft\Windows\CurrentVersion\Run'
    $pythonw = Join-Path $Venv 'Scripts\pythonw.exe'
    if (-not (Test-Path $pythonw)) { $pythonw = 'pythonw.exe' }
    $cmd = '"{0}" "{1}"' -f $pythonw, (Join-Path $Here 'tray.py')
    New-ItemProperty -Path $RunKey -Name 'WristDeck' -Value $cmd -PropertyType String -Force | Out-Null
    Ok "已写入 HKCU Run：WristDeck"
    Say "       $cmd"
    Say "       （可在托盘菜单里随时关掉，不想要就选「开机自启」取消勾选）"
}

if ($Firewall) {
    Say ""
    Say "[extra] 放行 8787 入站（管理员权限）"
    try {
        New-NetFirewallRule -DisplayName 'WristDeck Bridge 8787' -Direction Inbound `
            -Action Allow -Protocol TCP -LocalPort 8787 -Profile Private | Out-Null
        Ok "已放行。只有手表走 Wi-Fi 直连时才需要这条；纯 BLE 链路不需要。"
    } catch {
        Warn "放行失败（多半不是管理员）：$($_.Exception.Message)"
    }
}

# ---------- 自检 ----------
Say ""
Say "------------- 自检 -------------"
& $PyExe (Join-Path $Here 'win_gateway.py') --status
Say ""

Say "=============================================="
Say " 装好了。启动：gateway-win\start.ps1"
Say " 或直接双击托盘（无控制台）：$Venv\Scripts\pythonw.exe gateway-win\tray.py"
Say ""
Say " 手表侧别忘了：设置 → 连接方式 → 「用蓝牙连接」"
Say " 状态页 http://127.0.0.1:8787   日志 %USERPROFILE%\.wristbridge\logs\gateway.log"
Say "=============================================="
