@echo off
setlocal
cd /d "%~dp0"
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0pyla_addons\setup_windows.ps1"
set "result=%errorlevel%"
if not "%~1"=="--quiet" pause
exit /b %result%
