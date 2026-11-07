#pragma once

#ifdef EXPORT_API
#define API __declspec(dllexport)
#else
#define API __declspec(dllimport)
#endif

struct LandmarkerResult {
    int hand_count;
    int landmark_count; // Always 21 per hand
    float* x;           // Flattened array of normalized X
    float* y;           // Flattened array of normalized Y
    float* z;           // Flattened array of normalized Z
};

extern "C" {
    API void* CreateHandLandmarker(const char* model_path);
    API LandmarkerResult DetectHands(void* landmarker_ptr, unsigned char* image_data, int width, int height, int width_step);
    API void FreeResult(LandmarkerResult result);
    API void DestroyHandLandmarker(void* landmarker_ptr);
}
