# Marklife D100 Android Printer

Standalone Android app that receives PDF files (or direct PDF links) and prints them on a Marklife D100 over Bluetooth classic SPP/RFCOMM.

This repository contains only the Android app, extracted from the larger web-kit workspace.

## Features

- Share-to-print via Android ACTION_SEND for application/pdf.
- Share link support for text/plain when the URL looks like a PDF link.
- Optional auto-print immediately after share intake.
- File picker for local PDFs.
- First-page preview before printing.
- Multi-page and multi-copy printing.
- Blank test page print for transport/media diagnostics.
- Printer selection and connection test dialog.
- Label geometry settings:
    - Width (mm)
    - Height (mm)
    - Extra feed compensation (mm)
    - Paper mode (gap labels or continuous roll)

## High-Level Architecture

Pipeline:

1. Intake: PDF URI or PDF URL is accepted and staged locally.
2. Validate: basic PDF signature and EOF checks before print.
3. Render: PDF pages -> monochrome-ready bitmaps at configured dots.
4. Connect: Bluetooth RFCOMM SPP socket opened to paired D100.
5. Handshake: send ready command and wait for valid status packets.
6. Encode: each page is split into strips and JBIG1-compressed via JNI.
7. Transmit: protocol headers + JBIG blocks are sent in throttled chunks.

## Protocol Details (D100)

The implementation follows observed working traffic and compatible command behavior.

### Transport

- Link: Bluetooth classic RFCOMM (SPP UUID)
    - 00001101-0000-1000-8000-00805f9b34fb
- Chunking:
    - chunk size: 127 bytes
    - delay between chunks: 14 ms
- Connection attempts:
    - secure RFCOMM by UUID
    - insecure RFCOMM by UUID
    - reflection fallback createRfcommSocket(channel=1)

### Ready Handshake

- Send a 45-byte READY_COMMAND frame after socket open.
- Read incoming bytes until at least one valid status packet is found.
- Valid status packet rule:
    - starts with 0x53
    - XOR of packet bytes equals 0x00
- Handshake has retry/timeout logic to tolerate startup noise.

### Print Header Commands

Before image data, the app sends print setup commands:

- Speed: 1F 28 73 02 00 VV 00
    - VV = 0x64 for gap mode, 0x96 for continuous mode
- Density: 12 23 07
- Left margin: 1D 4C 00 00
- Width register: 1D 57 [width_lo] [width_hi]
- Alignment: 1B 61 01

For gap media, page markers are used:

- Page start: 1A 0C FF
- Page end: 1A 0C 00

### Image Data Blocks

- Input bitmap is converted to 1-bit packed raster.
- Sliced into strips up to 255 rows each.
- Each strip is JBIG1-encoded (jbigkit through JNI).
- Each JBIG stream is preceded by:
    - 1F 28 4A [len_lo] [len_hi] [w_lo] [w_hi] [height]
    - len = jbig_bytes + 3 (width+height metadata)

## App Behavior Notes

- Auto-print and manual print use the same internal print pipeline.
- Extra feed compensation is applied by increasing effective render height:
    - effective_height_mm = height_mm + height_comp_mm
- Copies are printed per page in sequence.
- In-progress print jobs are guarded to prevent concurrent prints.

## Project Layout

```text
.
├── app/
│   ├── src/main/java/com/marklife/d100printer/
│   │   ├── MainActivity.kt
│   │   ├── D100Printer.kt
│   │   ├── PdfPageRenderer.kt
│   │   └── JbigEncoder.kt
│   ├── src/main/cpp/
│   │   ├── CMakeLists.txt
│   │   ├── jbig_jni.c
│   │   └── jbigkit/
│   │       ├── jbig.c
│   │       ├── jbig.h
│   │       ├── jbig_ar.c
│   │       └── jbig_ar.h
│   ├── src/main/res/
│   └── src/main/AndroidManifest.xml
├── gradle/
├── gradlew
├── gradlew.bat
├── build.gradle
├── settings.gradle
└── gradle.properties
```

## Requirements

- Android Studio (recent stable)
- Android SDK with compile/target API 35
- NDK + CMake installed (for JNI encoder)
- Physical Android device (Bluetooth not usable in emulator)
- Marklife D100 paired with phone (PIN 0000)

## Build and Install

From repository root:

```sh
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Or open in Android Studio and run app on a physical device.

## Usage

1. Pair D100 in Android Bluetooth settings.
2. Open app once and grant Bluetooth permissions.
3. Optional: open Settings and configure label dimensions, paper mode, auto-print.
4. Share a PDF (or valid PDF URL text) to this app, or pick a PDF from files.
5. Preview and print.

## Settings Reference

- Label width (mm): render width converted with 8 dots/mm.
- Label height (mm): base render height.
- Extra feed compensation (mm): added to height before rendering.
- Paper mode:
    - Gap labels (uses page start/end markers)
    - Continuous roll
- Printer selector:
    - Auto-select D100
    - Explicit paired device address
- Auto-print on share:
    - If enabled, incoming shared PDF starts print automatically.

## Troubleshooting

- No printer found:
    - Ensure device is paired and powered on.
    - Confirm app has Bluetooth permissions.
- Handshake timeout:
    - Power-cycle printer and retry.
    - Stay near printer and avoid active discovery in other apps.
- Stops mid-print or partial print:
    - Use gap mode for gap labels.
    - Keep extra feed compensation small then increase gradually.
    - Current transport tuning uses slower chunk pacing for reliability.
- Shared link not printing:
    - Link must resolve to a PDF and pass staging validation.
    - Try sharing file directly if source host blocks download.

## Security and Permissions

- Bluetooth permissions are requested according to Android version.
- For Android 12+, BLUETOOTH_SCAN and BLUETOOTH_CONNECT are required.
- INTERNET is used only for PDF link download path.

## JNI and Licensing Notes

- Native encoder is based on jbigkit (libjbig).
- Verify licensing obligations for your distribution model, including source availability requirements where applicable.

## Known Limitations

- Works with classic Bluetooth SPP printers; BLE-only models are not supported.
- PDF link detection is heuristic (text share must contain an HTTP(S) URL that looks like PDF).
- Extra feed is implemented by image height compensation, not a separate post-print feed opcode.

## Development Tips

- Main orchestration: app/src/main/java/com/marklife/d100printer/MainActivity.kt
- Printer protocol and transport: app/src/main/java/com/marklife/d100printer/D100Printer.kt
- PDF rasterization: app/src/main/java/com/marklife/d100printer/PdfPageRenderer.kt
- JNI bridge: app/src/main/cpp/jbig_jni.c

## Disclaimer

This project is reverse-engineered for interoperability with D100-compatible behavior. Firmware differences may exist across hardware revisions.
