cmake_minimum_required(VERSION 3.22)
project(companion C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

include(FetchContent)

# llama.cpp, pinned to a known release
set(LLAMA_BUILD_COMMON   OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TESTS    OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TOOLS    OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER   OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_APP      OFF CACHE BOOL "" FORCE)
set(LLAMA_OPENSSL        OFF CACHE BOOL "" FORCE)
set(BUILD_SHARED_LIBS    OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE          OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP          OFF CACHE BOOL "" FORCE)
set(GGML_CPU_KLEIDIAI    OFF CACHE BOOL "" FORCE)
# Baseline ARM build so it runs on older tablets too (no dotprod/fp16 required)
if(ANDROID_ABI STREQUAL "armeabi-v7a")
    # llamafile's fast matmul needs fp16 NEON, which 32-bit ARM builds lack
    set(GGML_LLAMAFILE OFF CACHE BOOL "" FORCE)
endif()

FetchContent_Declare(
    llama
    GIT_REPOSITORY https://github.com/ggml-org/llama.cpp.git
    GIT_TAG        b12017
    GIT_SHALLOW    TRUE
)
FetchContent_MakeAvailable(llama)

add_library(companion SHARED llm_bridge.cpp)
target_link_libraries(companion llama android log)
