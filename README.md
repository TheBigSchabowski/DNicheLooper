# DNicheLooper

Real-time guitar **amp-model + cabinet-IR + looper** for Android, running entirely
on the phone as the DSP host (like a PiPedal Raspberry Pi). Audio normally goes in
and out through **one USB audio interface**; since 0.2.0 the phone's own mic and
speaker can be used instead, for practising without carrying hardware.

This is the Android sister project of the desktop NicheLooper; the signal flow and
slot concept (switchable amp A/B/C + a fixed cab IR D + looper) match the desktop
app, but the engine is a native Oboe full-duplex implementation for Android. The
rhythm section is the same code as the desktop app — 17 grooves across 4/4, 3/4,
2/4 and 6/8.

> ✅ Verified on-device: the engine starts on a connected USB interface and passes
> the audio sound check on a Pixel 9.
>
> ⚠️ Phone-audio mode and the ported rhythm section are **built and compiling but
> not yet tested on a device.**

## Signal flow

```
Guitar → NAM amp slot (A/B/C, switchable) → Cab IR (Slot D, fixed/global)
       → Looper → Monitor → USB out (or phone output)
```

Slot D is deliberately **not** part of the A/B/C switching: the amp changes, the
cab stays. An empty Slot D is a bypass.

## Rhythm section

A metronome and a sample-based drum machine share one bar clock, so the looper,
the click and the drums never drift apart.

| Meter | Grooves |
|---|---|
| 4/4 | Rock, Pop, Straight 16, Funk, Ballad, Four on the Floor, Shuffle, Bossa |
| 3/4 | Waltz, Jazz Waltz, Waltz Ballad |
| 2/4 | March, Polka, 2/4 Rock |
| 6/8 | 6/8 Basic, 6/8 Blues, 6/8 March |

Kick, snare and hats each have their own accent mask: a hit in the pattern but
not in the accent mask plays soft. That is what gives Funk its ghost snares and
lets the Bossa clave read as a rim click instead of a backbeat.

**Mute** silences the drums without stopping the bar clock, so unmuting drops
straight back into the groove in time — a live drop-out for a bar or two.

Switching the meter moves to that meter's first groove automatically, because a
groove is written for one specific bar length.

## Phone audio (no interface)

When no interface is connected, the **Phone audio** switch runs the engine on the
phone itself: built-in mic in, whatever Android is currently routing out.

Both output paths are allowed on purpose:

- **Headphones** — no acoustic path back to the mic, so this behaves normally.
- **Speaker** — the mic sits centimetres from the speaker, so monitoring and loop
  playback *can* feed back. That is physics, not a bug. Turn the volume down, or
  switch monitoring off and play acoustically over the drums and loops.

The app will not raise the volume for you in this mode (unlike USB, where maxing
the media volume is required to get full scale out of the DAC).

A connected interface always wins — the switch only appears when none is present.

## Install (sideload from GitHub)

1. Download the latest `DNicheLooper.apk` from
   [Releases](https://github.com/TheBigSchabowski/DNicheLooper/releases/latest).
2. On the phone: **Settings → Apps → Special access → Install unknown apps**
   (allow your browser / Files app).
3. Open the downloaded `.apk` and install.
4. Plug in a USB audio interface, open DNicheLooper, grant the recording
   permission — the engine starts on the interface. Without an interface, turn on
   **Phone audio** to run on the built-in mic instead.

Requires Android 8.0 (API 26) or newer; tested on a Pixel 9.

## Hardware / setup notes

- With an interface, both input and output must use the **same** one (explicit
  device ids).
- On USB disconnect the engine stops. It does not silently move to the phone's
  speaker — dropping a live looper onto an open mic would howl — so switching to
  phone audio is an explicit tap.
- The app maxes the media volume on engine start **on USB only** (Android
  throttles the USB DAC to the media volume set at connect time). In phone-audio
  mode the volume is left alone.

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
