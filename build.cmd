@echo off
cd /d "%~dp0"
python tools\build.py %*
exit /b %errorlevel%
