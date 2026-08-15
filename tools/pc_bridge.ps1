# pc_bridge.ps1 — DSH Mobile「连接电脑」USB 隧道智能维持
# 用法（推荐双击 pc_bridge.bat，或手动执行）：
#   powershell -NoProfile -ExecutionPolicy Bypass -File tools\pc_bridge.ps1
# 可选参数: -Port 3080
#
# 原理：电脑上的 DSH Web 默认只监听 127.0.0.1（上游安全设计，拒绝 0.0.0.0 绑定）。
#       本脚本用 adb reverse 把手机 127.0.0.1:3080 转发到电脑 127.0.0.1:3080，
#       手机 App 里服务地址保持 http://127.0.0.1:3080 即可连接电脑。
#
# 智能跟随 App 模式（读取手机上的 shared_prefs，需要 debug 包的 run-as 权限）：
#   - 「连接电脑」模式：建立并维持隧道（设备重插 / adb 重启后每 3 秒自动重建）
#   - 「本机内置服务」模式：移除隧道，把手机 3080 让给内置 DSH
#   （反向隧道与内置服务都要用手机 127.0.0.1:3080，二者互斥，必须让位）
param([int]$Port = 3080)

$ErrorActionPreference = "Continue"

function Get-LanIp {
    try {
        Get-NetIPAddress -AddressFamily IPv4 -ErrorAction SilentlyContinue |
            Where-Object { $_.IPAddress -notlike "127.*" -and $_.IPAddress -notlike "169.254.*" } |
            Select-Object -ExpandProperty IPAddress -First 1
    } catch { $null }
}

function Get-AppMode {
    try {
        $prefs = adb shell "run-as com.dsh.mobile cat shared_prefs/dsh_mobile.xml" 2>$null
        if (($prefs -join "`n") -match 'name="server_mode"[^>]*>pc<') { return "pc" }
        if (($prefs -join "`n") -match 'name="server_mode"') { return "local" }
    } catch { }
    return "unknown"
}

Write-Host "== DSH Mobile PC 桥接 ==" -ForegroundColor Cyan
Write-Host ("电脑局域网 IP: {0}" -f (Get-LanIp)) -ForegroundColor DarkGray
Write-Host "智能跟随 App 模式：连接电脑=维持隧道 / 本机模式=让出端口" -ForegroundColor DarkGray
Write-Host "保持本窗口打开。Ctrl+C 退出。" -ForegroundColor DarkGray
Write-Host ""

$stateShown = ""
while ($true) {
    $dev = adb devices 2>$null | Select-String -Pattern "^\S+\s+device(\s|$)"
    if (-not $dev) {
        if ($stateShown -ne "nousb") {
            Write-Host ("[{0}] 手机断开，等待重新连接（接上后自动恢复）..." -f (Get-Date -Format "HH:mm:ss")) -ForegroundColor Yellow
            $stateShown = "nousb"
        }
        Start-Sleep -Seconds 3
        continue
    }

    $mode = Get-AppMode
    if ($mode -eq "pc") {
        $out = (adb reverse "tcp:$Port" "tcp:$Port" 2>&1 | Out-String)
        if ($LASTEXITCODE -eq 0) {
            if ($stateShown -ne "pc") {
                Write-Host ("[{0}] 「连接电脑」模式：隧道已建立 手机127.0.0.1:{1} -> 电脑" -f (Get-Date -Format "HH:mm:ss"), $Port) -ForegroundColor Green
                Write-Host "       手机 App 服务地址保持 http://127.0.0.1:$Port 即可。" -ForegroundColor Green
                $stateShown = "pc"
            }
        } else {
            if ($stateShown -ne "busy") {
                Write-Host ("[{0}] 模式已是「连接电脑」，但手机 {1} 端口仍被内置服务占用，继续尝试..." -f (Get-Date -Format "HH:mm:ss"), $Port) -ForegroundColor Yellow
                $stateShown = "busy"
            }
        }
    } else {
        $has = adb reverse --list 2>$null | Select-String "tcp:$Port"
        if ($has) {
            adb reverse --remove "tcp:$Port" 2>$null | Out-Null
            Write-Host ("[{0}] 「本机内置服务」模式：隧道让位，手机 3080 交还内置 DSH" -f (Get-Date -Format "HH:mm:ss")) -ForegroundColor DarkGray
        }
        $stateShown = "local"
    }
    Start-Sleep -Seconds 3
}
