cmake_minimum_required(VERSION 3.31.6)

project("ai-chat" VERSION 1.0.0 LANGUAGES C CXX)

set(CMAKE_C_STANDARD 11)
set(CMAKE_C_STANDARD_REQUIRED ON)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

if(DEFINED ANDROID_ABI)
    message(STATUS "Offline AI Android ABI: ${ANDROID_ABI}")
    if(ANDROID_ABI STREQUAL "arm64-v8a")
        set(GGML_SYSTEM_ARCH "ARM")
        set(GGML_CPU_KLEIDIAI ON)
        set(GGML_OPENMP ON)
    else()
        message(FATAL_ERROR "Offline AI currently targets arm64-v8a only")
    endif()
endif()

set(PROJECT_ROOT "${CMAKE_CURRENT_LIST_DIR}/../../../..")
set(LLAMA_SRC "${PROJECT_ROOT}/vendor/llama.cpp")
set(AICHAT_CPP "${LLAMA_SRC}/examples/llama.android/lib/src/main/cpp")
set(VULKAN_CPP_HEADERS "${PROJECT_ROOT}/build/vulkan-headers/include")

if(NOT EXISTS "${LLAMA_SRC}/CMakeLists.txt")
    message(FATAL_ERROR "vendor/llama.cpp is missing. Run the offline AI bootstrap/build workflow first.")
endif()

if(GGML_VULKAN AND NOT EXISTS "${VULKAN_CPP_HEADERS}/vulkan/vulkan.hpp")
    message(FATAL_ERROR "Isolated Vulkan C++ headers are missing at ${VULKAN_CPP_HEADERS}")
endif()

add_subdirectory("${LLAMA_SRC}" build-llama)

if(GGML_VULKAN AND TARGET ggml-vulkan)
    # Android NDK ships the Vulkan C API, while modern ggml-vulkan also consumes
    # Vulkan-Hpp. Use a copied Vulkan-only include tree so host libc++/glibc
    # headers never leak into the Android cross-compilation search path.
    target_include_directories(ggml-vulkan PRIVATE "${VULKAN_CPP_HEADERS}")
endif()

add_library(${CMAKE_PROJECT_NAME} SHARED
        "${AICHAT_CPP}/ai_chat.cpp")

target_compile_definitions(${CMAKE_PROJECT_NAME} PRIVATE
        GGML_SYSTEM_ARCH=${GGML_SYSTEM_ARCH}
        GGML_CPU_KLEIDIAI=$<BOOL:${GGML_CPU_KLEIDIAI}>
        GGML_OPENMP=$<BOOL:${GGML_OPENMP}>)

target_include_directories(${CMAKE_PROJECT_NAME} PRIVATE
        "${AICHAT_CPP}"
        "${LLAMA_SRC}"
        "${LLAMA_SRC}/common"
        "${LLAMA_SRC}/include"
        "${LLAMA_SRC}/ggml/include"
        "${LLAMA_SRC}/ggml/src")

target_link_libraries(${CMAKE_PROJECT_NAME}
        llama
        llama-common
        android
        log)
