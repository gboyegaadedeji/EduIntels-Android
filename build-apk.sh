#!/usr/bin/env bash
set -euo pipefail
if ! command -v gradle >/dev/null 2>&1; then
  echo "Gradle is not installed. Open this project in Android Studio, or use the included GitHub Actions workflow."
  exit 1
fi
gradle :app:assembleDebug
printf '\nAPK: app/build/outputs/apk/debug/app-debug.apk\n'
