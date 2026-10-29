@echo off
chcp 65001 >nul
cd /d "%~dp0"
python -u selftest.py
echo.
python -u bench.py
echo.
pause
