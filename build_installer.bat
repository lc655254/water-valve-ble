@echo off
cd /d "%~dp0"
if not exist "dist\蓝牙水库安全版\蓝牙水库安全版.exe" (
  echo 先运行 build_onedir.bat
  pause
  exit /b 1
)
set "ISCC=%LOCALAPPDATA%\Programs\Inno Setup 6\ISCC.exe"
if not exist "%ISCC%" set "ISCC=C:\Program Files (x86)\Inno Setup 6\ISCC.exe"
if not exist "%ISCC%" (
  echo 未找到 Inno Setup 6 的 ISCC.exe
  pause
  exit /b 1
)
"%ISCC%" "installer\water_valve.iss"
if exist "installer\Output\蓝牙水库_Setup_v1.0.0.exe" copy /y "installer\Output\蓝牙水库_Setup_v1.0.0.exe" "蓝牙水库_Setup_v1.0.0.exe" >nul
pause
