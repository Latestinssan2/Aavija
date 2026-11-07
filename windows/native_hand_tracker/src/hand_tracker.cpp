#include "hand_tracker.h"

#include <cstdlib>
#include <cstring>
#include <vector>

// MediaPipe Tasks C++ headers (requires a local MediaPipe build).
// #include "mediapipe/tasks/cc/vision/hand_landmarker/hand_landmarker.h"
// #include "mediapipe/tasks/cc/vision/core/image_frame.h"

struct Tracker {
    std::string modelPath;
    // mediapipe::tasks::vision::hand_landmarker::HandLandmarker landmarker;
};

extern "C" {

void* CreateHandLandmarker(const char* model_path) {
    auto* t = new Tracker();
    if (model_path) t->modelPath = model_path;
    // TODO: initialize MediaPipe HandLandmarker with model_path.
    return t;
}

LandmarkerResult DetectHands(void* landmarker_ptr, unsigned char* image_data, int width, int height, int width_step) {
    LandmarkerResult result{};
    result.hand_count = 0;
    result.landmark_count = 21;
    result.x = nullptr;
    result.y = nullptr;
    result.z = nullptr;

    if (!landmarker_ptr || !image_data || width <= 0 || height <= 0) return result;

    auto* t = static_cast<Tracker*>(landmarker_ptr);

    // TODO: run MediaPipe HandLandmarker on image_data (width x height, RGB).
    // Populate result.hand_count and allocate flattened arrays of size
    // hand_count * 21 for x/y/z, then fill them with normalized landmarks.

    return result;
}

void FreeResult(LandmarkerResult result) {
    free(result.x);
    free(result.y);
    free(result.z);
}

void DestroyHandLandmarker(void* landmarker_ptr) {
    delete static_cast<Tracker*>(landmarker_ptr);
}

}
