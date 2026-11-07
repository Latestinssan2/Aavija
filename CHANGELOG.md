# Changelog

## [1.0.0] - 2026-11-07

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
