# Aavija Windows client (native)

Status: scaffold + native screenshot capture (GDI). Networking, PIN pairing,
TLS, mDNS discovery, and MediaPipe hand tracking are the next steps.

## Native screenshot capture
`ScreenshotCapture.cs` uses `user32`/`gdi32` (`GetSystemMetrics`, `PrintWindow`,
`BitBlt`) to capture the whole desktop or the foreground window without any
third-party dependency. A future revision will replace this with the Windows
Graphics Capture (WGC) API for hardware-accelerated and per-window capture,
matching the macOS ScreenCaptureKit path.

## Planned next steps
1. mDNS browse/advertise `_gestureshare._tcp` and `_aavija-mac._tcp`.
2. TLS listener/client using `System.Net.Security.SslStream` with the same
   self-signed certificate model as Android/macOS.
3. Reuse the JSON line protocol (`challenge`, `pair`, `auth`, `ping`/`pong`,
   `receive`, `file`).
4. MediaPipe hand landmarking via `Mediapipe.Net` for GRAB/RELEASE.
5. Port `PeerGestureMatcher` and the 10 s match window.
6. Foreground/background capture via a Windows service or tray app.
