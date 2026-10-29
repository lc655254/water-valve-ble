@echo off
chcp 65001 >nul
cd /d "%~dp0"
python -u tap.py close
echo.
pause
