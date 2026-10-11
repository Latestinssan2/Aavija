# GestureShare Protocol (draft — updated every phase)

Plain-words description of how two devices find each other, trust each other,
and move a file. Anything marked **TBD** is decided in a later phase.

## 1. Discovery

- Service type: `_gestureshare._tcp` (mDNS / Bonjour).
- **Android** advertises a service with `ServerSocket` + `NsdManager` (it is the
  listener / server side).
- **Mac** browses for it with `NWBrowser` and connects out with `NWConnection`.
- **Why the swap:** `NWListener` (Mac-as-server) is broken on the user's macOS
  27 machine — it fails with `EINVAL` in every context (app, script, launchd,
  loopback-only) while `NWBrowser`, `NWConnection`, and raw sockets all work.
  Android's networking is unaffected, so the phone is the listener. The Mac
  only needs to browse + dial out, both proven working. File bytes can still
  flow either direction once a connection exists; only who opens the door
  changed.
- The browser side (Mac) shows the advertiser's name (the phone) in a list.
  That's Phase 1.

## 2. Pairing (trust)

- First connection: **Mac shows a 6-digit PIN**, user types it on the phone.
- Wrong PIN: allowed **5 attempts**, then lockout (lockout duration: TBD in Phase 2).
- After a successful pairing, each device stores the other's **certificate
  fingerprint** (a short hash of the TLS certificate = "identity card").
- Later connections are accepted **only** if the fingerprint matches the stored one.
- Unpaired devices are rejected **before any file bytes** are accepted.
- A gesture never causes a file to be accepted from an unknown device.

## 3. Transfer

- One TCP connection wrapped in **TLS** (encrypted).
- Sender first sends a small **JSON header**:
  `filename`, `size`, `sha256` (hash of the whole file).
- Then raw file bytes in **1 MB chunks**.
- Receiver computes SHA-256 while writing. If it doesn't match the header:
  **delete the file**, report failure.

## 4. Safety rules

- Filenames containing `../`, absolute paths (`/...`), or empty names are rejected.
- Files are saved only into a dedicated folder: `Downloads/GestureShare/`.
- Max file size: TBD (Phase 3).
- No plaintext fallback — if TLS can't be done on a platform, we stop and
  redesign; we never silently downgrade.

## 5. Gestures

- Camera runs only when **Gesture mode** is ON (default OFF).
- Android: CameraX + MediaPipe Hand Landmarker.
- Mac: AVFoundation + Vision `VNDetectHumanHandPoseRequest`.
- State machine `OPEN <-> FIST`, ~5-frame smoothing + minimum hold time.
- GRAB (open→fist) on sender stores: selected file + timestamp, and tells the paired peer.
- RELEASE (fist→open) on receiver within **10 s** starts the pull/transfer.
- Timeout / failed transfer: TBD (Phase 6).

## Changelog

- Phase 1: initial draft (discovery only).
