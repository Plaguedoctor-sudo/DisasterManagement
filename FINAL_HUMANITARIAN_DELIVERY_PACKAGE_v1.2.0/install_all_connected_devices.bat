@echo off
REM ==============================================================================
REM DisasterManagement v1.2.0 - Batch Field ADB Installer for Windows
REM ==============================================================================

setlocal enabledelayedexpansion
title DisasterManagement Batch Deployment

set APK_FILE=%~dp0DisasterManagement-v1.2.0-signed.apk

echo ==================================================================
echo 🛡️ DisasterManagement Batch Field Device Deployment (Windows)
echo ==================================================================

if not exist "%APK_FILE%" (
    echo [ERROR] APK not found: %APK_FILE%
    pause
    exit /b 1
)

where adb >nul 2>&1
if %errorlevel% neq 0 (
    echo [ERROR] 'adb' is not recognized. Please install Android Platform Tools.
    pause
    exit /b 1
)

echo Scanning for attached devices...
for /f "skip=1 tokens=1" %%d in ('adb devices') do (
    if not "%%d"=="" if not "%%d"=="offline" (
        echo ------------------------------------------------------------------
        echo 📲 Installing on device: %%d
        adb -s %%d install -r -d "%APK_FILE%"
    )
)

echo ==================================================================
echo 🎯 Batch deployment sequence finished.
echo ==================================================================
pause
