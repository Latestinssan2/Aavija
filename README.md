# Aavija (GestureShare)

Hand-gesture file sharing between an Android phone and a Mac on the same Wi-Fi.

- **Grab** (open palm -> closed fist) picks up the selected file.
- **Release** (closed fist -> open palm) drops it on the other device.
- If both happen within ~10 seconds on two **paired** devices, the transfer starts.

## Layout

| Folder     | What it is                                   |
|------------|----------------------------------------------|
| `/android` | Kotlin + Jetpack Compose sender/receiver app |
| `/mac`     | Swift + SwiftUI menu-bar app (no Dock icon)  |
| `/docs`    | `PROTOCOL.md` — the network protocol in plain words |

## Phases

1. Menu-bar app advertises over Bonjour; Android discovers it. ✅
2. Pairing (6-digit PIN, certificate fingerprints stored both sides). ✅
3. Discovery of Macs on the phone + tap-to-pair flow. ✅
4. Button-based file transfer with SHA-256 verification:
   Mac → phone ✅, phone → Mac ✅ (`~/Downloads/GestureShare` on the Mac,
   `Downloads/GestureShare` on the phone).
5. Gesture detection on Android (CameraX + MediaPipe Hand Landmarker,
   10-fps sampling, 5-frame smoothing, OPEN/FIST state machine, GRAB/RELEASE
   with a minimum hold time). ✅ (camera runs only while Gesture mode is ON,
   default OFF; no frames leave the device)
6. Gesture detection on Mac (Vision).
7. Connect gestures to transfers (10s grab/release window).
8. Animation overlay (orb / particles / spring-unfold).
9. Optional: Android foreground service, Mac background mode.
