@echo off
setlocal
cd /d "%~dp0"
if exist ".venv\Scripts\python.exe" goto ready
call Setup-Windows.bat --quiet
if errorlevel 1 goto fail
:ready
set "PYTHONUTF8=1"
".venv\Scripts\python.exe" -m pyla_addons.launcher %*
if errorlevel 1 goto fail
exit /b 0
:fail
 echo Startup failed. See the message above, or run Diagnose-Windows.bat.
pause
exit /b 1
