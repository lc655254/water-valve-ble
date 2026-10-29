@echo off
chcp 65001 >nul
cd /d "%~dp0"
python -u WaterValveBLE_Web.py
echo.
pause
