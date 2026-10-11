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
- **Who starts it (Phase 5b):** the phone cannot dial the Mac (it's the
  listener), so the Mac cannot be told to begin by anything it receives
  passively. Instead the Mac **polls** the phone every ~2 s over the same TLS
  tunnel with a `{"type":"poll","name":"<machine name>"}` line. The phone
  answers `{"type":"poll","action":"pair"}` only if the user tapped that exact
  name in the phone's device list; everyone else gets `"wait"`. On `"pair"` the
  Mac generates a PIN, displays it, and starts the normal retry loop.
- **Attempt accounting (Phase 5b):** the phone verifies a PIN **only while the
  user's Submit press is armed**. Between Submits it answers
  `rejected / "no pin entered"`, which costs nothing. Without this the Mac's
  ~1.5 s retry loop would spend all five attempts in about seven seconds while
  the user was still reading the PIN. One Submit = one attempt.
- **Selection filter:** if the user has picked a device, a `pair` message whose
  `name` doesn't match is refused with `rejected / "not the selected device"`
  and burns no attempt. With nothing picked, any Mac may still pair (that's the
  Mac-side "Pair" button's fallback path).
- Wrong PIN: allowed **5 attempts**, then lockout for 5 minutes. The lockout is
  persisted, so force-quitting the app does not reset it. The phone shows how
  many attempts are left and buzzes on each wrong guess.
- After a successful pairing, each device stores the other's **certificate
  fingerprint** (a short hash of the TLS certificate = "identity card").
- The fingerprint must come from the **same certificate the TLS server actually
  presents**. Each app has one shared identity; never mint one certificate for
  the listening socket and a different one for hashing — the two sides would then
  disagree and every PIN would look wrong.
- Pairing is written to disk on **both** sides (phone: the token set + the paired
  Mac's name; Mac: pinned fingerprint + token), so closing or minimizing the app
  never loses it. The phone re-derives "paired" from the saved token at startup
  instead of holding it only in memory — otherwise a restart *looked* like the
  connection had been dropped.
- The Mac sends the same machine-name string in all three places it appears —
  mDNS instance name, `poll`, and `pair` — because the phone matches them
  against the row the user tapped. They must not drift apart.
- Later connections are accepted **only** if the fingerprint matches the stored one.
- Unpaired devices are rejected **before any file bytes** are accepted.
- A gesture never causes a file to be accepted from an unknown device.

### Discovery of Macs (Phase 5a/5b)

- The Mac announces itself as `_aavija-mac._tcp` with `DNSServiceRegister` —
  **not** `NWListener`, which is broken on this machine. Registering asks
  `mDNSResponder` to broadcast UDP and opens no listening socket at all.
- The phone browses that type with `NsdManager`. It adds each Mac to its list
  straight from `onServiceFound` and deliberately never calls `resolveService`:
  resolving only learns *where* to dial, this phone never dials a Mac, and it
  is by far the most failure-prone NsdManager call.
- The phone's list is sorted by name and rows are keyed by instance name, so it
  does not reshuffle under the user's finger when another device appears.


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
- Phase 5b: Mac discovery (`_aavija-mac._tcp`, no `resolveService`), tap-to-select
  device list, Mac polls the phone for a "show a PIN" signal, Submit-gated
  verification, persisted lockout with a live countdown, wrong-PIN vibration.
- Fix: pairing now survives closing/minimizing the app. The phone reloads
  "paired" from the saved token on startup and remembers the paired Mac's name;
  the Mac shows "Paired ✓" on a fresh launch instead of looking disconnected.
