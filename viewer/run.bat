@echo off
cd /d %~dp0
if not exist out\Main.class call build.bat || exit /b 1
java -cp out Main %*
