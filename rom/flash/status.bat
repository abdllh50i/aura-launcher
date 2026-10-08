@echo off
rem Shows whether the unit has the stock firmware or the Aura ROM (writes nothing).
chcp 65001 >nul
set /p IP=Unit IP address (Settings - Wi-Fi on the unit), e.g. 192.168.1.50 :
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0aura-rom.ps1" -Action Status -Ip %IP%
pause
