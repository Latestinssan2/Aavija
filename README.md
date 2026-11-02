# Aavija (GestureShare)

Hand-gesture file sharing between an Android phone and a Mac on the same Wi-Fi.

- **Grab** (open palm -> closed fist) picks up the selected file.
- **Release** (closed fist -> open palm) drops it on the other device.
- If both happen within ~10 seconds on two **paired** devices, the transfer starts.
- **Grab on the Mac with nothing held** takes a screenshot instead — with a
  Liquid Glass capture effect — and holds it. You can close your hand and hold
  it, or raise an already-closed fist: no need to open first. Release on the
  phone and the screenshot is sent there.
- **A received photo opens by itself** in a full-screen viewer on the phone,
  with a spring-unfold entrance and a one-tap "Open in Photos" button.
- **Custom sound effects** play on both platforms: a shutter + glass chime when
  the Mac captures the screen, an airy whoosh when a transfer starts, and a
  warm two-note chime when it finishes successfully.

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
6. Gesture detection on Mac (AVFoundation + Vision human hand pose, same
   10-fps sampling and 5-frame OPEN/FIST state machine as Android). ✅
   (camera runs only while Gesture mode is ON, default OFF; no frames leave
   the Mac)
7. Connect gestures to transfers (10s grab/release window). ✅
   - Either device's GRAB and the other device's RELEASE within 10 s trigger a
     transfer (phone→Mac pull or Mac→phone push). 
   - In Gesture mode the file queues wait for a gesture (not auto-pulled); the
     Mac can hold a file for gesture release. Heartbeat tightens to 1.5 s while
     gesture mode is on.
   - With nothing held, a Mac GRAB captures the screen (ScreenCaptureKit) and
     stages the PNG in memory; the phone's release sends it. Capturing shows a
     full-screen Liquid Glass effect and works on macOS 14+ (real `glassEffect`
     on macOS 26+).
   - On the phone, a received image opens immediately in an in-app viewer with
     a spring/unfold animation; the file still lands in Downloads/GestureShare.
   - Synthesized sound effects (bundled WAVs, no downloads): capture shutter +
     glass chime, transfer whoosh, and success chime.
8. Animation overlay (orb / particles / spring-unfold). ✅
   - Both platforms show a self-contained orb with orbiting particles, only
     while bytes are actually moving. No images or external animation library.
9. Optional: Android foreground service, Mac background mode.

## Permissions you may be asked for (macOS)

- **Camera** — only while Gesture mode is ON.
- **Screen & System Audio Recording** — the first time you GRAB with no file
  held. Aavija needs it to take the screenshot you are about to share. Denying
  it is safe: nothing else breaks, and the panel offers a shortcut to the
  setting.

Because the Debug build is ad-hoc signed, a rebuild can make macOS forget a
permission. To reset only the screen-capture decision:

```sh
tccutil reset ScreenCapture com.preet.aavija.mac
```
