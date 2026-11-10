@echo off
setlocal
cd /d "%~dp0"
py -3.11 Apply-Windows-Patch.py %*
if errorlevel 1 echo Install Python 3.11 x64 if the Python launcher was not found.
pause
