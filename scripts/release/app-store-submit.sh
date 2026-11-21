#!/usr/bin/env bash
# Submits an uploaded build for App Review: ios/fastlane lane `submit` (iOS) or `mac_submit` (Mac Catalyst), with
# the committed metadata (What's New from ios/fastlane/metadata/*/release_notes.txt) and automatic release.
#   scripts/release/app-store-submit.sh ios 1.2.4 512
#   scripts/release/app-store-submit.sh mac 1.2.4 512
# App Store Connect takes a few minutes to process a fresh upload; until it lists the build, deliver fails with
# "could not find the build". That failure is retried (every 60 s, ATTEMPTS times); any other failure is not.
# Run it in a login shell (scripts/release/in-login-shell.sh) from the repository root, with CI unset.
set -euo pipefail
platform="${1:?usage: app-store-submit.sh ios|mac <version> <build number>}"
version="${2:?version}"
build="${3:?build number}"
case "$platform" in
  ios) lane=submit ;;
  mac) lane=mac_submit ;;
  *) echo "::error::platform must be ios or mac"; exit 2 ;;
esac
attempts="${ATTEMPTS:-15}"
log="${RUNNER_TEMP:-${TMPDIR:-/tmp}}/kinframe-$lane-$build.log"
cd "$(dirname "$0")/../../ios"
for ((i = 1; i <= attempts; i++)); do
  echo "fastlane $lane $version ($build), attempt $i of $attempts"
  if bundle exec fastlane "$lane" "build_number:$build" "app_version:$version" metadata:true 2>&1 | tee "$log"; then
    exit 0
  fi
  if ! grep -Eiq "could ?n[o']t find (the |a |any )?build|no build (was )?found|build .*(not found|does ?n[o']t exist|does not exist)|cannot find build" "$log"; then
    echo "::error::fastlane $lane failed (not a missing build; see the log above)"
    exit 1
  fi
  (( i < attempts )) && sleep 60
done
echo "::error::App Store Connect still has no build $build after $attempts attempts"
exit 1
