# આવી જા (Aavija) — Gesture AirDrop

![Downloads](https://img.shields.io/github/downloads/Latestinssan2/Aavija/total?label=downloads)

> **Native Hand-Gesture File & Screenshot Sharing between macOS and Android over Local Wi-Fi.**  
> Zero cloud relay • Sub-millisecond peer-to-peer • TLS 1.3 encrypted • 100% Native



https://github.com/user-attachments/assets/56cc402f-568d-458c-9624-f69b874099e6



---

## 📦 Downloads (v1.2.0)

| Platform | Format | Download Link | Requirements |
| :--- | :--- | :--- | :--- |
| **macOS** (Apple Silicon / Intel) | `.dmg` | [Download Aavija-macOS.dmg](https://github.com/Latestinssan2/Aavija/releases/download/v1.2.0/Aavija-macOS.dmg) | macOS 14.0 Sonoma or later |
| **macOS** (Archive) | `.app.zip` | [Download Aavija-macOS.app.zip](https://github.com/Latestinssan2/Aavija/releases/download/v1.2.0/Aavija-macOS.app.zip) | macOS 14.0 Sonoma or later |
| **Android** | `.apk` | [Download Aavija.apk](https://github.com/Latestinssan2/Aavija/releases/download/v1.2.0/Aavija.apk) | Android 10.0 (API 29) or later |
| **Windows** | `.msix` / `.exe` | [Microsoft Store](https://apps.microsoft.com/detail/9PHCR9F31J06) · [Download .msix](https://github.com/Latestinssan2/Aavija/releases/download/v1.2.0/Aavija-windows.msix) | Windows 10 1903 or later |

---

## ✨ Features

- **Mid-Air Hand Gestures**:
  - **GRAB**: Close your fist in front of your Mac or phone camera to hold a file or screenshot.
  - **RELEASE**: Open your palm on the other device within 10 seconds to catch and save it.
  - On-device computer vision (Apple Vision framework on macOS; Google MediaPipe on Android) sampling at 10 fps. Camera runs only while Gesture mode is active. No frames are ever recorded or transmitted.
- **Screen & Active App Sharing**:
  - **Entire Screen**: Capture your full desktop display.
  - **Active App**: Isolate and capture only the frontmost active window with Retina resolution using ScreenCaptureKit.
- **Edge-to-Edge Fluid Photo Viewer**:
  - Receives photos and unfolds them in full-screen mode without borders or card halos.
  - Interactive multi-touch **Pinch to Zoom (1x to 5x)** and pan.
  - **Double-tap** to zoom/reset.
  - Swipe down to dismiss.
  - Floating actions: *"આવતું છે"*, *"Open in Photos"*, and *"Done"*.
- **Windows Native Client (beta)**:
  - Native WPF app with PIN pairing, file push/pull, and screenshot capture.
  - Gesture detection via MediaPipe sidecar (native C++ MediaPipe DLL in progress).
- **Magic Blackout Screen Mode**:
  - Completely blacks out the Android screen and hides status/navigation bars.
  - Background gesture camera stays actively tracking hands even while the screen remains pitch black.
- **Zero-Trust Local Security**:
  - 6-digit challenge-response PIN authentication.
  - TLS 1.3 self-signed certificates with SHA-256 fingerprint pinning.
  - Path traversal protection (writes strictly inside `Downloads/Aavija` / `GestureShare`).
  - 200 MB maximum transfer limit per payload.
- **Audio & Visual Feedback**:
  - Shutter + chime sound on capture.
  - Notch Eye HUD near the MacBook notch closing on GRAB and opening on RELEASE.
  - Glowing transfer orb with Gujarati status text (*"જતું છે"* on Mac, *"આવતું છે"* on Android).

---

## 📐 Project Architecture

```
Aavija/
├── android/                 # Native Android App (Kotlin + Jetpack Compose)
│   ├── app/src/main/java/com/aartiq/aavija/
│   │   ├── MainActivity.kt        # UI, Themes, Magic Blackout Mode
│   │   ├── PhotoViewer.kt         # Edge-to-edge pinch-to-zoom viewer
│   │   ├── GestureCamera.kt       # MediaPipe HandLandmarker & CameraX
│   │   ├── BonjourAdvertiser.kt   # Local NsdManager server & TLS socket
│   │   └── MacDiscovery.kt        # mDNS browser for macOS services
│   └── app/build.gradle.kts
├── mac/                     # Native macOS App (Swift + SwiftUI)
│   ├── Aavija/
│   │   ├── AavijaApp.swift        # Menu-bar entry point & lifecycle
│   │   ├── ContentView.swift      # Menu-bar popover & controls
│   │   ├── GestureEyeHUD.swift    # Notch Eye HUD overlay
│   │   ├── MacScreenshot.swift    # ScreenCaptureKit (Screen & Active App)
│   │   ├── HandCamera.swift       # AVFoundation + Apple Vision tracking
│   │   ├── Connector.swift        # TLS client & pairing state machine
│   │   └── MacAdvertiser.swift    # mDNS announcement via DNSServiceRegister
│   └── project.yml                # XcodeGen specification
└── web/                     # Official Website (HTML5, Tailwind CSS, Vercel)
    ├── index.html                 # Animated landing page with live simulator
    ├── logo-wide.png              # Wide brand wordmark
    └── vercel.json                # Vercel deployment configuration
```

---

##  Building From Source

### Android (APK)
```bash
cd android
./gradlew assembleDebug
# APK generated at android/app/build/outputs/apk/debug/app-debug.apk
```

### macOS (.app & .dmg)
```bash
brew install xcodegen
cd mac
xcodegen generate --spec project.yml
xcodebuild -project Aavija.xcodeproj -scheme Aavija -configuration Debug build
```

---

## Developer & Credits

Developed and maintained by **Latestinssan**.
- GitHub: [Latestinssan (@Latestinssan2)](https://github.com/Latestinssan2)

Licensed under the MIT License.
