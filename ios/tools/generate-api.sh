#!/bin/sh
# Regenerates the Swift API client in KinframeKit/API/Generated from docs/api/openapi.yaml.
# Run after changing the spec: ios/tools/generate-api.sh
# Works on macOS and on Linux (a Swift 5.10 or newer toolchain, e.g. the swift:5.10 container image); split.py gives
# the output the same layout and import header either way.
set -eu
cd "$(dirname "$0")/openapi"
out=../../KinframeKit/API/Generated
tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT
swift run -c release specprep ../../../docs/api/openapi.yaml "$tmp/openapi.yaml"
mkdir -p "$tmp/raw"
swift run -c release swift-openapi-generator generate \
  --config openapi-generator-config.yaml \
  --output-directory "$tmp/raw" \
  "$tmp/openapi.yaml"
# Lay it out in the temporary directory first, so a failure (no python3, e.g. inside the swift container: run the
# generator there and split.py on the host) leaves the checked-in client alone.
python3 split.py "$tmp/raw" "$tmp/split"
rm -rf "$out"
mv "$tmp/split" "$out"
echo "Generated $(ls "$out" | wc -l | tr -d ' ') files in KinframeKit/API/Generated"
