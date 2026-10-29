@echo off
cd /d "%~dp0"
python -m PyInstaller --noconfirm --clean --noconsole --name "蓝牙水库安全版" ^
  --add-data "web;web" ^
  --hidden-import valve_guard --hidden-import valve_safety ^
  --hidden-import webview.platforms.edgechromium --hidden-import webview.platforms.winforms ^
  --hidden-import clr --hidden-import waitress ^
  --exclude-module tkinter --exclude-module matplotlib --exclude-module numpy --exclude-module pandas ^
  WaterValveBLE_Web.py
if exist "config.json" copy /y "config.json" "dist\蓝牙水库安全版\config.json" >nul
if exist "ui_prefs.json" copy /y "ui_prefs.json" "dist\蓝牙水库安全版\ui_prefs.json" >nul
echo.
echo dist\蓝牙水库安全版\蓝牙水库安全版.exe
pause
