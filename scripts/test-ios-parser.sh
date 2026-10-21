#!/usr/bin/env bash
# Builds and runs the iOS SVG path parser and motion tests with swiftc (no Xcode project, no simulator).
set -euo pipefail

root="$(cd "$(dirname "$0")/.." && pwd)"

if [[ -z "${DEVELOPER_DIR:-}" && -d /Applications/Xcode-26.6.0.app ]]; then
  export DEVELOPER_DIR=/Applications/Xcode-26.6.0.app
fi

binary="${TMPDIR:-/tmp}/svgpath-tests"

xcrun swiftc -swift-version 5 \
  "$root/ios/SVGPath.swift" \
  "$root/ios/Tests/SVGPathTests.swift" \
  -o "$binary"

"$binary"

motion_binary="${TMPDIR:-/tmp}/motion-tests"

xcrun swiftc -swift-version 5 \
  "$root/ios/SVGPath.swift" \
  "$root/ios/Motion.swift" \
  "$root/ios/Tests/MotionTests.swift" \
  -o "$motion_binary"

"$motion_binary"
