@echo off
chcp 65001 >nul
title DSH Mobile PC Bridge
echo.
echo 正在启动 USB 隧道维持（手机 127.0.0.1:3080 -^> 电脑 DSH Web）...
echo.
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0pc_bridge.ps1"
echo.
echo 隧道已停止（窗口关闭）。
pause
