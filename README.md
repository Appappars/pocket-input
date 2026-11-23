# PocketInput 🖱⌨

Turn your Android phone into a Bluetooth HID mouse and keyboard for Windows — no app to install on the PC, no drivers, works in BIOS/UAC/login screen.

[![Kotlin](https://img.shields.io/badge/Kotlin-2.0-7F52FF?logo=kotlin)](https://kotlinlang.org)
[![API](https://img.shields.io/badge/API%2028+-44CC11?logo=android)](https://developer.android.com)
[![License](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)

---

## Features

| | |
|---|---|
| **Trackpad** | Tap, drag & drop, right-click (long press), two-finger scroll, pinch zoom, edge auto-move while dragging |
| **Keyboard** | Powered by your phone's IME — swipe, autocorrect, any language. Backspace, Enter, Tab, Esc, arrow keys, all via HID |
| **Gesture tuning** | Adjust double-tap window, hold-to-drag delay, long-press threshold, tap slop — all from settings |
| **Diagnostics** | See your report rate (Hz), queue delay, send failures, connection history — measure, don't guess |
| **Scroll** | Invert, adjust gain, fine-grained control |
| **Modifiers** | Tap Ctrl/Shift/Alt/Win to arm for the next key, double-tap to latch |
| **Themes** | Light and dark |
| **Zero-install on PC** | Standard Bluetooth HID — Windows sees a keyboard + mouse, no drivers, works everywhere |

## How it works

PocketInput uses Android's [`BluetoothHidDevice`](https://developer.android.com/reference/android/bluetooth/BluetoothHidDevice) API (API 28+) to register as a composite HID device. Your PC sees a standard Bluetooth keyboard and mouse.

```
Phone touch → gesture machine → ordered HID queue → Bluetooth sendReport → Windows RAWINPUT
```

The gesture recogniser is a **pure Kotlin state machine** (JVM-tested, no Android dependency). The UI thread only enqueues deltas — the actual send runs on a separate `ScheduledThreadPoolExecutor`, so a held key never blocks cursor motion.

### Design highlights

- **Ordered queue with scheduling.** A click is press → ~40 ms pause → release — scheduled as deadlines on a serial executor, so cursor motion keeps flowing during the pause.
- **Coalescing.** Adjacent motion deltas merge before the 125 Hz send tick; button events between them are preserved.
- **Historical sample replay.** The gesture machine used to receive one touch position per frame (~60 Hz). It now reads `PointerInputChange.historical` to recover the panel's full 120–480 Hz sample rate.
- **Pure gesture state machine.** `IDLE / TAP / POINTER / DRAG / TWO_FINGER / SWIPE` modes, 32+ JVM tests.

## Requirements

- **Phone:** Android 9 (API 28)+ with Bluetooth LE
- **PC:** Windows 8.1+ with Bluetooth (a USB dongle works too)
- **Pairing:** Start from the PC — "Add Bluetooth device" while the app shows the connection screen

> **Cyrillic / non-Latin text** — HID sends physical key positions, not letters. Cyrillic works when the PC has the matching layout active. Full Unicode input is planned for the PC agent (phase 2).

## Building

```bash
export JAVA_HOME="path/to/jdk-21"
export ANDROID_HOME="path/to/android-sdk"

# Build and test
cd android
./gradlew :app:assembleDebug :app:testDebugUnitTest
```

Debug APK: `android/app/build/outputs/apk/debug/app-debug.apk`

Requires:
- JDK 21
- Android SDK 34 (`platforms;android-34`, `build-tools;34.0.0`)
- Gradle 8.10

## Measuring latency on the PC

`tools/hid_latency.py` captures RAWINPUT events and reports:

- Report rate (Hz)
- Median / p95 / p99 interval
- Stall/pause breakdown (intervals >1 s are counted as finger pauses)
- Full CSV export

```bash
python tools/hid_latency.py --seconds 30 --csv out.csv
```

## Roadmap

| Phase | What | Status |
|---|---|---|
| **0** | Bluetooth HID registration, basic mouse + keyboard | ✅ Done |
| **1** | Full trackpad + keyboard app UI, IME integration, gesture settings, diagnostics | ✅ Done |
| **2** | PC agent with Unicode injection and screen-element snapping | 📋 Planned |
| **3** | IMU laser pointer with touch calibration | 📋 Planned |
| **4** | ARCore 6DoF pointer | 📋 Planned |

## Project structure

```
pocket-input/
├── android/          # Android app (Kotlin + Compose)
├── agent/            # PC agent (planned)
├── proto/            # Protocol buffers (planned)
├── spec/             # Design specification (Russian)
├── docs/             # Measurement reports, design docs
└── tools/            # Windows utilities:
    ├── hid_latency.py    # RAWINPUT latency measurement
    └── pc-power-*.ps1    # Windows power tuning for Bluetooth HID
```

## License

MIT
