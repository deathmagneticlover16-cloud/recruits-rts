@echo off
setlocal

if not exist "libs\recruits.jar" (
  echo Missing libs\recruits.jar
  exit /b 1
)
if not exist "libs\freecam.jar" (
  echo Missing libs\freecam.jar
  exit /b 1
)
call gradlew.bat clean build
if errorlevel 1 exit /b %errorlevel%

echo.
echo Built JAR:
echo   build\libs\recruits-rts-camera-0.15.2-server.1.jar
