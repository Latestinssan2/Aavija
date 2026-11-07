# Contributing to Aavija

Thanks for your interest in contributing.

## Development setup
- Android: Android Studio, JDK 17, Gradle.
- macOS: Xcode 15+, XcodeGen.
- Windows: Visual Studio 2022, .NET 8 SDK.

## Build
- Android: `cd android && ./gradlew assembleDebug`
- macOS: `cd mac && xcodegen generate --spec project.yml && xcodebuild ...`
- Windows: `dotnet build windows/AavijaWindows/AavijaWindows.csproj`

## Pull requests
- Keep changes focused and tested.
- Update CHANGELOG.md for user-facing changes.
- Follow existing code style.

## Issues
- Report bugs with reproduction steps and device/OS versions.
