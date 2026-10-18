@echo off
rem AMRI lyrics server: more lyrics sources for the car, and lyrics timed by listening (README.md / README.ar.md).
rem Run setup.bat once first. Close this window to stop the server.
rem Where things are (each can be set before running this):
rem   AMRI_LYRICS_VENV   the Python environment setup.bat made       (default: beside the repository, lyrics-venv)
rem   AMRI_LYRICS_CACHE  speech models, pip's downloads, the server's home with its key (default: lyrics-cache)
title AMRI lyrics server
setlocal
for %%I in ("%~dp0..\..\..") do set "BESIDE=%%~fI"
if not defined AMRI_LYRICS_VENV set "AMRI_LYRICS_VENV=%BESIDE%\lyrics-venv"
if not defined AMRI_LYRICS_CACHE set "AMRI_LYRICS_CACHE=%BESIDE%\lyrics-cache"
if not defined AMRI_LYRICS_HOME set "AMRI_LYRICS_HOME=%AMRI_LYRICS_CACHE%\server"
rem (the speech, voice and word models and every temporary file on this drive, not on C:)
set "HF_HOME=%AMRI_LYRICS_CACHE%\hf"
set "TORCH_HOME=%AMRI_LYRICS_CACHE%\torch"
set "TEMP=%AMRI_LYRICS_CACHE%\tmp"
set "TMP=%AMRI_LYRICS_CACHE%\tmp"
set "PYTHONIOENCODING=utf-8"
if not exist "%TEMP%" mkdir "%TEMP%"
if not exist "%AMRI_LYRICS_VENV%\Scripts\python.exe" (
  echo The Python environment is missing: run setup.bat first.
  pause
  exit /b 1
)
"%AMRI_LYRICS_VENV%\Scripts\python.exe" "%~dp0server.py" %*
if errorlevel 1 pause
