# Builds whisper.cpp (MIT) for on-device push-to-talk speech recognition.
cmake_minimum_required(VERSION 3.22)
project(sketchsolve_speech C CXX)
set(CMAKE_CXX_STANDARD 17)

set(WHISPER_DIR ${CMAKE_CURRENT_SOURCE_DIR}/whisper.cpp)
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
set(GGML_BACKEND_DL OFF CACHE BOOL "" FORCE)
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
if (${ANDROID_ABI} STREQUAL "arm64-v8a")
    # fp16 + dot-product instructions: much faster on phones from the last several years
    set(GGML_CPU_ARM_ARCH "armv8.2-a+fp16+dotprod" CACHE STRING "" FORCE)
endif()
add_subdirectory(${WHISPER_DIR}/ggml ggml)

add_library(sketchsolve_whisper SHARED
    ${WHISPER_DIR}/src/whisper.cpp
    ${WHISPER_DIR}/src/aneforge/whisper-aneforge.cpp
    whisper_jni.cpp)
target_include_directories(sketchsolve_whisper PRIVATE ${WHISPER_DIR}/include ${WHISPER_DIR}/src ${WHISPER_DIR}/ggml/include)
target_compile_definitions(sketchsolve_whisper PRIVATE WHISPER_VERSION="1.9.5")
target_compile_options(sketchsolve_whisper PRIVATE -O3 -fvisibility=hidden)
target_link_libraries(sketchsolve_whisper ggml log android)
