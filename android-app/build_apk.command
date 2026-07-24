#!/bin/bash
set -e
cd "$(dirname "$0")"
if [ -z "$ANDROID_HOME" ] && [ -d "$HOME/Library/Android/sdk" ]; then
  export ANDROID_HOME="$HOME/Library/Android/sdk"
fi
if [ -z "$ANDROID_HOME" ]; then
  echo "Android SDK was not found. Install Android Studio once, then run this file again."
  exit 1
fi
./gradlew assembleDebug
mkdir -p ../apk
cp app/build/outputs/apk/debug/app-debug.apk ../apk/HaloLink-v0.1.7-debug.apk
(cd ../apk && shasum -a 256 HaloLink-v0.1.7-debug.apk > HaloLink-v0.1.7-debug.apk.sha256)
echo "APK created: ../apk/HaloLink-v0.1.7-debug.apk"
