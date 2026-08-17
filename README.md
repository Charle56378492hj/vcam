# VCam — Virtual Camera for Rooted Android Emulators

Inject any image or video into the camera feed of any app on a rooted Android emulator — **no Xposed required**.

## Features

- 🎥 Inject **image or video** as camera source
- 🎯 **Target specific apps** or inject globally
- 🔐 Root-based injection via V4L2 / system commands
- 📱 Works on **rooted Android emulators** (Genymotion, LDPlayer, MuMu, BlueStacks with root, etc.)
- 🚀 Clean Material Design 3 UI
- 🔔 Foreground service with notification controls

## Requirements

- Android 8.0+ (API 26+)
- **Rooted** Android emulator
- Root manager (Magisk recommended)

## How It Works

1. App requests root permission from the system
2. Locates the virtual camera device (`/dev/video0` or v4l2loopback)
3. Injects your image/video frames directly into the camera stream via V4L2 ioctls
4. When a target app opens the camera, it receives the injected feed instead of the real one

## Download APK

See [Releases](../../releases) — built automatically by GitHub Actions.

## Building

```bash
./gradlew assembleDebug
```

APK output: `app/build/outputs/apk/debug/app-debug.apk`

The Windows Bridg build also includes `mss` and `Pillow` for low-latency OBS
monitor capture:

```bash
cd conect_vcam
pip install -r requirements.txt
pyinstaller conect_vcam.spec --noconfirm --clean
```

## Usage

1. Install APK on your rooted emulator
2. Open VCam — grant root permission when prompted
3. Choose **رفع الملفات محلياً** to keep using the existing media workflow, or choose **ربط مع OBS عبر Bridg**
4. For OBS mode, open OBS, connect the phone over USB, enable the link in the OBS Bridge screen, then enter the displayed host/port/token in Bridg
5. Click **Start Stream** in Bridg; the live OBS frames use the existing VCam injection pipeline
6. For local mode, tap **Pick Image** or **Pick Video**, then **Start VCam** as before

## Architecture

```
VCam
├── MainActivity          — UI: media picker, app selector, start/stop
├── MainViewModel         — State management
├── VCamService           — Foreground service (camera injection lifecycle)
├── CameraInjector        — Root + V4L2 injection logic
├── RootManager           — libsu root shell wrapper
├── AppLoader             — Installed app enumeration
└── vcam_native.so        — Native V4L2 frame writer (C++)
```
