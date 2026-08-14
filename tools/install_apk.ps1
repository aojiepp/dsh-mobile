# install_apk.ps1 — 安装 APK 并自动处理 vivo 等真机的安装确认弹窗
# 用法: powershell -File tools/install_apk.ps1 <apk路径>
#
# 应对链路（vivo OriginOS 实测）：
#   1. 后台跑 adb install -r（USB 系统级安装路径，绕开「未知来源」开关）
#   2. 轮询 uiautomator：先勾选「已了解应用的风险检测结果」复选框（不勾选按钮会被静默忽略），
#      再点「继续安装/允许」等确认按钮 —— 全程自动，无需人工
#   3. INSTALL_FAILED_ABORTED（用户拒绝等）→ 自动重试一次（覆盖安装幂等）
#   4. adb 通道挂起 → 自动 adb kill-server && adb start-server 后重试一次
#   5. 兜底：仍失败时把 APK push 到 /sdcard/Download/，提示用文件管理器点开安装（弹窗更显眼）
param([Parameter(Mandatory=$true)][string]$ApkPath)

$adb = "adb"
$patterns = @("继续安装", "仍要安装", "仍然安装", "允许安装", "允许", "确定", "安装", "Install", "INSTALL")

function Test-ConfirmDialog {
    param([string]$Xml)
    $cb = [regex]::Match($Xml, 'text="已了解应用的风险检测结果"[^>]*checked="false"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"')
    if ($cb.Success) {
        $cx = ([int]$cb.Groups[1].Value + [int]$cb.Groups[3].Value) / 2
        $cy = ([int]$cb.Groups[2].Value + [int]$cb.Groups[4].Value) / 2
        Write-Host "risk checkbox unchecked -> tapping ($cx,$cy)"
        & $adb shell input tap $cx $cy | Out-Null
        Start-Sleep -Seconds 1
        return $true
    }
    foreach ($pat in $patterns) {
        $m = [regex]::Match($Xml, "text=`"$pat`"[^>]*bounds=`"\[(\d+),(\d+)\]\[(\d+),(\d+)\]`"")
        if ($m.Success) {
            $cx = ([int]$m.Groups[1].Value + [int]$m.Groups[3].Value) / 2
            $cy = ([int]$m.Groups[2].Value + [int]$m.Groups[4].Value) / 2
            Write-Host ("confirm button '{0}' at ({1},{2}) -> tapping" -f $pat, $cx, $cy)
            & $adb shell input tap $cx $cy | Out-Null
            Start-Sleep -Seconds 2
            return $true
        }
    }
    return $false
}

function Invoke-InstallOnce {
    param([string]$ApkPath)
    $tmp = Join-Path $env:TEMP ("inst_" + [guid]::NewGuid().ToString("N") + ".out.txt")
    $tmpErr = Join-Path $env:TEMP ("inst_" + [guid]::NewGuid().ToString("N") + ".err.txt")
    $p = Start-Process -FilePath $adb -ArgumentList @("install", "-r", "`"$ApkPath`"") -NoNewWindow -RedirectStandardOutput $tmp -RedirectStandardError $tmpErr -PassThru
    Write-Host "install started (pid $($p.Id)), polling for confirm dialog..."
    for ($i = 0; $i -lt 40; $i++) {
        Start-Sleep -Seconds 2
        if ($p.HasExited) { break }
        $null = & $adb shell uiautomator dump /sdcard/inst_dlg.xml 2>$null
        $xml = (& $adb shell cat /sdcard/inst_dlg.xml 2>$null) -join "`n"
        if ($xml) { $null = Test-ConfirmDialog -Xml $xml }
    }
    $hung = $false
    if (-not $p.HasExited) {
        $hung = $true
        Stop-Process -Id $p.Id -Force -ErrorAction SilentlyContinue
    }
    Write-Host "=== install output ==="
    $out = @(Get-Content $tmp -ErrorAction SilentlyContinue) + @(Get-Content $tmpErr -ErrorAction SilentlyContinue)
    $out | ForEach-Object { Write-Host $_ }
    Remove-Item $tmp -ErrorAction SilentlyContinue
    Remove-Item $tmpErr -ErrorAction SilentlyContinue
    $rc = if ($p.HasExited) { $p.ExitCode } else { -1 }
    return @{ Rc = $rc; Hung = $hung; Output = ($out -join "`n") }
}

$r = Invoke-InstallOnce -ApkPath $ApkPath
if ($r.Rc -ne 0 -or $r.Hung) {
    Write-Host "--- first attempt failed/hung; recovering adb channel and retrying once ---"
    & $adb kill-server | Out-Null
    & $adb start-server | Out-Null
    Start-Sleep -Seconds 2
    $r = Invoke-InstallOnce -ApkPath $ApkPath
}
if ($r.Rc -ne 0) {
    $dest = "/sdcard/Download/" + (Split-Path $ApkPath -Leaf)
    Write-Host "--- fallback: pushing to $dest for manual install ---"
    & $adb push $ApkPath $dest
    Write-Host "请在手机上用文件管理器打开 /sdcard/Download/ 下的 APK 点击安装（弹窗更显眼）"
    exit 1
}
Write-Host "INSTALL OK"
