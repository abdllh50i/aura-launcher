@echo off
rem Makes the lyrics server's Python environment (once; again after requirements.txt changes) and fetches the models,
rem so the first song is not kept waiting. Everything big goes beside the repository, not on C: (which is nearly full
rem on the workshop PC): the environment (with torch about 5 GB), pip's downloads, the models (about 3.5 GB: speech,
rem voice separator, word aligner).
rem Needs Python 3.9 to 3.12 on PATH (python.org's installer, "Add python.exe to PATH"; 3.11 is the tested one).
rem With an NVIDIA graphics card (nvidia-smi answers) torch comes with CUDA and the server works on the card;
rem without one, everything runs on the processor (slower: a 12 s piece takes several seconds).
title AMRI lyrics server - setup
setlocal
for %%I in ("%~dp0..\..\..") do set "BESIDE=%%~fI"
if not defined AMRI_LYRICS_VENV set "AMRI_LYRICS_VENV=%BESIDE%\lyrics-venv"
if not defined AMRI_LYRICS_CACHE set "AMRI_LYRICS_CACHE=%BESIDE%\lyrics-cache"
if not defined AMRI_LYRICS_HOME set "AMRI_LYRICS_HOME=%AMRI_LYRICS_CACHE%\server"
set "PIP_CACHE_DIR=%AMRI_LYRICS_CACHE%\pip"
set "HF_HOME=%AMRI_LYRICS_CACHE%\hf"
set "TORCH_HOME=%AMRI_LYRICS_CACHE%\torch"
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
rem torch first, from PyTorch's own index: CUDA 12.4 with an NVIDIA card, else the processor's build
set "TORCH_INDEX=https://download.pytorch.org/whl/cpu"
nvidia-smi -L >nul 2>&1 && set "TORCH_INDEX=https://download.pytorch.org/whl/cu124"
echo torch from %TORCH_INDEX%
"%AMRI_LYRICS_VENV%\Scripts\python.exe" -m pip install torch==2.5.1 torchaudio==2.5.1 --index-url %TORCH_INDEX% || (echo torch could not be installed. & pause & exit /b 1)
"%AMRI_LYRICS_VENV%\Scripts\python.exe" -m pip install -r "%~dp0requirements.txt" || (echo pip failed. & pause & exit /b 1)
echo Fetching the models (once)...
"%AMRI_LYRICS_VENV%\Scripts\python.exe" "%~dp0server.py" --download-model || (echo The models could not be fetched. & pause & exit /b 1)
echo.
echo Done. Start the server with lyrics-server.bat
pause
