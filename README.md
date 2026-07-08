# DNicheLooper

Real-time guitar **amp-model + cabinet-IR + looper** for Android, running entirely
on the phone as the DSP host (like a PiPedal Raspberry Pi). Audio goes in and out
through **one USB audio interface** — the phone's built-in audio is never used.

This is the Android sister project of the desktop NicheLooper; the signal flow and
slot concept (switchable amp A/B/C + a fixed cab IR D + looper) match the desktop
app, but the engine is a native Oboe full-duplex implementation for Android.

> ✅ Verified on-device: the engine starts on a connected USB interface and passes
> the audio sound check on a Pixel 9.

## Signal flow

```
Guitar → NAM amp slot (A/B/C, switchable) → Cab IR (Slot D, fixed/global)
       → Looper → Monitor → USB out
```

Slot D is deliberately **not** part of the A/B/C switching: the amp changes, the
cab stays. An empty Slot D is a bypass.

## Install (sideload from GitHub)

1. Download the latest `DNicheLooper.apk` from
   [Releases](https://github.com/TheBigSchabowski/DNicheLooper/releases/latest).
2. On the phone: **Settings → Apps → Special access → Install unknown apps**
   (allow your browser / Files app).
3. Open the downloaded `.apk` and install.
4. Plug in a USB audio interface, open DNicheLooper, grant the recording
   permission — the engine starts on the interface.

Requires Android 8.0 (API 26) or newer; tested on a Pixel 9.

## Hardware / setup notes

- Both input and output must use the **same USB interface** (explicit device ids).
- On USB disconnect the engine stops — there is no fallback to built-in audio.
- The app maxes the media volume on engine start (Android throttles the USB DAC
  to the media volume set at connect time).

## Credits & third-party licenses

DNicheLooper bundles third-party components whose licenses require attribution:

- **NeuralAmpModelerCore** — © 2023 Steven Atkinson, **MIT License**
  (amp-model DSP). [NeuralAmpModelerCore](https://github.com/sdatkinson/NeuralAmpModelerCore)
- **Oboe** — Google, **Apache License 2.0** (low-latency audio).
- **Eigen** — MPL2 / BSD / Apache 2.0 (linear algebra, used by the IR loader).

Drum samples (optional rhythm section) come from the **"The Black Pearl 1.0"**
Hydrogen drumkit by Glen MacArthur (AVL Drumkits), licensed under the **GPL**.
See `app/src/main/assets/drums/ATTRIBUTION.txt`.

> ℹ️ License-sensitive mentions (NAM, the desktop looper, etc.) are kept here as
> legally-required attribution only. Adjust marketing mentions to taste.

## Build

Android Studio / Gradle (AGP 9, Gradle 9.4, Kotlin 2.2). The NAM core is a git
submodule:

```bash
git submodule update --init --recursive
./gradlew :app:assembleDebug
```

Debug APK: `app/build/outputs/apk/debug/app-debug.apk`.
