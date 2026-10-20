@echo off
setlocal

if "%~1"=="" (
    set LOG_FILE=/dev/null
) else (
    set LOG_FILE=%~1
)

adb push frida-server-android /data/local/tmp/
adb shell "chmod 755 /data/local/tmp/frida-server-android"
adb shell "nohup /data/local/tmp/frida-server-android > %LOG_FILE% 2>&1 &"
adb shell ps | findstr frida-server

pause
