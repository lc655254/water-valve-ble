@echo off
chcp 65001 >nul
cd /d "%~dp0"
python -u probe.py
echo.
pause
