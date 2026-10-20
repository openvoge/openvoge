@echo off
setlocal enabledelayedexpansion

if "%~1"=="" (
    echo Please provide a package ID or partial package ID.
    exit /b
)

set package_input=%~1
set output_dir=output

if not "%~2"=="" (
    set output_dir=%~2
)

if not exist "!output_dir!" (
    mkdir "!output_dir!"
)

set found=0

for /f "delims=" %%p in ('adb shell pm list packages ^| findstr /i "%package_input%"') do (
    set package_name=%%p
    set package_name=!package_name:package=!
    set package_name=!package_name:~1!
    echo Found package: !package_name!
    set found=1

    for /f "delims=" %%a in ('adb shell pm path !package_name!') do (
        set apk_path=%%a
        set apk_path=!apk_path:package:=!
        echo Pulling APK from !apk_path!...
        adb pull !apk_path! "!output_dir!"
    )
)

if !found! equ 0 (
    echo No packages found matching "%package_input%".
) else (
    echo Extraction completed for packages matching "%package_input%".
)

pause
