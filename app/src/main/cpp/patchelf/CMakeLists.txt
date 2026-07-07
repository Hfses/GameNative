cmake_minimum_required(VERSION 3.22.1)

project(PatchElf)

set(CMAKE_C_FLAGS "${CMAKE_C_FLAGS} -O2")

include_directories(src)

add_library(patchelf SHARED
            src/patchelf.cc)

target_link_libraries(patchelf)

# Align ELF LOAD segments to 16 KB for Android 15+ devices with 16 KB page size.
target_link_options(patchelf PRIVATE -Wl,-z,max-page-size=16384)
