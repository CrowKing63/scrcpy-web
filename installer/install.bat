@echo off
if not exist "%~dp0install.ps1" (
    echo.
    echo ERROR: install.ps1 not found in this folder.
    echo Please re-download the latest installer zip from GitHub Releases.
    echo.
    pause
    exit /b 1
)
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0install.ps1"
if %errorlevel% neq 0 pause
