# Changelog

## [1.2.0] - 2026-11-08

### Added
- Unified mid-air camera gesture mode with photo & file beam selector.
- Hands-free gesture sharing: ✊ Fist to lock file, 🖐 Open palm to beam directly to paired devices.
- Background & minimized reception: automatic high-priority wake with Gujarati "આવતું છે" (Aavtu Chhe) living quantum orb animation unfolding into received media viewer.
- Google Sign-In with Firebase Authentication integration and verified identity badge.
- Permanent release signing configuration and automated CI release workflow.
- Dynamic app version synchronization across Settings and About views.

### Changed
- Bumped unified version to 1.2.0 across Android, macOS, and Windows.
- Minimal home header with official settings icon.

## [1.1.0] - 2026-11-07

### Added
- Android foreground service for background gesture scanning.
- Android screenshot capture on GRAB and share on RELEASE.
- Native splash screen with system-aware light/dark theme.
- English and Gujarati localization.
- Black screen mode home-screen widget shortcut.
- Windows native WPF client (pairing, file sharing, screenshots).
- Windows listener mode for desktop-to-desktop and phone-to-Windows sharing.
- Native C++ MediaPipe hand-tracker scaffold and P/Invoke wrapper.
- GitHub Actions CI builds for Android, macOS, Windows (.exe and .msix).
- Updated landing page with Windows support.

### Changed
- Simplified in-app wording and startup guidance.
- Unified app version to 1.0.0 across Android, macOS, and Windows.

### Known limitations
- Windows gesture tracking uses a Python/MediaPipe sidecar; native DLL integration is in progress.
- macOS cannot run as a listener; Mac↔Mac direct sharing is not supported.
