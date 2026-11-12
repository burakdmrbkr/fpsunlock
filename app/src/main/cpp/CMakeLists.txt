cmake_minimum_required(VERSION 3.22.1)
project(propshield CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# bytehook / AAPM (MIT). Vendored as a submodule or dropped into cpp/third_party.
# When the dependency is absent the native layer simply does not build — which is
# the correct outcome, since native.enabled defaults to false anyway.
set(BYTEHOOK_DIR "${CMAKE_CURRENT_SOURCE_DIR}/third_party/bytehook")

if(EXISTS "${BYTEHOOK_DIR}/CMakeLists.txt")
    add_subdirectory(${BYTEHOOK_DIR} bytehook)

    add_library(propshield SHARED propshield.cpp)

    target_link_libraries(propshield
        bytehook
        android
        log)

    if(ANDROID_ABI STREQUAL "arm64-v8a")
        target_compile_options(propshield PRIVATE -march=armv8-a)
    endif()
else()
    message(WARNING
        "bytehook not found at ${BYTEHOOK_DIR}. "
        "The native layer is skipped. Add it with: "
        "git submodule add https://github.com/bytedance/bytehook app/src/main/cpp/third_party/bytehook")
endif()
