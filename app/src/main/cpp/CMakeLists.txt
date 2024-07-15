
cmake_minimum_required(VERSION 3.22.1)

# Set the ABI for Android
set(CMAKE_ANDROID_ARCH_ABI "armeabi-v7a;arm64-v8a")

# PaddleLite configuration
set(PaddleLite_DIR "${CMAKE_CURRENT_SOURCE_DIR}/../../../PaddleLite")
include_directories(${PaddleLite_DIR}/cxx/include)

# OpenCV configuration
set(OpenCV_DIR "${CMAKE_CURRENT_SOURCE_DIR}/../../../OpenCV/sdk/native/jni")
message(STATUS "opencv dir: ${OpenCV_DIR}")
find_package(OpenCV REQUIRED)
message(STATUS "OpenCV libraries: ${OpenCV_LIBS}")
include_directories(${OpenCV_INCLUDE_DIRS})

aux_source_directory(. SOURCES)

set(CMAKE_CXX_FLAGS
        "${CMAKE_CXX_FLAGS} -ffast-math -Ofast -Os -fexceptions"
)

## Compiler flags
set(CMAKE_CXX_FLAGS
        "${CMAKE_CXX_FLAGS} -fvisibility=hidden -fvisibility-inlines-hidden -fdata-sections -ffunction-sections"
)
set(CMAKE_SHARED_LINKER_FLAGS
        "${CMAKE_SHARED_LINKER_FLAGS} -Wl,--gc-sections -Wl,-z,nocopyreloc")

# Create the shared library
add_library(
        Native
        SHARED
        ${SOURCES})

# Find log library
find_library(
        log-lib
        log)

# PaddleLite shared library
add_library(
        paddle_light_api_shared
        SHARED
        IMPORTED)

set_target_properties(
        paddle_light_api_shared
        PROPERTIES
        IMPORTED_LOCATION
        ${PaddleLite_DIR}/cxx/libs/${ANDROID_ABI}/libpaddle_light_api_shared.so
)

# Link libraries
target_link_libraries(
        Native
        paddle_light_api_shared
        ${OpenCV_LIBS}
        GLESv2
        EGL
        jnigraphics
        ${log-lib}
)

add_custom_command(
        TARGET Native
        POST_BUILD
        COMMAND
        ${CMAKE_COMMAND} -E copy
        ${PaddleLite_DIR}/cxx/libs/${ANDROID_ABI}/libc++_shared.so
        ${CMAKE_LIBRARY_OUTPUT_DIRECTORY}/libc++_shared.so)

add_custom_command(
        TARGET Native
        POST_BUILD
        COMMAND
        ${CMAKE_COMMAND} -E copy
        ${PaddleLite_DIR}/cxx/libs/${ANDROID_ABI}/libpaddle_light_api_shared.so
        ${CMAKE_LIBRARY_OUTPUT_DIRECTORY}/libpaddle_light_api_shared.so)

add_custom_command(
        TARGET Native
        POST_BUILD
        COMMAND
        ${CMAKE_COMMAND} -E copy
        ${PaddleLite_DIR}/cxx/libs/${ANDROID_ABI}/libhiai.so
        ${CMAKE_LIBRARY_OUTPUT_DIRECTORY}/libhiai.so)

add_custom_command(
        TARGET Native
        POST_BUILD
        COMMAND
        ${CMAKE_COMMAND} -E copy
        ${PaddleLite_DIR}/cxx/libs/${ANDROID_ABI}/libhiai_ir.so
        ${CMAKE_LIBRARY_OUTPUT_DIRECTORY}/libhiai_ir.so)

add_custom_command(
        TARGET Native
        POST_BUILD
        COMMAND
        ${CMAKE_COMMAND} -E copy
        ${PaddleLite_DIR}/cxx/libs/${ANDROID_ABI}/libhiai_ir_build.so
        ${CMAKE_LIBRARY_OUTPUT_DIRECTORY}/libhiai_ir_build.so)
