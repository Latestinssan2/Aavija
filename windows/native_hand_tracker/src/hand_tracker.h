#pragma once

#ifdef AAVIJA_TRACKER_EXPORTS
#define AAVIJA_API __declspec(dllexport)
#else
#define AAVIJA_API __declspec(dllimport)
#endif

extern "C" {
AAVIJA_API void* CreateTracker(const char* modelPath);
AAVIJA_API int ProcessFrame(void* handle, const unsigned char* rgb, int width, int height, long nowMs);
AAVIJA_API void DestroyTracker(void* handle);
}
