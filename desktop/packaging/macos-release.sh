#!/bin/sh
# Builds, signs and notarizes the macOS app and .dmg. Run on a Mac from desktop/:
#   packaging/macos-release.sh [aarch64-apple-darwin|x86_64-apple-darwin]
#
# Signs with APPLE_SIGNING_IDENTITY (default: the Appmire Developer ID). cargo-packager signs the binary and the
# .app with the hardened runtime and notarizes and staples the .app; this script then notarizes and staples the
# .dmg. Notarization uses the App Store Connect API key from APPLE_API_KEY (the key ID), APPLE_API_ISSUER and
# APPLE_API_KEY_PATH, or from ~/.appstoreconnect/env. Without a key the build is signed but not notarized.
set -eu
cd "$(dirname "$0")/.."

target=${1:-aarch64-apple-darwin}
identity=${APPLE_SIGNING_IDENTITY:-"Developer ID Application: Appmire GCV (QB4NQU492U)"}

if [ -z "${APPLE_API_KEY:-}" ] && [ -f "$HOME/.appstoreconnect/env" ]; then
  # shellcheck source=/dev/null
  . "$HOME/.appstoreconnect/env"
  APPLE_API_KEY=${ASC_KEY_ID:-} APPLE_API_ISSUER=${ASC_ISSUER_ID:-} APPLE_API_KEY_PATH=${ASC_KEY_PATH:-}
fi
if [ -n "${APPLE_API_KEY:-}" ] && [ -n "${APPLE_API_ISSUER:-}" ] && [ -n "${APPLE_API_KEY_PATH:-}" ]; then
  notarize=yes
  export APPLE_API_KEY APPLE_API_ISSUER APPLE_API_KEY_PATH
else
  # cargo-packager would try to notarize with whichever of these is set
  notarize=no
  unset APPLE_API_KEY APPLE_API_ISSUER APPLE_API_KEY_PATH
fi

cargo build --release --locked --target "$target"

# cargo-packager reads macos.signing-identity only from its config, so pass the Cargo.toml config with the
# identity added, filled in the way cargo-packager does it for a Cargo package.
config=target/packager-$target.json
cargo metadata --format-version 1 --no-deps | python3 -c '
import json, os, sys
target, identity = sys.argv[1], sys.argv[2]
meta = json.load(sys.stdin)
pkg = next(p for p in meta["packages"] if p["name"] == "kinframe-desktop")
config = dict(pkg["metadata"]["packager"])
# Relative paths in the config resolve against the config file, so make them absolute from the manifest.
root = os.path.dirname(pkg["manifest_path"])
for key in ("out-dir", "license-file"):
    if key in config:
        config[key] = os.path.join(root, config[key])
config["icons"] = [os.path.join(root, i) for i in config.get("icons", [])]
config.setdefault("name", pkg["name"])
config.setdefault("version", pkg["version"])
config.setdefault("description", pkg["description"])
config.setdefault("authors", pkg["authors"])
config["binaries-dir"] = os.path.join(meta["target_directory"], target, "release")
config["binaries"] = [{"path": t["name"], "main": True} for t in pkg["targets"] if "bin" in t["kind"]]
config.setdefault("macos", {})["signing-identity"] = identity
json.dump(config, sys.stdout, indent=2)
' "$target" "$identity" > "$config"

cargo packager --release --target "$target" --formats app,dmg --config "$config"

# shellcheck disable=SC2012
dmg=$(ls -t target/packages/*.dmg | head -1)
app=target/packages/kinframe.app
codesign --verify --deep --strict --verbose=2 "$app"
codesign --verify --verbose=2 "$dmg"

if [ "$notarize" = yes ]; then
  xcrun notarytool submit "$dmg" --key "$APPLE_API_KEY_PATH" --key-id "$APPLE_API_KEY" \
    --issuer "$APPLE_API_ISSUER" --wait
  xcrun stapler staple "$dmg"
  spctl -a -vvv -t install "$dmg"
  spctl -a -vvv "$app"
else
  echo "Signed, not notarized: no App Store Connect API key."
fi
echo "$dmg"
