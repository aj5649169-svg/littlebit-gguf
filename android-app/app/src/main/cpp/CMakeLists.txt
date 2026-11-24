# Builds libllama_jni.so for Android arm64-v8a by statically linking the
# repository's patched third_party/llama.cpp (LittleBit Q1_0 + Arm KleidiAI).
#
# CMake flags mirror scripts/build-android.sh exactly:
#   -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-28 (from Gradle)
#   -DGGML_NATIVE=OFF -DGGML_CPU_KLEIDIAI=ON -DGGML_OPENMP=OFF

cmake_minimum_required(VERSION 3.22)
project(littlebit-jni LANGUAGES CXX)

# android-app/app/src/main/cpp -> repository root is 5 levels up.
get_filename_component(REPO_ROOT "${CMAKE_CURRENT_SOURCE_DIR}/../../../../../" ABSOLUTE)
set(LLAMA_CPP_DIR "${REPO_ROOT}/third_party/llama.cpp")
set(PATCHES_DIR "${REPO_ROOT}/patches")

if(NOT EXISTS "${LLAMA_CPP_DIR}/CMakeLists.txt")
    message(FATAL_ERROR
        "third_party/llama.cpp not found at ${LLAMA_CPP_DIR}. "
        "Run: git submodule update --init third_party/llama.cpp")
endif()

# ---------------------------------------------------------------------------
# Apply patches/*.diff idempotently (same logic as scripts/apply-patches.sh),
# so a plain Gradle build works on a fresh checkout.
# ---------------------------------------------------------------------------
find_program(GIT_EXECUTABLE git)
if(GIT_EXECUTABLE AND EXISTS "${PATCHES_DIR}")
    file(GLOB PATCH_FILES "${PATCHES_DIR}/*.diff")
    foreach(patch_file ${PATCH_FILES})
        execute_process(
            COMMAND ${GIT_EXECUTABLE} -C ${LLAMA_CPP_DIR} apply --reverse --check ${patch_file}
            RESULT_VARIABLE patch_already_applied
            OUTPUT_QUIET ERROR_QUIET)
        if(NOT patch_already_applied EQUAL 0)
            execute_process(
                COMMAND ${GIT_EXECUTABLE} -C ${LLAMA_CPP_DIR} apply --check ${patch_file}
                RESULT_VARIABLE patch_check_rc
                OUTPUT_QUIET ERROR_QUIET)
            if(NOT patch_check_rc EQUAL 0)
                message(FATAL_ERROR
                    "${patch_file} does not apply to third_party/llama.cpp. "
                    "Reset the submodule (git submodule update --force third_party/llama.cpp) "
                    "or run scripts/apply-patches.sh manually.")
            endif()
            execute_process(
                COMMAND ${GIT_EXECUTABLE} -C ${LLAMA_CPP_DIR} apply ${patch_file}
                RESULT_VARIABLE patch_apply_rc
                OUTPUT_QUIET ERROR_QUIET)
            if(NOT patch_apply_rc EQUAL 0)
                message(FATAL_ERROR "Failed to apply ${patch_file}")
            endif()
            message(STATUS "Applied patch: ${patch_file}")
        endif()
    endforeach()
endif()

# ---------------------------------------------------------------------------
# llama.cpp + ggml configuration — keep in sync with scripts/build-android.sh
# ---------------------------------------------------------------------------
set(BUILD_SHARED_LIBS      OFF  CACHE BOOL "" FORCE)  # static libs linked into libllama_jni.so
set(GGML_NATIVE            OFF  CACHE BOOL "" FORCE)
set(GGML_CPU_KLEIDIAI      ON   CACHE BOOL "" FORCE)
set(GGML_OPENMP            OFF  CACHE BOOL "" FORCE)
set(GGML_VULKAN            ON   CACHE BOOL "" FORCE)

if(ANDROID)
    # The SDK headers + glslc are host tools; link the Android platform loader.
    if(NOT Vulkan_INCLUDE_DIR AND DEFINED ENV{VULKAN_SDK})
        set(Vulkan_INCLUDE_DIR "$ENV{VULKAN_SDK}/include" CACHE PATH "Host Vulkan headers" FORCE)
    endif()
    if(NOT Vulkan_GLSLC_EXECUTABLE AND DEFINED ENV{VULKAN_GLSLC_EXECUTABLE})
        set(Vulkan_GLSLC_EXECUTABLE "$ENV{VULKAN_GLSLC_EXECUTABLE}" CACHE FILEPATH "Host glslc" FORCE)
    endif()
    if(NOT Vulkan_GLSLC_EXECUTABLE AND DEFINED ENV{VULKAN_SDK})
        set(Vulkan_GLSLC_EXECUTABLE "$ENV{VULKAN_SDK}/bin/glslc" CACHE FILEPATH "Host glslc" FORCE)
    endif()
    if(NOT Vulkan_INCLUDE_DIR OR NOT EXISTS "${Vulkan_INCLUDE_DIR}/vulkan/vulkan.h")
        message(FATAL_ERROR "Vulkan headers missing: install host LunarG SDK and set VULKAN_SDK.")
    endif()
    if(NOT Vulkan_GLSLC_EXECUTABLE OR NOT EXISTS "${Vulkan_GLSLC_EXECUTABLE}")
        message(FATAL_ERROR "Modern host glslc missing: set VULKAN_GLSLC_EXECUTABLE to SDK glslc.")
    endif()

    find_library(ANDROID_VULKAN_LIBRARY NAMES vulkan
        PATHS
            "${CMAKE_SYSROOT}/usr/lib/aarch64-linux-android/${CMAKE_SYSTEM_VERSION}"
            "${CMAKE_SYSROOT}/usr/lib/aarch64-linux-android"
        NO_DEFAULT_PATH)
    if(NOT ANDROID_VULKAN_LIBRARY)
        message(FATAL_ERROR "Android NDK libvulkan.so missing for arm64-v8a / android-28.")
    endif()
    set(Vulkan_LIBRARY "${ANDROID_VULKAN_LIBRARY}" CACHE FILEPATH "Android Vulkan loader" FORCE)
endif()
set(GGML_LLAMAFILE         OFF  CACHE BOOL "" FORCE)
set(GGML_BACKEND_DL        OFF  CACHE BOOL "" FORCE)
set(GGML_CPU_ALL_VARIANTS  OFF  CACHE BOOL "" FORCE)
set(GGML_BUILD_TESTS       OFF  CACHE BOOL "" FORCE)
set(GGML_BUILD_EXAMPLES    OFF  CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TESTS      OFF  CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES   OFF  CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER     OFF  CACHE BOOL "" FORCE)
set(LLAMA_BUILD_APP        OFF  CACHE BOOL "" FORCE)
set(LLAMA_CURL             OFF  CACHE BOOL "" FORCE)

# ggml/llama static libs end up inside a shared object: everything needs PIC.
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

add_subdirectory(${LLAMA_CPP_DIR} ${CMAKE_CURRENT_BINARY_DIR}/llama.cpp)

# ---------------------------------------------------------------------------
# JNI bridge
# ---------------------------------------------------------------------------
add_library(llama_jni SHARED llama_jni.cpp)

target_include_directories(llama_jni PRIVATE
    ${LLAMA_CPP_DIR}/include
    ${LLAMA_CPP_DIR}/ggml/include)

# `log` resolves the Run #2 linker error (__android_log_print from
# logToAndroid); `android` is linked for future native glue. The previous
# mobile edit left a stray `log)` line outside the call, which made CMake
# configure fail with a parse error (Runs #3-#6).
target_link_libraries(llama_jni PRIVATE
    llama
    log
    android
    ${ANDROID_VULKAN_LIBRARY})

target_compile_options(llama_jni PRIVATE -O3)

set_target_properties(llama_jni PROPERTIES
    CXX_VISIBILITY_PRESET hidden
    VISIBILITY_INLINES_HIDDEN ON)
