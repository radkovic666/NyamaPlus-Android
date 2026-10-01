@echo off
setlocal EnableExtensions
cd /d "%~dp0"

echo ============================================================
echo Nyama+ 2.0.1 - First-time Gradle preparation
echo ============================================================
echo.
echo This removes ONLY the temporary .gradle folder inside this project.
echo Your source code and Android Studio installation are not touched.
echo.

if exist ".gradle" (
  echo Removing old project Gradle state...
  rmdir /s /q ".gradle"
)

echo Preparing the Gradle 9.6.0 wrapper...
call gradlew.bat --stop
if errorlevel 1 goto :fail
call gradlew.bat --version
if errorlevel 1 goto :fail

echo.
echo ============================================================
echo READY.
echo Now open this folder in Android Studio and press Gradle Sync.
echo In Android Studio use the Wrapper and Embedded JDK.
echo ============================================================
pause
exit /b 0

:fail
echo.
echo Preparation failed. Check that the PC has Internet access.
echo If Java is not found, open Android Studio once and set Gradle JDK to Embedded JDK.
pause
exit /b 1
