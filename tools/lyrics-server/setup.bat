@echo off
rem Makes the lyrics server's Python environment (once; again after requirements.txt changes) and fetches the speech
rem model, so the first song is not kept waiting. Everything big goes beside the repository, not on C: (which is
rem nearly full on the workshop PC): the environment, pip's downloads, the model (about 1.6 GB).
rem Needs Python 3.9 or newer on PATH (python.org's installer, "Add python.exe to PATH").
title AMRI lyrics server - setup
setlocal
for %%I in ("%~dp0..\..\..") do set "BESIDE=%%~fI"
if not defined AMRI_LYRICS_VENV set "AMRI_LYRICS_VENV=%BESIDE%\lyrics-venv"
if not defined AMRI_LYRICS_CACHE set "AMRI_LYRICS_CACHE=%BESIDE%\lyrics-cache"
if not defined AMRI_LYRICS_HOME set "AMRI_LYRICS_HOME=%AMRI_LYRICS_CACHE%\server"
set "PIP_CACHE_DIR=%AMRI_LYRICS_CACHE%\pip"
set "HF_HOME=%AMRI_LYRICS_CACHE%\hf"
set "TEMP=%AMRI_LYRICS_CACHE%\tmp"
set "TMP=%AMRI_LYRICS_CACHE%\tmp"
set "PYTHONIOENCODING=utf-8"
if not exist "%TEMP%" mkdir "%TEMP%"
echo Environment: %AMRI_LYRICS_VENV%
echo Downloads and models: %AMRI_LYRICS_CACHE%
if not exist "%AMRI_LYRICS_VENV%\Scripts\python.exe" (
  python -c "import sys; sys.exit(0 if sys.version_info >= (3, 9) else 1)" || (echo Python 3.9 or newer is needed on PATH. & pause & exit /b 1)
  python -m venv "%AMRI_LYRICS_VENV%" || (echo Could not make the environment. & pause & exit /b 1)
)
"%AMRI_LYRICS_VENV%\Scripts\python.exe" -m pip install --upgrade pip
"%AMRI_LYRICS_VENV%\Scripts\python.exe" -m pip install -r "%~dp0requirements.txt" || (echo pip failed. & pause & exit /b 1)
echo Fetching the speech model (once)...
"%AMRI_LYRICS_VENV%\Scripts\python.exe" "%~dp0server.py" --download-model || (echo The model could not be fetched. & pause & exit /b 1)
echo.
echo Done. Start the server with lyrics-server.bat
pause
