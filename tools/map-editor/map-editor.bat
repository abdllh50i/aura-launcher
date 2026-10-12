@echo off
rem The AMRI map editor: starts its little server on this PC and opens the page in the browser.
rem Close this window to stop it.
title AMRI map editor
python "%~dp0server.py" %*
if errorlevel 1 pause
