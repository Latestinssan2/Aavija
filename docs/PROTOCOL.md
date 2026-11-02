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
- Later connections are accepted **only** if the Mac presents the **session
  token** the phone minted at pairing — not the fingerprint. The phone mints a
  fresh self-signed certificate each app run, so a pinned fingerprint goes
  stale; the token lives in storage on both sides and never crosses the network
  except inside this TLS tunnel. The fingerprint's job is narrower: it ties the
  PIN exchange to the specific socket at pairing time, so the token can't be
  handed to a machine-in-the-middle.
- Unpaired devices are rejected **before any file bytes** are accepted.
- A gesture never causes a file to be accepted from an unknown device.

### Is the phone actually there? (heartbeat)

- A stored pairing is **trust**, not a live link. The two must not be
  conflated: with the phone's Wi-Fi off, nothing can be transferred, yet a
  record saying "paired" is still on disk and is still true.
- So the Mac decides "connected" from a real round-trip, not from the record.
  While paired it dials the phone every ~3 s and sends `{"type":"ping"}`.
- The phone answers `{"type":"pong"}`. It verifies nothing, changes nothing,
  and burns no PIN attempt — it only proves the phone is on the Wi-Fi and
  speaking our protocol.
- From Phase 7 the ping carries `{"type":"ping","token":"<hex>"}`. The token is
  the same one minted at pairing, so the phone can tell a paired Mac from a
  stranger. A pong to a valid token adds `gestureGate` (Gesture mode is ON, so
  queued files must wait for a handshake), and `gesture` / `gestureId` /
  `gestureAgeMs` (the phone's latest GRAB or RELEASE). The age is measured on
  the phone's clock, so the two devices never need synchronised clocks.
- A ping without a valid token is still answered with `pong` (so reachability
  checking keeps working), but it carries no gesture fields — gestures are
  private. An older phone simply omits the fields and keeps Phase 4b behaviour.
- Phase 8 adds no wire messages: the orb/spinner is purely local UI, shown while
  an already-authorized transfer is in flight.
- The panel says **"Connected to \<phone\>"** only after a pong, and
  **"Disconnected — \<phone\> isn't reachable"** when pings stop (phone off the
  Wi-Fi, powered down, or the app closed).
- The phone's name is remembered at pairing time so the panel can name it even
  while it's offline.

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

- One TCP connection wrapped in **TLS** (encrypted). The Mac always dials (the
  phone is the listener), so a send is a **push** from the Mac.
- Before a single file byte, the Mac must prove it is the paired device:
  1. Phone → Mac: `{"type":"challenge","salt":"<hex>"}` (the phone always speaks
     first, on every connection).
  2. Mac → Phone: `{"type":"auth","token":"<hex>"}` — the 256-bit session token
     minted during pairing. It lives only on the two devices.
  3. Phone → Mac: `{"type":"authenticated"}`, or
     `{"type":"rejected","reason":"…"}` and the phone drops the connection.
     **No file bytes are read before this.**
- Then the sender sends a small **JSON header**:
  `{"type":"file","filename":"a.jpg","size":1234,"sha256":"<hex>"}` + `\n`.
- Then the raw file bytes in **1 MB chunks**. The receiver reads whatever the
  socket actually has (up to 64 KB at a time), so a slow sender still progresses.
- Receiver computes SHA-256 while writing. On a match it keeps the file and
  answers `{"type":"received"}`. On a mismatch it **deletes the file** and
  answers `{"type":"failed","reason":"hash mismatch"}`.
- The header line and the body share **one** buffered stream, read strictly in
  order: the line reader must stop exactly at `\n`, or it would swallow the
  first bytes of the file.

### Phone → Mac (Phase 4b)

The Mac still dials and still authenticates first; only the direction of the
file changes:

1. The phone's `pong` carries `"outgoing": <n>` — how many files are queued on
   the phone. The Mac sees a non-zero count on its heartbeat and dials.
2. TLS + `challenge` / `auth` / `authenticated` exactly as above (no bytes
   before this point).
3. Mac → phone: `{"type":"receive"}`.
4. Phone answers either `{"type":"idle"}` (nothing queued) or a normal `file`
   header followed by the bytes and the SHA-256 they were hashed with.
5. The Mac verifies the hash **before** writing, then saves to
   `~/Downloads/GestureShare/<name>` (atomic write). A bad hash is discarded and
   reported in the menu-bar panel; nothing is left on disk.
6. A failed send leaves the file in the phone's queue, so the next heartbeat
   retries it — a transfer is only removed from the queue after the bytes were
   sent successfully.


## 4. Safety rules

- Filenames containing `../`, path separators (`/`, `\`), control characters, or
  empty names are rejected outright (not "cleaned up").
- Files are saved only into a dedicated folder: `Downloads/GestureShare/`.
  Android 10+ writes it through **MediaStore** (no storage permission, and the
  partial row is deleted when a transfer fails); older Android writes the plain
  path under the storage permission. On the Mac the folder is
  `~/Downloads/GestureShare/`, and the sandbox entitlement is limited to
  `files.downloads.read-write` (plus read-only access to a file the user picked).
- Max file size: **200 MB**. Both sides refuse an oversized header before
  reading any bytes, so the user gets a clear message on either device.
- The phone accepts a `receive` request only on an **already authenticated**
  connection, so an unpaired Mac can never pull a queued file.
- No plaintext fallback — if TLS can't be done on a platform, we stop and
  redesign; we never silently downgrade.

## 5. Gestures

- Camera runs only when **Gesture mode** is ON (default OFF).
- Android: CameraX + MediaPipe Hand Landmarker.
- Mac: AVFoundation + Vision `VNDetectHumanHandPoseRequest`.
- State machine `OPEN <-> FIST`, ~5-frame smoothing + minimum hold time.
- GRAB (open→fist) and RELEASE (fist→open) are matched **across the two
  devices**: one side grabs, the other releases, within **10 s**. Each event
  carries a monotonic id and an age measured on the sender's clock, so no clock
  sync is needed.
- The **Mac drives the transfer**, because it is the only side that can dial:
  - phone grabbed → Mac released: the Mac pulls the phone's queued file.
  - Mac grabbed → phone released: the Mac pushes the file it is holding.
- While Gesture mode is ON on the phone, its queue is not auto-pulled just
  because it exists (`gestureGate`); it waits for the handshake. Turning Gesture
  mode off restores the Phase 4b "pull as soon as queued" behaviour.
- On the Mac, "Hold a file for gesture…" selects a file without sending it. The
  bytes stay in memory (max 200 MB) and are cleared when the file is delivered,
  gesture mode is turned off, or the devices are unpaired.
- The heartbeat tightens from 3 s to 1.5 s while gesture mode runs on either
  side, so the 10 s window is not mostly spent in transit.
- A gesture never bypasses authentication: it only changes WHEN an already
  authorized transfer starts.
- Unmatched gestures simply expire; a late release does nothing.

## Changelog

- Phase 1: initial draft (discovery only).
- Phase 5b: Mac discovery (`_aavija-mac._tcp`, no `resolveService`), tap-to-select
  device list, Mac polls the phone for a "show a PIN" signal, Submit-gated
  verification, persisted lockout with a live countdown, wrong-PIN vibration.
- Fix: pairing now survives closing/minimizing the app. The phone reloads
  "paired" from the saved token on startup and remembers the paired Mac's name;
  the Mac shows "Paired ✓" on a fresh launch instead of looking disconnected.
- Phase 4a: **Mac → phone file transfer.** A "Send file to phone…" button opens
  the Mac's file picker and pushes the file over the pairing TLS tunnel: token
  `auth` first, then a JSON header + 1 MB chunks, SHA-256 checked as the bytes
  land, saved to `Downloads/GestureShare` through MediaStore, and deleted if the
  hash doesn't match. 200 MB cap. 14 new JVM tests cover the safety rules.
- Fix: "Paired" no longer pretends the phone is reachable. The Mac heartbeats
  (`ping`/`pong`) and shows "Connected to <phone>" vs "Disconnected". Also fixed
  the Send freeze — the file picker is now async, the file is read off the main
  thread, and the sandbox security scope is opened before reading.
- Phase 4b: **phone → Mac file transfer.** The phone's `pong` now reports its
  outgoing queue length; when it is non-zero the Mac dials, authenticates, sends
  `receive`, and the phone answers `idle` or a `file` header + 1 MB chunks. The
  Mac verifies SHA-256 before writing to `~/Downloads/GestureShare/` and reports
  the result in the menu-bar panel. A failed send stays queued and is retried on
  the next heartbeat; the Mac guards against overlapping pulls.
- Phase 7: **gesture-driven transfers.** The phone's latest GRAB/RELEASE ride on
  `pong` with an age and an id; the Mac matches them with its own gestures in a
  10 s window. Phone grab + Mac release pulls the queued file; Mac grab + phone
  release pushes a held file. `gestureGate` stops the previous auto-pull while
  the phone is in Gesture mode, and the heartbeat halves to 1.5 s.
