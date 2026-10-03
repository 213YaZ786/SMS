# Voice messages written out on the phone: whisper.cpp (MIT) built from its
# pinned source (fetched and checked by the app's build), with one CPU
# variant per generation of ARM chip, the right one chosen when it starts.
cmake_minimum_required(VERSION 3.22)
project(smswhisper CXX C)

set(BUILD_SHARED_LIBS ON CACHE BOOL "" FORCE)
set(GGML_BACKEND_DL ON CACHE BOOL "" FORCE)
set(GGML_CPU_ALL_VARIANTS ON CACHE BOOL "" FORCE)
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(WHISPER_ALL_WARNINGS OFF CACHE BOOL "" FORCE)

add_subdirectory(${WHISPER_SRC} whisper)

add_library(smswhisper SHARED whisper_jni.cpp)
target_link_libraries(smswhisper PRIVATE whisper ggml log)
target_compile_options(smswhisper PRIVATE -O2 -fvisibility=hidden)
