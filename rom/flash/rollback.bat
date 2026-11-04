@echo off
rem Takes AMRI on the K2501 head unit back to the previous version it keeps on the unit. Its data stays; no pack is needed.
rem Needs Python 3 on this PC (aura-install.py carries its own ADB client). Without Python: adb connect IP:5555, then
rem adb shell sh /data/local/tmp/amri-rollback/rollback.sh
chcp 65001 >nul
set /p IP=Unit IP address (Settings - Wi-Fi on the unit), e.g. 192.168.1.50 :
where py >nul 2>nul
if %ERRORLEVEL%==0 (
    py -3 "%~dp0aura-install.py" rollback --ip %IP%
) else (
    python "%~dp0aura-install.py" rollback --ip %IP%
)
pause
