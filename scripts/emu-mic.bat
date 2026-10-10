@echo off
rem The emulator's microphone and speaker, chosen from the PC's (see emu-mic.ps1).
start "" powershell -NoProfile -STA -ExecutionPolicy Bypass -WindowStyle Hidden -File "%~dp0emu-mic.ps1"
