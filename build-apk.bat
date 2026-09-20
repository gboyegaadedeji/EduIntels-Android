@echo off
where gradle >nul 2>nul
if errorlevel 1 (
  echo Gradle is not installed. Open this project in Android Studio, or use the included GitHub Actions workflow.
  exit /b 1
)
gradle :app:assembleDebug
if errorlevel 1 exit /b 1
echo.
echo APK: app\build\outputs\apk\debug\app-debug.apk
