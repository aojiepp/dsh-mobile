# cdp_eval.ps1 — 带重试的 CDP eval 包装器（vivo 上 devtools socket 每次用后会卡死）
# 用法: pwsh -File tools/cdp_eval.ps1 "<js expression>"
# 每次执行前重设 forward，失败自动重试（最多 8 次）。
param([string]$Expr = 'document.title')
$ErrorActionPreference = 'Continue'
$root = Split-Path -Parent $PSScriptRoot
for ($i = 1; $i -le 8; $i++) {
    $appPid = (adb shell pidof com.dsh.mobile | Select-Object -First 1).Trim()
    if (-not $appPid) { Start-Sleep -Seconds 1; continue }
    adb forward --remove tcp:9222 2>$null | Out-Null
    adb forward tcp:9222 "localabstract:webview_devtools_remote_$appPid" | Out-Null
    & node "$root\tools\cdp_eval.mjs" $Expr
    if ($LASTEXITCODE -eq 0) { exit 0 }
    Start-Sleep -Milliseconds 700
}
Write-Error "CDP eval failed after retries"
exit 1
