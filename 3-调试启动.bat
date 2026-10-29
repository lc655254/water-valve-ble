@echo off
chcp 65001 >nul
cd /d "%~dp0"
set WV_DEBUG=1
python -u WaterValveBLE_Web.py
echo.
pause
