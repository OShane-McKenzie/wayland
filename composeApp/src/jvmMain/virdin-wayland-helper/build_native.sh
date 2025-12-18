#!/usr/bin/env bash
# build_native.sh
#
# Builds the wayland-helper C binary and copies it into the Gradle resources
# directory so it gets bundled into the JAR.
#
# Run from the repo root:
#   ./build_native.sh
#
# Prerequisites:
#   cmake, ninja (or make), pkg-config, wayland-dev, wayland-scanner

set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
NATIVE_DIR="$SCRIPT_DIR/native"
BUILD_DIR="$NATIVE_DIR/build"

echo "==> Configuring CMake..."
cmake -S "$NATIVE_DIR" \
      -B "$BUILD_DIR"  \
      -DCMAKE_BUILD_TYPE=Release

echo "==> Building..."
cmake --build "$BUILD_DIR" -j"$(nproc 2>/dev/null || sysctl -n hw.ncpu 2>/dev/null || echo 4)"

echo "==> Deploying to resources..."
cmake --install "$BUILD_DIR" --prefix ""

echo ""
echo "Done!  Binary is in virdin-wayland-helper/native/build"

