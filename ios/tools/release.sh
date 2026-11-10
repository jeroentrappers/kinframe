#!/bin/sh
# Archives the app for the App Store and exports the .ipa to build/export; with --upload, also uploads it to
# App Store Connect (TestFlight). Run on a Mac with the Appmire team: ios/tools/release.sh [--upload]
#
# Needs an App Store Connect API key in ~/.appstoreconnect/env (ASC_KEY_ID, ASC_ISSUER_ID, ASC_KEY_PATH).
# MARKETING_VERSION defaults to the latest vX.Y.Z tag without its pre-release suffix, the build number to the
# commit count; override with MARKETING_VERSION=… BUILD_NUMBER=… ios/tools/release.sh.
set -eu
cd "$(dirname "$0")/.."

upload=no
[ "${1:-}" = "--upload" ] && upload=yes

# shellcheck source=/dev/null
. "$HOME/.appstoreconnect/env"
: "${ASC_KEY_ID:?}" "${ASC_ISSUER_ID:?}" "${ASC_KEY_PATH:?}"

tag=$(git describe --tags --abbrev=0 --match 'v[0-9]*' 2>/dev/null || echo v0.1.0)
version=${MARKETING_VERSION:-$(echo "${tag#v}" | sed 's/-.*//')}
build=${BUILD_NUMBER:-$(git rev-list --count HEAD)}
echo "Kinframe $version ($build)"

auth="-allowProvisioningUpdates -authenticationKeyPath $ASC_KEY_PATH -authenticationKeyID $ASC_KEY_ID
  -authenticationKeyIssuerID $ASC_ISSUER_ID"
archive=build/Kinframe.xcarchive
rm -rf "$archive" build/export

# shellcheck disable=SC2086
xcodebuild archive -project Kinframe.xcodeproj -scheme Kinframe -configuration Release \
  -destination 'generic/platform=iOS' -archivePath "$archive" \
  MARKETING_VERSION="$version" CURRENT_PROJECT_VERSION="$build" $auth

# shellcheck disable=SC2086
xcodebuild -exportArchive -archivePath "$archive" -exportOptionsPlist ExportOptions.plist \
  -exportPath build/export $auth
ipa=$(ls build/export/*.ipa)
echo "Exported $ipa"

if [ "$upload" = yes ]; then
  # Same export, but Xcode uploads the build to App Store Connect instead of writing it to disk.
  opts=build/ExportOptions-upload.plist
  cp ExportOptions.plist "$opts"
  /usr/libexec/PlistBuddy -c 'Set :destination upload' "$opts"
  # shellcheck disable=SC2086
  xcodebuild -exportArchive -archivePath "$archive" -exportOptionsPlist "$opts" -exportPath build/upload $auth
  echo "Uploaded $version ($build) to App Store Connect"
fi
