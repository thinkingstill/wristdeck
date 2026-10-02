<#
    WristDeck Windows 网关 —— 启动（无控制台）

      powershell -ExecutionPolicy Bypass -File gateway-win\start.ps1

    用 pythonw.exe 启动托盘，所以**不会**有黑色控制台窗口。
    想看实时日志就加 -Console，或用 gateway-win\debug.ps1。
#>
#Requires -Version 5.1
[CmdletBinding()]
param(
    [switch]$Console,
    [switch]$NoBridge,
    [switch]$Foreground
)

$ErrorActionPreference = 'Stop'
$Here = Split-Path -Parent $MyInvocation.MyCommand.Path
$Repo = Split-Path -Parent $Here

$VenvPy = Join-Path $Repo '.venv\Scripts'
$pythonw = Join-Path $VenvPy 'pythonw.exe'
$python  = Join-Path $VenvPy 'python.exe'
if (-not (Test-Path $python)) {   # 没建 venv 就退回 PATH
    $python  = 'python.exe'
    $pythonw = 'pythonw.exe'
}

$trayArgs = @((Join-Path $Here 'tray.py'))
if ($NoBridge) { $trayArgs += '--no-bridge' }
$argLine = ($trayArgs | ForEach-Object { '"{0}"' -f $_ }) -join ' '

if ($Console -or $Foreground) {
    # 前台带控制台：日志直接滚在眼前，Ctrl-C 退出
    & $python @trayArgs
} else {
    Start-Process -FilePath $pythonw -ArgumentList $argLine -WorkingDirectory $Here
    Write-Host "已在托盘中启动（无控制台）。日志：%USERPROFILE%\.wristbridge\logs\gateway.log"
}
