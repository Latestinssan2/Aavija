# Native Windows hand tracker (C++ / MediaPipe Tasks)

This directory contains a scaffold for a native DLL that wraps MediaPipe's
`HandLandmarker` task. The WPF app will P/Invoke into this DLL instead of
relying on the Python sidecar.

## Prerequisites
- Visual Studio 2022 with C++ workload
- MediaPipe source checked out and built (see https://mediapipe.dev)
- CMake 3.20+

## Build
```
cmake -S . -B build -A x64
cmake --build build --config Release
```

The resulting `AavijaHandTracker.dll` exports:
- `CreateTracker(modelPath)` -> handle
- `ProcessFrame(handle, rgbBytes, width, height)` -> int gesture (0=none,1=grab,2=release)
- `DestroyTracker(handle)`

## Status
Scaffold only. MediaPipe integration is pending a local MediaPipe build.
