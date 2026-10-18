# verify_release.ps1 — dsh-mobile v2.11 真机验收脚本（vivo V2339A）
# 用法：手机开 USB 调试连上后，在 dsh-mobile 根目录跑：
#   pwsh -NoProfile -File tools\verify_release.ps1 -Phase all [-Apk build\apk\dsh-mobile-v2.11-release.apk]
# 阶段：install（装包+vivo两连点+versionCode）/ security（P0/P1）/ logs（debug 包日志卫生）/ perf（CPU 采样）
# 约定：release 包验 security（run-as 应被拒）；debug 包验 logs（run-as 可读私有目录）。
[CmdletBinding()]
param(
    [string]$Phase = "all",
    [string]$Apk = "build\apk\dsh-mobile-v2.11-release.apk",
    [string]$DebugApk = "build\apk\dsh-mobile-v2.11-debug.apk"
)
$ErrorActionPreference = "Continue"
$ADB = "C:\Android\platform-tools\adb.exe"
$AAPT2 = "C:\Android\build-tools\34.0.0\aapt2.exe"
$PKG = "com.dsh.mobile"
$script:Pass = 0; $script:Fail = 0

function Gate([string]$name, [bool]$ok, [string]$detail = "") {
    if ($ok) { $script:Pass++; Write-Host "PASS  $name $detail" -ForegroundColor Green }
    else { $script:Fail++; Write-Host "FAIL  $name $detail" -ForegroundColor Red }
}

function Tap-ById([string]$id) {
    & $ADB shell uiautomator dump /sdcard/ui.xml | Out-Null
    & $ADB pull /sdcard/ui.xml "$env:TEMP\dshm-ui.xml" | Out-Null
    $xml = Get-Content "$env:TEMP\dshm-ui.xml" -Raw
    $m = [regex]::Match($xml, 'resource-id="' + [regex]::Escape($id) + '"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"')
    if ($m.Success) {
        $x = ([int]$m.Groups[1].Value + [int]$m.Groups[3].Value) / 2
        $y = ([int]$m.Groups[2].Value + [int]$m.Groups[4].Value) / 2
        & $ADB shell input tap ([int]$x) ([int]$y) | Out-Null
        return $true
    }
    return $false
}

function Phase-Install {
    Write-Host "== install: $Apk =="
    # adb install 会阻塞到用户应答/超时，所以放后台跑；vivo 安全守护必须在
    # 安装阻塞期间点选（勾选「已了解风险」→ 继续安装），装完再点就晚了。
    $apkFull = (Resolve-Path $Apk).Path
    $job = Start-Job { param($a, $p) & $a install -r $p 2>&1 } -ArgumentList $ADB, $apkFull
    $deadline = (Get-Date).AddSeconds(100)
    while ((Get-Date) -lt $deadline -and $job.State -eq "Running") {
        if (Tap-ById "com.android.packageinstaller:id/deleted_file_state_cb") { Start-Sleep -Milliseconds 700 }
        Tap-ById "android:id/button1" | Out-Null
        Start-Sleep -Milliseconds 900
    }
    $out = Receive-Job $job -Wait 2>&1 | Out-String
    Remove-Job $job -Force
    Write-Host $out.Trim()
    Gate "adb install 成功" ($out -match "Success") ($out.Trim())
    $vcLine = (& $ADB shell dumpsys package $PKG | Select-String "versionCode" | Select-Object -First 1).ToString()
    Gate "versionCode=29" ($vcLine -match "versionCode=29") $vcLine.Trim()
}

function Phase-Security {
    Write-Host "== security（对 $Apk 静态 + 设备运行时）=="
    # 静态 manifest：无 debuggable、allowBackup=false、有 dataExtractionRules
    $tree = & $AAPT2 dump xmltree --file AndroidManifest.xml $Apk 2>$null | Out-String
    Gate "manifest 无 debuggable" (-not ($tree -match "debuggable")) ""
    Gate "allowBackup=false" ($tree -match "allowBackup.*0x0") ""
    Gate "dataExtractionRules 存在" ($tree -match "dataExtractionRules") ""
    # 运行时：App 启动状态下 /proc/net/unix 无 webview_devtools_remote
    & $ADB shell am start -n "$PKG/.MainActivity" | Out-Null
    Start-Sleep 12   # 等 WebView 加载
    $unix = & $ADB shell cat /proc/net/unix | Out-String
    Gate "无 webview_devtools_remote socket" (-not ($unix -match "webview_devtools_remote")) ""
    $ra = & $ADB shell run-as $PKG id 2>&1 | Out-String
    Gate "run-as 被拒（release 不可调试）" ($ra -match "not debuggable|unknown|Permission denied|run-as") ($ra.Trim())
    # allowBackup 运行时侧写：bmgr 对该包应不可用（仅打印参考）
    & $ADB shell bmgr list transports | Out-Null
}

function Phase-Logs {
    Write-Host "== logs（需先装 debug 包：adb install -r $DebugApk）=="
    $vcLine = (& $ADB shell dumpsys package $PKG | Select-String "versionCode" | Select-Object -First 1).ToString()
    & $ADB shell am start -n "$PKG/.MainActivity" | Out-Null
    Start-Sleep 15   # 等服务起来、watcher 写 prefs、日志落盘
    $log = & $ADB shell run-as $PKG cat files/dsh-server.log 2>&1 | Out-String
    Gate "dsh-server.log 无 token 明文" (-not ($log -match "[?&](token|api_key|key)=[^&\s*]")) ""
    $ls = & $ADB shell run-as $PKG ls -l files/dsh-server.log 2>&1 | Out-String
    Gate "日志权限 -rw-------" ($ls -match "^-rw-------") $ls.Trim()
    $manual = & $ADB shell run-as $PKG ls files/manual.log 2>&1 | Out-String
    Gate "manual.log 已清理" ($manual -match "No such file") ""
    $prefs = & $ADB shell run-as $PKG cat shared_prefs/dsh_mobile.xml 2>&1 | Out-String
    Gate "launch_url 已写 prefs" ($prefs -match "launch_url") ""
}

function Phase-Perf {
    Write-Host "== perf：先在手机上开一轮活跃对话，脚本采样 30 次 top =="
    $samples = & $ADB shell top -d 1 -n 30 | Select-String $PKG
    $cpu = @()
    foreach ($s in $samples) {
        if ($s.ToString() -match "(\d+(\.\d+)?)%") { $cpu += [double]$Matches[1] }
    }
    if ($cpu.Count -gt 0) {
        $avg = ($cpu | Measure-Object -Average).Average
        $max = ($cpu | Measure-Object -Maximum).Maximum
        Write-Host ("采样 {0} 次：avg {1:N1}%  max {2:N1}%（旧基线 113%，目标 <45%）" -f $cpu.Count, $avg, $max)
        Gate "active turn CPU avg <45%" ($avg -lt 45) ("avg={0:N1}%" -f $avg)
    } else {
        Gate "CPU 采样" $false "未采到 $PKG 进程（先在手机上发起一轮对话再跑）"
    }
}

# ---- 主流程 ----
$dev = & $ADB devices | Select-String "device$"
if (-not $dev) { Write-Host "没有已连接的 adb 设备，先把手机插上并授权 USB 调试。" -ForegroundColor Yellow; exit 2 }
Write-Host "设备已连接。Phase=$Phase"
if ($Phase -in @("all", "install")) { Phase-Install }
if ($Phase -in @("all", "security")) { Phase-Security }
if ($Phase -in @("all", "logs")) { Phase-Logs }
if ($Phase -in @("all", "perf")) { Phase-Perf }
Write-Host ""
Write-Host ("验收汇总：PASS={0} FAIL={1}" -f $script:Pass, $script:Fail)
if ($script:Fail -gt 0) { exit 1 } else { exit 0 }
