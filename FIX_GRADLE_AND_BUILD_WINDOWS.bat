@echo off
setlocal EnableExtensions
cd /d "%~dp0"

echo ============================================================
echo Nyama+ - Gradle repair and debug APK builder
echo ============================================================
echo.

echo [1/5] Removing this project's old Gradle state...
if exist ".gradle" rmdir /s /q ".gradle"

echo [2/5] Checking Android SDK path...
if not exist "local.properties" (
  if exist "%LOCALAPPDATA%\Android\Sdk" (
    set "SDK_ESC=%LOCALAPPDATA:\=\\%\Android\\Sdk"
    > local.properties echo sdk.dir=%LOCALAPPDATA:\=\\%\Android\\Sdk
    echo Created local.properties for %LOCALAPPDATA%\Android\Sdk
  ) else (
    echo local.properties was not found. Open the project once in Android Studio first.
  )
)

echo [3/5] Stopping old Gradle daemons...
call gradlew.bat --stop
if errorlevel 1 goto :fail

echo [4/5] Verifying Gradle version...
call gradlew.bat --version
if errorlevel 1 goto :fail

echo [5/5] Building debug APK...
call gradlew.bat :app:assembleDebug --stacktrace
if errorlevel 1 goto :fail

echo.
echo ============================================================
echo SUCCESS
if exist "app\build\outputs\apk\debug\app-debug.apk" (
  echo APK: %CD%\app\build\outputs\apk\debug\app-debug.apk
  explorer.exe /select,"%CD%\app\build\outputs\apk\debug\app-debug.apk"
) else (
  echo Build completed, but the APK path was not found automatically.
)
echo ============================================================
pause
exit /b 0

:fail
echo.
echo ============================================================
echo BUILD FAILED. Copy the LAST red error block and send it to me.
echo ============================================================
pause
exit /b 1
