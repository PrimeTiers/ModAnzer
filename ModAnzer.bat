@echo off
title ModAnzer
rem ModAnzer launcher - double-click to run the ModAnzer screenshare scanner.
rem Source: https://github.com/napatc1/ModAnzer
rem Needs Java 22 or newer (https://adoptium.net). Most people should use ModAnzer.exe instead,
rem which has Java built in. If ModAnzer.jar is in the same folder it runs that copy,
rem otherwise it downloads the latest one next to this file.

if not exist "%~dp0ModAnzer.jar" (
    echo Downloading ModAnzer.jar...
    curl -L -s -o "%~dp0ModAnzer.jar" https://github.com/napatc1/ModAnzer/releases/download/apk/ModAnzer.jar
)

java --enable-native-access=ALL-UNNAMED -jar "%~dp0ModAnzer.jar" %*

if %errorlevel% neq 0 (
    echo.
    echo ModAnzer could not start. It needs Java 22 or newer: https://adoptium.net
    echo Or download ModAnzer.exe, which does not need Java.
    pause
)
