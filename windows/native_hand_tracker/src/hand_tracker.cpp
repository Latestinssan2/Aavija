#include "hand_tracker.h"

#include <memory>
#include <string>
#include <vector>

// MediaPipe Tasks C++ headers (requires a local MediaPipe build).
// #include "mediapipe/tasks/cc/vision/hand_landmarker/hand_landmarker.h"
// #include "mediapipe/tasks/cc/core/base_options.h"
// #include "mediapipe/framework/formats/image.h"

struct Tracker {
    std::string modelPath;
    // mediapipe::tasks::vision::hand_landmarker::HandLandmarker landmarker;
    // Smoothing state (mirrors Android HandGestureMachine).
    int windowSize = 5;
    int minStable = 4;
    long minHoldMs = 500;
    std::vector<int> recent; // 0 unknown, 1 open, 2 fist
    int pose = 0; // 0 unknown, 1 open, 2 fist
    long lastEventAt = 0;
    long lastHandAt = 0;
};

static int Classify(const std::vector<float>& pts) {
    if (pts.size() < 21 * 3) return 0;
    auto dist = [&](int a, int b) {
        float dx = pts[a*3] - pts[b*3];
        float dy = pts[a*3+1] - pts[b*3+1];
        return std::sqrt(dx*dx + dy*dy);
    };
    int wrist = 0;
    int extended = 0;
    int tips[] = {8,12,16,20};
    int pips[] = {6,10,14,18};
    for (int i = 0; i < 4; i++) {
        float tipDist = dist(tips[i], wrist);
        float pipDist = dist(pips[i], wrist);
        if (pipDist <= 0.0001f) continue;
        if (tipDist > pipDist * 1.20f) extended++;
    }
    if (extended >= 3) return 1; // open
    if (extended <= 1) return 2; // fist
    return 0; // unknown
}

extern "C" {

AAVIJA_API void* CreateTracker(const char* modelPath) {
    auto* t = new Tracker();
    if (modelPath) t->modelPath = modelPath;
    // TODO: initialize MediaPipe HandLandmarker with modelPath.
    return t;
}

AAVIJA_API int ProcessFrame(void* handle, const unsigned char* rgb, int width, int height, long nowMs) {
    if (!handle) return 0;
    auto* t = static_cast<Tracker*>(handle);

    // TODO: run MediaPipe HandLandmarker on rgb (width x height, RGB).
    // Extract 21 landmarks into a flat float array `pts` (x,y,z normalized).
    std::vector<float> pts; // placeholder: empty => no hand

    // For now, no landmarks => unknown; real impl fills pts.
    if (pts.empty()) {
        if (t->lastHandAt != 0 && nowMs - t->lastHandAt > 800) {
            t->lastHandAt = 0;
            t->recent.clear();
            t->pose = 0;
        }
        return 0;
    }

    t->lastHandAt = nowMs;
    int c = Classify(pts);
    t->recent.push_back(c);
    while ((int)t->recent.size() > t->windowSize) t->recent.erase(t->recent.begin());
    if ((int)t->recent.size() < t->windowSize) return 0;

    int fists = 0, opens = 0;
    for (int v : t->recent) { if (v == 2) fists++; if (v == 1) opens++; }
    int decided = (fists >= t->minStable) ? 2 : (opens >= t->minStable) ? 1 : 0;
    if (decided == 0 || decided == t->pose) return 0;

    int previous = t->pose;
    t->pose = decided;
    int type = 0; // 0 none, 1 grab, 2 release
    if (previous == 1 && decided == 2) type = 1; // grab
    else if (previous == 2 && decided == 1) type = 2; // release
    else return 0;

    if (t->lastEventAt != 0 && nowMs - t->lastEventAt < t->minHoldMs) return 0;
    t->lastEventAt = nowMs;
    return type;
}

AAVIJA_API void DestroyTracker(void* handle) {
    delete static_cast<Tracker*>(handle);
}

}
