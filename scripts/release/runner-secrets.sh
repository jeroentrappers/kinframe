#!/usr/bin/env bash
# Builds what a release job needs from the secrets of GitHub environment `production` (docs/release.md#secrets), in a
# private directory under $RUNNER_TEMP, and removes it all again. The runners themselves hold no secrets.
#   runner-secrets.sh android   keystores, Play service account JSON, google-services.json
#   runner-secrets.sh deploy    ssh key and config for appmire-hetz1, kinframe-deploy clone with .vault-pass, gpg key
#   runner-secrets.sh apple     App Store Connect key (.p8), match repo key, a temporary signing keychain
#   runner-secrets.sh cleanup   delete the keychain (restoring the search list), stop gpg-agent, remove the directory
# Input: the secrets as environment variables of the same name. Output: paths and settings in $GITHUB_ENV. Values are
# never printed; values derived from secrets are masked first. No set -x here.
set -euo pipefail
: "${RUNNER_TEMP:?run this in a GitHub Actions job}"
: "${GITHUB_ENV:?run this in a GitHub Actions job}"
dir="$RUNNER_TEMP/kinframe-release"
umask 077
mkdir -p "$dir"

fail() { echo "::error::$*"; exit 1; }
need() { local n; for n in "$@"; do [[ -n "${!n:-}" ]] || fail "secret $n is not set in environment production"; done; }
mask() { local line; while IFS= read -r line; do [[ -z "$line" ]] || echo "::add-mask::$line"; done <<< "$1"; }
export_env() { printf '%s=%s\n' "$1" "$2" >> "$GITHUB_ENV"; }
write() { printf '%s' "${!1}" > "$2"; }      # write secret $1 to file $2
write_b64() { printf '%s' "${!1}" | base64 --decode > "$2"; }

# ssh config for the hosts the jobs reach: appmire-hetz1 (deploy), gitlab.com (kinframe-deploy), github.com (match).
ssh_setup() {
  need RELEASE_SSH_KNOWN_HOSTS
  write RELEASE_SSH_KNOWN_HOSTS "$dir/known_hosts"
  : > "$dir/ssh_config"
  if [[ -n "${DEPLOY_SSH_KEY:-}" ]]; then
    write DEPLOY_SSH_KEY "$dir/hetz1_key"
    printf 'Host appmire-hetz1\n  HostName %s\n  User ansible\n  IdentityFile %s\n' "$DEPLOY_SSH_HOST" "$dir/hetz1_key" >> "$dir/ssh_config"
  fi
  if [[ -n "${DEPLOY_REPO_KEY:-}" ]]; then
    write DEPLOY_REPO_KEY "$dir/deploy_repo_key"
    printf 'Host gitlab.com\n  User git\n  IdentityFile %s\n' "$dir/deploy_repo_key" >> "$dir/ssh_config"
  fi
  if [[ -n "${MATCH_DEPLOY_KEY:-}" ]]; then
    write MATCH_DEPLOY_KEY "$dir/match_key"
    printf 'Host github.com\n  User git\n  IdentityFile %s\n' "$dir/match_key" >> "$dir/ssh_config"
  fi
  # Only these keys and known hosts, never the runner user's own.
  printf 'Host *\n  IdentitiesOnly yes\n  IdentityAgent none\n  BatchMode yes\n  StrictHostKeyChecking yes\n  UserKnownHostsFile %s\n  GlobalKnownHostsFile /dev/null\n' \
    "$dir/known_hosts" >> "$dir/ssh_config"
  chmod 600 "$dir"/*_key 2>/dev/null || true
  export_env KINFRAME_SSH_CONFIG "$dir/ssh_config"
  export_env GIT_SSH_COMMAND "ssh -F $dir/ssh_config"
}

android() {
  need KINFRAME_UPLOAD_KEYSTORE_B64 KINFRAME_FRAME_UPLOAD_KEYSTORE_B64 SUPPLY_JSON_KEY_DATA KINFRAME_GOOGLE_SERVICES_JSON
  write_b64 KINFRAME_UPLOAD_KEYSTORE_B64 "$dir/family-upload.jks"
  write_b64 KINFRAME_FRAME_UPLOAD_KEYSTORE_B64 "$dir/frame-upload.jks"
  write SUPPLY_JSON_KEY_DATA "$dir/play-service-account.json"
  write KINFRAME_GOOGLE_SERVICES_JSON "$dir/google-services.json"
  export_env KINFRAME_UPLOAD_KEYSTORE "$dir/family-upload.jks"
  export_env KINFRAME_FRAME_UPLOAD_KEYSTORE "$dir/frame-upload.jks"
  export_env SUPPLY_JSON_KEY "$dir/play-service-account.json"
  export_env KINFRAME_GOOGLE_SERVICES "$dir/google-services.json"
  echo "Android signing and Play files ready"
}

deploy() {
  need DEPLOY_SSH_KEY DEPLOY_SSH_HOST DEPLOY_REPO_KEY KINFRAME_DEPLOY_VAULT_PASSWORD DEPLOY_GPG_PRIVATE_KEY
  ssh_setup
  local repo="${KINFRAME_DEPLOY_REPO:-git@gitlab.com:jeroentrappers/kinframe-deploy.git}" clone="$dir/kinframe-deploy"
  rm -rf "$clone"
  GIT_SSH_COMMAND="ssh -F $dir/ssh_config" git clone --quiet "$repo" "$clone"
  write KINFRAME_DEPLOY_VAULT_PASSWORD "$clone/.vault-pass"
  export_env KINFRAME_DEPLOY_DIR "$clone"
  export_env ANSIBLE_SSH_ARGS "-F $dir/ssh_config -o ControlMaster=auto -o ControlPersist=60s -o ControlPath=$dir/cm-%C"

  # gpg: a keyring of its own with the signing subkey; git signs through a wrapper that supplies the passphrase.
  export GNUPGHOME="$dir/gnupg"
  mkdir -p "$GNUPGHOME"
  printf '%s' "${DEPLOY_GPG_PASSPHRASE:-}" > "$dir/gpg-passphrase"
  write DEPLOY_GPG_PRIVATE_KEY "$dir/gpg-key.asc"
  gpg --batch --quiet --pinentry-mode loopback --passphrase-file "$dir/gpg-passphrase" --import "$dir/gpg-key.asc" 2>/dev/null
  rm -f "$dir/gpg-key.asc"
  local key
  key="$(gpg --batch --with-colons --list-secret-keys | awk -F: '
    ($1 == "sec" || $1 == "ssb") { cand = ($12 ~ /s/ && $15 != "#") ? 1 : 0; next }
    $1 == "fpr" && cand { print $10; exit }')"
  [[ -n "$key" ]] || fail "DEPLOY_GPG_PRIVATE_KEY holds no usable signing key"
  printf '#!/bin/sh\nexec gpg --batch --pinentry-mode loopback --passphrase-file %s "$@"\n' "$dir/gpg-passphrase" > "$dir/gpg-sign"
  chmod 700 "$dir/gpg-sign"
  export_env GNUPGHOME "$GNUPGHOME"
  export_env GIT_CONFIG_COUNT 3
  export_env GIT_CONFIG_KEY_0 gpg.program
  export_env GIT_CONFIG_VALUE_0 "$dir/gpg-sign"
  export_env GIT_CONFIG_KEY_1 user.signingkey
  export_env GIT_CONFIG_VALUE_1 "$key!"
  export_env GIT_CONFIG_KEY_2 commit.gpgsign
  export_env GIT_CONFIG_VALUE_2 true
  echo "kinframe-deploy cloned, ssh and gpg (signing key ${key: -16}) ready"
}

apple() {
  need APP_STORE_CONNECT_API_KEY_JSON MATCH_PASSWORD MATCH_DEPLOY_KEY
  [[ "$(uname -s)" == Darwin ]] || fail "the apple secrets are for the macOS runner"
  ssh_setup
  # fastlane's api_key.json → ASC_KEY_ID, ASC_ISSUER_ID (masked) and the .p8 at ASC_KEY_PATH (ios/fastlane/Fastfile).
  write APP_STORE_CONNECT_API_KEY_JSON "$dir/asc_api_key.json"
  python3 -I - "$dir/asc_api_key.json" "$dir/AuthKey.p8" "$dir/asc_ids" <<'PY'
import json, sys
src, p8, ids = sys.argv[1:4]
d = json.load(open(src))
for k in ("key_id", "issuer_id", "key"):
    if not d.get(k):
        sys.exit(f"::error::APP_STORE_CONNECT_API_KEY_JSON has no {k}")
open(p8, "w").write(d["key"])
open(ids, "w").write(f"{d['key_id']}\n{d['issuer_id']}\n")
PY
  local key_id issuer
  { IFS= read -r key_id; IFS= read -r issuer; } < "$dir/asc_ids"
  rm -f "$dir/asc_ids" "$dir/asc_api_key.json"
  mask "$key_id"; mask "$issuer"
  export_env ASC_KEY_ID "$key_id"
  export_env ASC_ISSUER_ID "$issuer"
  export_env ASC_KEY_PATH "$dir/AuthKey.p8"

  # A signing keychain for this run only: match imports the certificates into it (Fastfile keychain_path), and
  # cleanup deletes it. The runner user's own keychains stay as they were.
  local kc="$dir/kinframe-release.keychain-db" pw
  pw="$(openssl rand -hex 32)"
  mask "$pw"
  security list-keychains -d user | sed -e 's/^[[:space:]]*"//' -e 's/"$//' > "$dir/keychains-before"
  security create-keychain -p "$pw" "$kc"
  security set-keychain-settings -lut 21600 "$kc"
  security unlock-keychain -p "$pw" "$kc"
  # shellcheck disable=SC2046 # one path per line, no spaces in keychain paths
  security list-keychains -d user -s "$kc" $(cat "$dir/keychains-before")
  export_env KINFRAME_KEYCHAIN "$kc"
  export_env KINFRAME_KEYCHAIN_PASSWORD "$pw"
  echo "App Store Connect key, match key and temporary keychain ready"
}

cleanup() {
  if [[ -f "$dir/keychains-before" ]] && command -v security >/dev/null 2>&1; then
    # shellcheck disable=SC2046
    security list-keychains -d user -s $(cat "$dir/keychains-before") || true
    security delete-keychain "$dir/kinframe-release.keychain-db" 2>/dev/null || true
  fi
  if [[ -d "$dir/gnupg" ]]; then GNUPGHOME="$dir/gnupg" gpgconf --kill gpg-agent 2>/dev/null || true; fi
  for cm in "$dir"/cm-*; do
    if [[ -e "$cm" ]]; then ssh -O exit -o ControlPath="$cm" dummy 2>/dev/null || true; fi
  done
  rm -rf "$dir"
  echo "Release secrets removed from the runner"
}

case "${1:-}" in
  android|deploy|apple|cleanup) "$1" ;;
  *) echo "usage: runner-secrets.sh android|deploy|apple|cleanup" >&2; exit 2 ;;
esac
