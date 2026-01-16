#!/bin/bash
set -e

# Build script for Xcode Run Script phase
# Builds Rust core only if source files have changed (dev builds)
# Always rebuilds for Release/Archive builds to ensure fresh binaries
# Builds all architectures for full XCFramework compatibility

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
REPO_ROOT="$(dirname "$SCRIPT_DIR")"
CORE_DIR="$REPO_ROOT/core"
BUILD_DIR="$SCRIPT_DIR/build"
OUTPUT_DIR="$SCRIPT_DIR/Transponder"

# Use native rustup
export PATH="$HOME/.cargo/bin:$PATH"

SWIFT_BINDINGS="$OUTPUT_DIR/Transponder/transponder_core.swift"
XCFRAMEWORK="$OUTPUT_DIR/transponder_core.xcframework"
MARKER="$BUILD_DIR/.last_build"

# Force rebuild for Release/Archive builds
if [ "$CONFIGURATION" = "Release" ] || [ "$ACTION" = "install" ]; then
    echo "Release/Archive build detected, forcing clean rebuild..."
    rm -f "$MARKER"
fi

# Find newest source file in core
NEWEST_SOURCE=$(find "$CORE_DIR/src" "$CORE_DIR/Cargo.toml" -type f 2>/dev/null | xargs stat -f "%m %N" 2>/dev/null | sort -rn | head -1 | cut -d' ' -f2-)

# Check if we need to rebuild
NEEDS_REBUILD=false

if [ ! -f "$MARKER" ]; then
    echo "No previous build marker, building..."
    NEEDS_REBUILD=true
elif [ ! -f "$SWIFT_BINDINGS" ]; then
    echo "Swift bindings not found, building..."
    NEEDS_REBUILD=true
elif [ ! -d "$XCFRAMEWORK" ]; then
    echo "XCFramework not found, building..."
    NEEDS_REBUILD=true
elif [ -n "$NEWEST_SOURCE" ] && [ "$NEWEST_SOURCE" -nt "$MARKER" ]; then
    echo "Source files changed, rebuilding..."
    NEEDS_REBUILD=true
fi

if [ "$NEEDS_REBUILD" = true ]; then
    echo "=== Installing Rust targets ==="
    rustup target add aarch64-apple-ios 2>/dev/null || true
    rustup target add aarch64-apple-ios-sim 2>/dev/null || true
    rustup target add x86_64-apple-ios 2>/dev/null || true
    rustup target add aarch64-apple-darwin 2>/dev/null || true
    rustup target add x86_64-apple-darwin 2>/dev/null || true

    echo "=== Building for iOS (arm64) ==="
    cargo build --manifest-path "$CORE_DIR/Cargo.toml" --release --target aarch64-apple-ios

    echo "=== Building for iOS Simulator (arm64) ==="
    cargo build --manifest-path "$CORE_DIR/Cargo.toml" --release --target aarch64-apple-ios-sim

    echo "=== Building for iOS Simulator (x86_64) ==="
    cargo build --manifest-path "$CORE_DIR/Cargo.toml" --release --target x86_64-apple-ios

    echo "=== Building for macOS (arm64) ==="
    cargo build --manifest-path "$CORE_DIR/Cargo.toml" --release --target aarch64-apple-darwin

    echo "=== Building for macOS (x86_64) ==="
    cargo build --manifest-path "$CORE_DIR/Cargo.toml" --release --target x86_64-apple-darwin

    echo "=== Creating fat libraries ==="
    rm -rf "$BUILD_DIR"
    mkdir -p "$BUILD_DIR/ios-simulator" "$BUILD_DIR/macos"

    # iOS Simulator: combine arm64 and x86_64
    lipo -create \
        "$REPO_ROOT/target/aarch64-apple-ios-sim/release/libtransponder_core.a" \
        "$REPO_ROOT/target/x86_64-apple-ios/release/libtransponder_core.a" \
        -output "$BUILD_DIR/ios-simulator/libtransponder_core.a"

    # macOS: combine arm64 and x86_64
    lipo -create \
        "$REPO_ROOT/target/aarch64-apple-darwin/release/libtransponder_core.a" \
        "$REPO_ROOT/target/x86_64-apple-darwin/release/libtransponder_core.a" \
        -output "$BUILD_DIR/macos/libtransponder_core.a"

    echo "=== Generating Swift bindings ==="
    cargo run --manifest-path "$CORE_DIR/Cargo.toml" --bin uniffi-bindgen generate \
        --library "$REPO_ROOT/target/aarch64-apple-darwin/release/libtransponder_core.a" \
        --language swift \
        --out-dir "$BUILD_DIR/swift"

    echo "=== Creating module map ==="
    mkdir -p "$BUILD_DIR/headers"
    cp "$BUILD_DIR/swift/transponder_coreFFI.h" "$BUILD_DIR/headers/"

    cat > "$BUILD_DIR/headers/module.modulemap" << 'EOF'
module transponder_coreFFI {
    header "transponder_coreFFI.h"
    export *
}
EOF

    echo "=== Creating XCFramework ==="
    rm -rf "$XCFRAMEWORK"

    xcodebuild -create-xcframework \
        -library "$REPO_ROOT/target/aarch64-apple-ios/release/libtransponder_core.a" \
        -headers "$BUILD_DIR/headers" \
        -library "$BUILD_DIR/ios-simulator/libtransponder_core.a" \
        -headers "$BUILD_DIR/headers" \
        -library "$BUILD_DIR/macos/libtransponder_core.a" \
        -headers "$BUILD_DIR/headers" \
        -output "$XCFRAMEWORK"

    echo "=== Copying Swift bindings ==="
    cp "$BUILD_DIR/swift/transponder_core.swift" "$SWIFT_BINDINGS"

    # Update build marker
    touch "$MARKER"

    echo "=== Done ==="
else
    echo "Rust core is up to date"
fi
