<#
    WristDeck Windows 网关 —— 停止

      powershell -ExecutionPolicy Bypass -File gateway-win\stop.ps1

    停掉托盘进程与其拉起的 Bridge 子进程。
    正常情况请用**托盘菜单的「退出」**（会顺带优雅关闭 Bridge）；本脚本是兜底，
    用于托盘卡住 / 从任务管理器里直接杀掉之后收拾残局。

    实现说明：不用 `Get-CimInstance -Filter "Name LIKE 'python%'"` 那种写法 ——
    实测在某些环境下该过滤器会返回空集，脚本就"静默地什么都没做"。
    这里改成先 `Get-Process` 拿到候选，再**逐个 PID** 查命令行，拿不到命令行时
    明确打印出来而不是假装没事。
#>
#Requires -Version 5.1
[CmdletBinding()]
param()

$ErrorActionPreference = 'Continue'
$killed = 0
$denied = 0

function Get-CmdLine([int]$id) {
    try {
        $ci = Get-CimInstance Win32_Process -Filter ("ProcessId = " + $id) -ErrorAction Stop
        return [string]$ci.CommandLine
    } catch {
        return ''
    }
}

function Test-Target([string]$cmdline) {
    if ([string]::IsNullOrWhiteSpace($cmdline)) { return $false }
    if ($cmdline -match 'tray\.py') { return $true }
    if ($cmdline -match 'win_gateway\.py') { return $true }
    if ($cmdline -match 'bridge[\\/]server\.js') { return $true }
    return $false
}

$cands = Get-Process -ErrorAction SilentlyContinue |
    Where-Object { $_.ProcessName -like 'python*' -or $_.ProcessName -eq 'node' }

Write-Host ("扫描到 {0} 个 python/node 进程" -f @($cands).Count)

foreach ($p in $cands) {
    $cl = Get-CmdLine $p.Id
    if (-not (Test-Target $cl)) { continue }
    Write-Host ("停止 {0} (PID {1})" -f $p.ProcessName, $p.Id)
    try {
        Stop-Process -Id $p.Id -Force -ErrorAction Stop
        $killed++
    } catch {
        Write-Host ("  [x] 停止失败：{0}" -f $_.Exception.Message) -ForegroundColor Red
        $denied++
    }
}

if ($killed -eq 0 -and $denied -eq 0) {
    Write-Host "没有发现正在运行的 WristDeck 进程。"
} else {
    Write-Host ("已停止 {0} 个进程。" -f $killed)
    if ($denied -gt 0) { Write-Host ("有 {0} 个停不掉，请用任务管理器结束。" -f $denied) -ForegroundColor Yellow }
}
