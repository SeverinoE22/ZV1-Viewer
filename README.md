# ZV1-Viewer

ZV1-Viewer is a lightweight, offline, ad-free Android app built to turn any USB-OTG capable Android phone or tablet into a fast external photo review monitor for the Sony ZV-1 Mark 1 (and potentially other Sony cameras supporting standard USB MTP).

## Features

- **Auto-Launch on USB Connect:** App opens up and shuts down (!) on connecting and disconnecting the required OTG-USB cable to/from the camera, respectively (restart of app is a required workaround for reading the SD card of the ZV-1). Use a cable with an on/off switch for max comfort and long-term protection of connectors.
- **JPEG & Sony RAW (`.ARW`) Support:**
  - **JPEGs** are loaded directly from the camera's SD card and scaled up to max. **2560px** width for sharp focus checks and low memory usage.
  - **Sony RAW (`.ARW`)** files are marked with a `RAW` badge on their thumbnails and load their embedded JPEG preview (1616×1080px) via partial USB transfer for instant viewing without copying huge RAW files.
- **Focus Check & Navigation:**
  - Pinch-to-zoom (up to 6x) and pan to verify critical sharpness.
  - Swipe left/right to browse through photos when unzoomed.
  - Dedicated **Fullscreen** mode to maximize screen space in landscape orientation.
- **Optional Local Backup:** Save the displayed preview (up to 2560px for JPEGs, or the embedded preview as `.jpg` for `.ARW` files) directly to your phone's gallery (`Pictures/ZV1Viewer`) with a single tap on **Save to gallery (2560px)**.
- **Privacy First:** 100% offline. No internet permission, no tracking, no ads.

## Required Sony ZV-1 Camera Settings

Before connecting the USB cable, configure your Sony ZV-1 once:

1. `MENU` → `(Network)` → `[PC Remote Func.]` → `[PC Remote]`: **Off**
2. `MENU` → `(Setup)` → `[USB Connection]`: **MTP**

## Installation

1. Download `app-release.apk` from the **Releases** section.
2. Open the APK on your Android device (Android 7.0 / API 24 or newer) and allow installation from unknown sources if prompted.
3. Connect your Sony ZV-1 via a USB-OTG cable/adapter and **confirm the Android USB permission / data exchange dialog once** (*"Always open..."*).
4. Remember that app must not run when connecting device and camera. For accessing the sd-card the app has to autostart each time the connection is established.

---

*Doesn't work with your device? You can fix it, with or without AI — my programming knowledge is close to zero. ;-)*
