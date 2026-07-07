cmake_minimum_required(VERSION 3.22.1)

project(namlooper CXX)

# NeuralAmpModelerCore requires C++20.
set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# Oboe is consumed as a prefab package from com.google.oboe:oboe
find_package(oboe REQUIRED CONFIG)

# --- NeuralAmpModelerCore (git submodule at the repo root) -------------------
set(NAM_CORE_DIR ${CMAKE_CURRENT_SOURCE_DIR}/../../../../external/NeuralAmpModelerCore)
if(NOT EXISTS ${NAM_CORE_DIR}/NAM/dsp.h)
    message(FATAL_ERROR
        "NeuralAmpModelerCore submodule missing. Run: git submodule update --init --recursive")
endif()

file(GLOB NAM_CORE_SOURCES
        ${NAM_CORE_DIR}/NAM/*.cpp
        ${NAM_CORE_DIR}/NAM/*/*.cpp)

add_library(namcore STATIC ${NAM_CORE_SOURCES})

target_include_directories(namcore SYSTEM PUBLIC
        ${NAM_CORE_DIR}
        ${NAM_CORE_DIR}/NAM
        ${NAM_CORE_DIR}/Dependencies/eigen
        ${NAM_CORE_DIR}/Dependencies/nlohmann)

# float samples (the engine is float). Two things are deliberately OFF after
# A/B tests on the Pixel 9 (2026-07): the hand-optimized A2 fast path
# (NAM_ENABLE_A2_FAST) garbled the output completely, and -ffast-math
# audibly degraded the amp sound ("synthy"). Plain -O3 runs a standard
# WaveNet at ~70% of one core with zero xruns — do not re-add either
# without an audible A/B check.
target_compile_definitions(namcore PUBLIC NAM_SAMPLE_FLOAT)

# The amp sim must meet the audio deadline even in Debug builds of the app,
# so optimization is forced here (target options come after AGP's -O0 and
# win).
target_compile_options(namcore PRIVATE -O3)

# --- App library -------------------------------------------------------------
add_library(namlooper SHARED
        LooperEngine.cpp
        RhythmSection.cpp
        NamChain.cpp
        AudioEngine.cpp
        jni_bridge.cpp)

target_compile_options(namlooper PRIVATE -Wall -Wextra -O2)

# --whole-archive: NAM registers its architectures (WaveNet, LSTM, …) via
# static initializers in otherwise-unreferenced objects; without this the
# linker drops them and get_dsp fails with "No config parser registered".
target_link_libraries(namlooper
        -Wl,--whole-archive namcore -Wl,--no-whole-archive
        oboe::oboe
        android
        log)
