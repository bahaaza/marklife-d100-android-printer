# Command Cheat Sheet

Run commands from the repository root:

```sh
cd /home/arsen/clones/marklife-d100-android-printer
```

If Gradle cannot find the Android SDK, set `ANDROID_HOME` for the command:

```sh
ANDROID_HOME=/home/arsen/Android/Sdk ./gradlew :app:assembleDebug
```

## Build

```sh
./gradlew :app:assembleDebug
./gradlew :app:assembleRelease
./gradlew :app:assembleDebug :app:assembleRelease
```

Clean generated build outputs:

```sh
./gradlew clean
```

Show more warning detail:

```sh
./gradlew :app:assembleDebug --warning-mode all
```

## Install

Install the debug APK on a connected Android device:

```sh
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Install the release APK:

```sh
adb install -r app/build/outputs/apk/release/app-release-unsigned.apk
```

Uninstall the app:

```sh
adb uninstall com.marklife.d100printer
```

## Device Checks

List connected devices:

```sh
adb devices
```

Check Android version:

```sh
adb shell getprop ro.build.version.release
adb shell getprop ro.build.version.sdk
```

Open the app:

```sh
adb shell monkey -p com.marklife.d100printer 1
```

Open Android Bluetooth settings:

```sh
adb shell am start -a android.settings.BLUETOOTH_SETTINGS
```

## Logs

Follow app logs:

```sh
adb logcat | grep -E 'D100Printer|D100JbigEncoder|AndroidRuntime'
```

Clear logcat first, then reproduce:

```sh
adb logcat -c
adb logcat | grep -E 'D100Printer|D100JbigEncoder|AndroidRuntime'
```

Capture logs to a file:

```sh
adb logcat -d > d100-logcat.txt
```

## Share/Intent Testing

Send a PDF file path already on the device to the app:

```sh
adb shell am start \
  -a android.intent.action.SEND \
  -t application/pdf \
  --eu android.intent.extra.STREAM file:///sdcard/Download/test.pdf \
  -n com.marklife.d100printer/.MainActivity
```

Send a PDF URL as shared text:

```sh
adb shell am start \
  -a android.intent.action.SEND \
  -t text/plain \
  --es android.intent.extra.TEXT 'https://example.com/test.pdf' \
  -n com.marklife.d100printer/.MainActivity
```

## Bluetooth Notes

The app expects a paired D100 over classic Bluetooth SPP/RFCOMM.

- Pair in Android Bluetooth settings.
- PIN: `0000`
- Tested device name: `D100-1B43`
- Tested address: `02:26:5D:C0:1B:43`

The Settings screen has a test button that connects and queries:

- Firmware command: `1F 1B 1A 1D 03`
- Serial command: `1F 1B 1A 1D 00`

## Git

Check local changes:

```sh
git status --short
git diff --stat
git diff
```

Stage and commit:

```sh
git add .
git commit -m "Describe the change"
```

Push current branch:

```sh
git push origin HEAD
```
