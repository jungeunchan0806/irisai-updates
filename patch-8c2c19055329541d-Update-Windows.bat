@echo off
setlocal
cd /d "%~dp0"
if not exist ".venv\Scripts\python.exe" call Setup-Windows.bat --quiet
set "PYTHONUTF8=1"
".venv\Scripts\python.exe" -m pyla_addons.updater %*
pause
