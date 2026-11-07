cmake_minimum_required(VERSION 3.20)
project(AavijaHandTracker LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# TODO: point at your MediaPipe build tree.
# set(MEDIAPIPE_DIR "C:/mediapipe")

add_library(AavijaHandTracker SHARED
    src/hand_tracker.cpp
    src/hand_tracker.h
)

target_include_directories(AavijaHandTracker PRIVATE ${CMAKE_CURRENT_SOURCE_DIR}/src)

# target_link_libraries(AavijaHandTracker PRIVATE mediapipe::hand_landmarker)
# target_include_directories(AavijaHandTracker PRIVATE ${MEDIAPIPE_DIR})

set_target_properties(AavijaHandTracker PROPERTIES
    OUTPUT_NAME "AavijaHandTracker"
    PREFIX ""
)
