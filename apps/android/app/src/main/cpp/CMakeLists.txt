cmake_minimum_required(VERSION 3.22.1)
project(signalasi_whisper LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(WHISPER_ROOT ${CMAKE_CURRENT_SOURCE_DIR}/whispercpp)

set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
set(GGML_CPU_ARM_ARCH "armv8-a" CACHE STRING "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
set(GGML_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(GGML_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
add_subdirectory(${WHISPER_ROOT}/ggml ${CMAKE_CURRENT_BINARY_DIR}/ggml)

foreach(GGML_TARGET ggml ggml-base ggml-cpu)
    if(TARGET ${GGML_TARGET})
        target_compile_options(${GGML_TARGET} PRIVATE -O3 -march=armv8-a)
    endif()
endforeach()

add_library(whisper SHARED
    whisper_jni.c
    ${WHISPER_ROOT}/src/whisper.cpp
)

target_compile_definitions(whisper PRIVATE
    GGML_USE_CPU
    WHISPER_VERSION="1.9.1"
)
target_compile_options(whisper PRIVATE
    -O3
    -march=armv8-a
    -fvisibility=hidden
    -fvisibility-inlines-hidden
)
target_include_directories(whisper PRIVATE
    ${WHISPER_ROOT}
    ${WHISPER_ROOT}/include
    ${WHISPER_ROOT}/src
    ${WHISPER_ROOT}/ggml/include
    ${WHISPER_ROOT}/ggml/src
    ${WHISPER_ROOT}/ggml/src/ggml-cpu
)

find_library(LOG_LIB log)
find_library(ANDROID_LIB android)
target_link_libraries(whisper PRIVATE ${LOG_LIB} ${ANDROID_LIB} ggml)
