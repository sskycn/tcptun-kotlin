#!/bin/bash
set -e

./gradlew installDebug

# adb install app/build/outputs/apk/release/app-release.apk

# ./gradlew assembleRelease
# ./gradlew assembleDebug

# adb logcat | grep com.gostartkit.base > log.txt
# git diff agri...feature/organization > diff.txt

# adb kill-server
# adb start-server
# adb devices
