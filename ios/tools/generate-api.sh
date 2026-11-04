#!/bin/sh
# Regenerates the Swift API client in KinframeKit/API/Generated from docs/api/openapi.yaml.
# Run after changing the spec: ios/tools/generate-api.sh
set -eu
cd "$(dirname "$0")/openapi"
out=../../KinframeKit/API/Generated
tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT
swift run -c release specprep ../../../docs/api/openapi.yaml "$tmp/openapi.yaml"
rm -rf "$out"
mkdir -p "$out"
swift run -c release swift-openapi-generator generate \
  --config openapi-generator-config.yaml \
  --output-directory "$out" \
  "$tmp/openapi.yaml"
echo "Generated $(ls "$out" | wc -l | tr -d ' ') files in KinframeKit/API/Generated"
