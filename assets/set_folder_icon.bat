@echo off
attrib +s "%~dp0."
attrib +h +s "%~dp0desktop.ini"
ie4uinit.exe -show >nul 2>&1
exit /b 0
