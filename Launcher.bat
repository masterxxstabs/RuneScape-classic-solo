@echo off
setlocal
cd /d "%~dp0"

where java >nul 2>nul
if errorlevel 1 if not defined JAVA_HOME (
    echo Java 21 is required. Download it from https://adoptium.net/temurin/releases/?version=21
    pause
    exit /b 1
)

echo Preparing the launcher. The first run downloads build tools and can take a few minutes...
call gradlew.bat --console=plain -q :tools:launcher:shadowJar
if errorlevel 1 (
    echo.
    echo Building the launcher failed. Make sure Java 21 is installed:
    echo https://adoptium.net/temurin/releases/?version=21
    pause
    exit /b 1
)

set "JAVAW=javaw"
if defined JAVA_HOME set "JAVAW=%JAVA_HOME%\bin\javaw.exe"
start "" "%JAVAW%" -jar "tools\launcher\build\libs\launcher.jar"
