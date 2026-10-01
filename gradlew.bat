@echo off
setlocal EnableExtensions
set "APP_HOME=%~dp0"
set "WRAPPER_JAR=%APP_HOME%gradle\wrapper\gradle-wrapper.jar"
set "WRAPPER_URL=https://raw.githubusercontent.com/gradle/gradle/v9.6.0/gradle/wrapper/gradle-wrapper.jar"
set "WRAPPER_SHA256=497c8c2a7e5031f6aa847f88104aa80a93532ec32ee17bdb8d1d2f67a194a9c7"

if not exist "%APP_HOME%gradle\wrapper" mkdir "%APP_HOME%gradle\wrapper"

if not exist "%WRAPPER_JAR%" (
  echo [Nyama+] Gradle Wrapper JAR is missing. Downloading the official Gradle 9.6.0 wrapper...
  where curl.exe >nul 2>&1
  if not errorlevel 1 (
    curl.exe -L --fail --retry 3 --connect-timeout 15 -o "%WRAPPER_JAR%" "%WRAPPER_URL%"
  ) else (
    powershell.exe -NoProfile -ExecutionPolicy Bypass -Command "Invoke-WebRequest -UseBasicParsing -Uri '%WRAPPER_URL%' -OutFile '%WRAPPER_JAR%'"
  )
  if not exist "%WRAPPER_JAR%" (
    echo ERROR: Could not download gradle-wrapper.jar.
    echo Check your Internet connection and try again.
    exit /b 1
  )
)

for /f "tokens=*" %%H in ('powershell.exe -NoProfile -Command "(Get-FileHash -Algorithm SHA256 -LiteralPath '%WRAPPER_JAR%').Hash.ToLower()"') do set "ACTUAL_SHA=%%H"
if /I not "%ACTUAL_SHA%"=="%WRAPPER_SHA256%" (
  echo ERROR: gradle-wrapper.jar checksum does not match the official Gradle checksum.
  del /q "%WRAPPER_JAR%" >nul 2>&1
  exit /b 1
)

if defined JAVA_HOME (
  set "JAVA_EXE=%JAVA_HOME%\bin\java.exe"
) else if exist "%ProgramFiles%\Android\Android Studio\jbr\bin\java.exe" (
  set "JAVA_EXE=%ProgramFiles%\Android\Android Studio\jbr\bin\java.exe"
) else (
  set "JAVA_EXE=java.exe"
)

"%JAVA_EXE%" -version >nul 2>&1
if errorlevel 1 (
  echo ERROR: Java was not found. In Android Studio choose an Embedded JDK for Gradle.
  exit /b 1
)

"%JAVA_EXE%" -Dfile.encoding=UTF-8 -Xmx64m -Xms64m -Dorg.gradle.appname=gradlew -jar "%WRAPPER_JAR%" %*
exit /b %ERRORLEVEL%
