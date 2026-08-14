# pull_workspace.ps1 — 把手机端 agent 的工作区产物导出到电脑（可选再拷回手机可见目录）
# 产物在应用私有目录 /data/user/0/com.dsh.mobile/files/workspace/，
# 手机文件管理器看不到，adb + run-as 是唯一的导出通道。
# 用法:
#   pwsh -File tools\pull_workspace.ps1                      # 导出到默认目录
#   pwsh -File tools\pull_workspace.ps1 D:\out               # 指定电脑端目录
#   pwsh -File tools\pull_workspace.ps1 -ToPhone             # 同时拷到手机 /sdcard/Download/dsh-export
param(
    [string]$Dest = (Join-Path $env:USERPROFILE "Desktop\dsh-agent-export"),
    [switch]$ToPhone
)
$ErrorActionPreference = 'Continue'
$pkg = 'com.dsh.mobile'
$ws = 'files/workspace'

$lines = @(adb shell run-as $pkg find $ws -type f 2>$null)
$files = @($lines | Where-Object { $_ -match '^files/workspace/' })
if ($files.Count -eq 0) {
    Write-Host "workspace 为空或无法访问（应用是否在运行？）"
    exit 1
}
Write-Host ("workspace 里有 {0} 个文件" -f $files.Count)
New-Item -ItemType Directory -Force -Path $Dest | Out-Null
foreach ($f in $files) {
    $rel = $f.Substring(($ws + '/').Length)
    $out = Join-Path $Dest $rel
    New-Item -ItemType Directory -Force -Path (Split-Path $out) | Out-Null
    # cmd 重定向保持二进制安全（HTML/图片都不会坏）
    cmd /c ('adb exec-out run-as {0} cat "{1}" > "{2}"' -f $pkg, $f, $out) | Out-Null
    Write-Host ("exported: {0} ({1} bytes)" -f $rel, (Get-Item $out).Length)
}
Write-Host "== 导出完成: $Dest"

if ($ToPhone) {
    $phoneDir = '/sdcard/Download/dsh-export'
    adb shell mkdir -p $phoneDir | Out-Null
    foreach ($f in $files) {
        $rel = $f.Substring(($ws + '/').Length)
        adb push (Join-Path $Dest $rel) ("$phoneDir/" + ($rel -replace '\\', '/')) | Out-Null
    }
    Write-Host "== 同时已拷到手机: $phoneDir（用手机文件管理器打开 Download/dsh-export 即可看到）"
}
