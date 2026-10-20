@echo off
setlocal

if "%~1"=="" (
    echo Please provide the path to the folder containing the APKs.
    exit /b
)

set APK_FOLDER=%~1
set OUTPUT_APK_NAME=%~2

if not exist "%APK_FOLDER%" (
    echo The folder %APK_FOLDER% does not exist.
    exit /b
)

if "%OUTPUT_APK_NAME%"=="" (
    set OUTPUT_APK_NAME=output.apk
)

java -jar APKEditor.jar merge -i "%APK_FOLDER%" -o "%OUTPUT_APK_NAME%" -clean-meta -f
if errorlevel 1 (
    echo Error executing APKEditor.jar. Ensure the file exists and is accessible.
    exit /b
)

set APK_PATH="%OUTPUT_APK_NAME%"
set APK_PATH="%OUTPUT_APK_NAME%"
for %%F in (%APK_PATH%) do set APK_NAME=%%~nF
set KEYSTORE_NAME=%APK_NAME%.jks
set ALIAS_NAME=%APK_NAME%

if not exist %APK_PATH% (
    echo The file %APK_PATH% does not exist.
    exit /b
)

if not exist "%KEYSTORE_NAME%" (
    echo Creating a new keystore...
    keytool -genkey -v -keystore "%KEYSTORE_NAME%" -keyalg RSA -keysize 2048 -validity 10000 -alias "%ALIAS_NAME%"
)

echo Signing the APK...
apksigner sign --ks "%KEYSTORE_NAME%" --ks-key-alias "%ALIAS_NAME%" %APK_PATH%

set ALIGNED_APK_NAME=%APK_NAME%_aligned.apk
echo Aligning the APK...
zipalign -v 4 %APK_PATH% "%ALIGNED_APK_NAME%"

echo Verifying the APK signature...
apksigner verify "%ALIGNED_APK_NAME%"

echo Process completed.
endlocal
pause
