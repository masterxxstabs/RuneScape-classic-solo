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

rem Run a copy so an update can rebuild the launcher while the old one is still closing.
if not exist ".launcher" mkdir ".launcher"
set /a tries=0
:copy
copy /y "tools\launcher\build\libs\launcher.jar" ".launcher\launcher.jar" >nul 2>nul && goto run
set /a tries+=1
if %tries% geq 15 goto run
ping -n 2 127.0.0.1 >nul
goto copy

:run
set "JAVAW=javaw"
if defined JAVA_HOME set "JAVAW=%JAVA_HOME%\bin\javaw.exe"
start "" "%JAVAW%" -jar ".launcher\launcher.jar"
