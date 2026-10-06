#!/usr/bin/env bash
# Linux mode stage 5 (WIP): builds DroidDeck's in-app Wayland compositor (GPL-3.0) for arm64,
# the GPU path Steam ARM / gamescope need. Pinned to a known-good DroidDeck commit.
# Usage: NDK=/path/to/ndk tools/waylandcomp/build.sh <out-dir>
# Needs: git, cmake + ninja (Android SDK "cmake;3.22.1"). Frame generation is stubbed.
set -euo pipefail
OUT=$(mkdir -p "$1" && cd "$1" && pwd)
: "${NDK:?set NDK}"
DROIDDECK_COMMIT=b44235c495fa6458aa438c9ba3be06562e5d1a3c
CMAKE_BIN=${CMAKE_BIN:-$(dirname "$NDK")/../cmake/3.22.1/bin}
WORK=$(mktemp -d); trap 'rm -rf "$WORK"' EXIT
git clone -q https://github.com/Droid-Deck/DroidDeck.git "$WORK/dd"
git -C "$WORK/dd" checkout -q "$DROIDDECK_COMMIT"
CPP="$WORK/dd/app/src/main/cpp"
mkdir -p "$WORK/src"
cat > "$WORK/src/CMakeLists.txt" <<CM
cmake_minimum_required(VERSION 3.22.1)
project(waylandcomp_standalone C CXX)
add_subdirectory($CPP/adrenotools adrenotools)
add_subdirectory($CPP/waylandcomp waylandcomp)
CM
"$CMAKE_BIN/cmake" -S "$WORK/src" -B "$WORK/out" -G Ninja -DCMAKE_MAKE_PROGRAM="$CMAKE_BIN/ninja" \
  -DCMAKE_TOOLCHAIN_FILE="$NDK/build/cmake/android.toolchain.cmake" \
  -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-26 -DCMAKE_BUILD_TYPE=Release
"$CMAKE_BIN/ninja" -C "$WORK/out" droiddeckwayland
install -m755 "$WORK/out/waylandcomp/libdroiddeckwayland.so" "$OUT/"
install -m755 "$CPP/waylandcomp/prebuilt/lib/libwayland-server.so" "$OUT/"
ls -l "$OUT"/libdroiddeckwayland.so "$OUT"/libwayland-server.so
