@echo off
rem Restores the stock firmware (undoes the Aura ROM) on the K2501 head unit.
chcp 65001 >nul
set /p IP=Unit IP address (Settings - Wi-Fi on the unit), e.g. 192.168.1.50 :
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0aura-rom.ps1" -Action Restore -Ip %IP%
pause
