# shellcheck shell=bash
# The release secrets (docs/release.md#secrets): one name per secret, the same in GitHub (environment production) and
# in Bitwarden (an item of that name in folder kinframe-release). Sourced by scripts/release-secrets.sh and
# scripts/secrets-backup-to-bitwarden.sh; works with the bash 3.2 of macOS.
#
# Kind: "value" is one line (a password, token, DSN, id); "file" is a whole file (keys, JSON). Binary files are base64
# (the *_B64 names), so every secret is text and Bitwarden holds exactly what GitHub gets.

KINFRAME_SECRETS="
KINFRAME_UPLOAD_KEYSTORE_B64            file
KINFRAME_UPLOAD_KEYSTORE_PASSWORD       value
KINFRAME_UPLOAD_KEY_ALIAS               value
KINFRAME_UPLOAD_KEY_PASSWORD            value
KINFRAME_FRAME_UPLOAD_KEYSTORE_B64      file
KINFRAME_FRAME_UPLOAD_KEYSTORE_PASSWORD value
KINFRAME_FRAME_UPLOAD_KEY_ALIAS         value
KINFRAME_FRAME_UPLOAD_KEY_PASSWORD      value
SUPPLY_JSON_KEY_DATA                    file
KINFRAME_GOOGLE_SERVICES_JSON           file
KINFRAME_SENTRY_DSN_FAMILY              value
KINFRAME_SENTRY_DSN_FRAME               value
KINFRAME_SENTRY_DSN                     value
SENTRY_AUTH_TOKEN                       value
APP_STORE_CONNECT_API_KEY_JSON          file
MATCH_PASSWORD                          value
MATCH_DEPLOY_KEY                        file
REVIEW_PHONE                            value
REVIEW_PASSWORD                         value
KINFRAME_DEPLOY_VAULT_PASSWORD          value
DEPLOY_SSH_HOST                         value
DEPLOY_SSH_KEY                          file
DEPLOY_REPO_KEY                         file
RELEASE_SSH_KNOWN_HOSTS                 file
DEPLOY_GPG_PRIVATE_KEY                  file
DEPLOY_GPG_PASSPHRASE                   value
"

# Where the current manual setup keeps them (override with these variables).
PLAY_ENV="${PLAY_ENV:-$HOME/.config/play/kinframe-signing.env}"
ASC_DIR="${ASC_DIR:-$HOME/.appstoreconnect}"
DEPLOY_DIR="${KINFRAME_DEPLOY_DIR:-$HOME/Developer/gitlab/jeroentrappers/kinframe-deploy}"
DEPLOY_HOST_ALIAS="${DEPLOY_HOST_ALIAS:-appmire-hetz1}"
# Dedicated keys without a passphrase, made for the release jobs (docs/release.md#keys-for-the-release-jobs).
MATCH_DEPLOY_KEY_FILE="${MATCH_DEPLOY_KEY_FILE:-$HOME/.ssh/kinframe-release-match}"
DEPLOY_SSH_KEY_FILE="${DEPLOY_SSH_KEY_FILE:-$HOME/.ssh/kinframe-release-hetz1}"
DEPLOY_REPO_KEY_FILE="${DEPLOY_REPO_KEY_FILE:-$HOME/.ssh/kinframe-release-gitlab}"

# Names, one per line.
secret_names() { printf '%s\n' "$KINFRAME_SECRETS" | awk 'NF { print $1 }'; }
secret_kind() { printf '%s\n' "$KINFRAME_SECRETS" | awk -v n="$1" '$1 == n { print $2 }'; }

# KEY's value in a KEY=value file (last one wins), with a leading ~ or $HOME expanded and surrounding quotes removed.
env_get() {
  local file="$1" key="$2" v
  [[ -f "$file" ]] || return 1
  v="$(sed -n "s/^${key}=//p" "$file" | tail -n 1)"
  v="${v%\"}"; v="${v#\"}"; v="${v%\'}"; v="${v#\'}"
  [[ -n "$v" ]] || return 1
  # shellcheck disable=SC2088,SC2016 # literal "~/" and "$HOME/" prefixes in the file
  case "$v" in
    "~/"*) v="$HOME/${v#\~/}" ;;
    '$HOME/'*) v="$HOME/${v#\$HOME/}" ;;
  esac
  printf '%s' "$v"
}

# Prints the file at the path held by KEY in FILE; fails when either is missing.
file_at() {
  local path
  path="$(env_get "$1" "$2")" || return 1
  [[ -f "$path" ]] || return 1
  cat "$path"
}

b64() { base64 | tr -d '\n'; }

# The secret's value from the local files, on stdout, never echoed elsewhere. Fails (status 1) when the source is
# missing; the reason goes to stderr.
secret_from_files() {
  local name="$1" v path key
  case "$name" in
    KINFRAME_UPLOAD_KEYSTORE_B64|KINFRAME_FRAME_UPLOAD_KEYSTORE_B64)
      key="${name%_B64}"
      path="$(env_get "$PLAY_ENV" "$key" || true)"
      [[ -n "$path" && -f "$path" ]] || { echo "no $key file ($PLAY_ENV)" >&2; return 1; }
      b64 < "$path" ;;
    KINFRAME_UPLOAD_KEYSTORE_PASSWORD|KINFRAME_UPLOAD_KEY_ALIAS|KINFRAME_UPLOAD_KEY_PASSWORD|\
    KINFRAME_FRAME_UPLOAD_KEYSTORE_PASSWORD|KINFRAME_FRAME_UPLOAD_KEY_ALIAS|KINFRAME_FRAME_UPLOAD_KEY_PASSWORD|\
    KINFRAME_SENTRY_DSN_FAMILY|KINFRAME_SENTRY_DSN_FRAME)
      env_get "$PLAY_ENV" "$name" || { echo "$name is not in $PLAY_ENV" >&2; return 1; } ;;
    SUPPLY_JSON_KEY_DATA)
      file_at "$PLAY_ENV" SUPPLY_JSON_KEY || { echo "no SUPPLY_JSON_KEY file ($PLAY_ENV)" >&2; return 1; } ;;
    KINFRAME_GOOGLE_SERVICES_JSON)
      file_at "$PLAY_ENV" KINFRAME_GOOGLE_SERVICES || { echo "no KINFRAME_GOOGLE_SERVICES file ($PLAY_ENV)" >&2; return 1; } ;;
    SENTRY_AUTH_TOKEN)
      env_get "$PLAY_ENV" "$name" || env_get "$ASC_DIR/signing.env" "$name" \
        || { echo "$name is in neither $PLAY_ENV nor $ASC_DIR/signing.env" >&2; return 1; } ;;
    KINFRAME_SENTRY_DSN|MATCH_PASSWORD)
      env_get "$ASC_DIR/signing.env" "$name" || { echo "$name is not in $ASC_DIR/signing.env" >&2; return 1; } ;;
    REVIEW_PHONE|REVIEW_PASSWORD)
      env_get "$ASC_DIR/review.env" "$name" || { echo "$name is not in $ASC_DIR/review.env" >&2; return 1; } ;;
    APP_STORE_CONNECT_API_KEY_JSON)
      # fastlane's api_key.json (key_id, issuer_id, key); without "key", the .p8 at ASC_KEY_PATH (~/.appstoreconnect/env).
      python3 -I - "$ASC_DIR/api_key.json" "$(env_get "$ASC_DIR/env" ASC_KEY_PATH || true)" <<'PY' || return 1
import json, pathlib, sys
src, p8 = pathlib.Path(sys.argv[1]), sys.argv[2]
if not src.is_file():
    sys.exit(f"no {src}")
d = json.loads(src.read_text())
if not d.get("key") and p8 and pathlib.Path(p8).is_file():
    d["key"] = pathlib.Path(p8).read_text()
missing = [k for k in ("key_id", "issuer_id", "key") if not d.get(k)]
if missing:
    sys.exit(f"{src} has no {', '.join(missing)}")
d.setdefault("in_house", False)
sys.stdout.write(json.dumps(d))
PY
      ;;
    MATCH_DEPLOY_KEY|DEPLOY_SSH_KEY|DEPLOY_REPO_KEY)
      eval "path=\$${name}_FILE"
      [[ -f "$path" ]] || { echo "no $path (${name}_FILE)" >&2; return 1; }
      if ! ssh-keygen -y -P '' -f "$path" >/dev/null 2>&1; then
        echo "$path has a passphrase; the release jobs need a key without one" >&2; return 1
      fi
      cat "$path" ;;
    DEPLOY_SSH_HOST)
      v="$(ssh -G "$DEPLOY_HOST_ALIAS" 2>/dev/null | awk '$1 == "hostname" { print $2; exit }')"
      [[ -n "$v" && "$v" != "$DEPLOY_HOST_ALIAS" ]] || { echo "no HostName for $DEPLOY_HOST_ALIAS in ~/.ssh/config" >&2; return 1; }
      printf '%s' "$v" ;;
    RELEASE_SSH_KNOWN_HOSTS)
      # appmire-hetz1 (by alias and address), gitlab.com (kinframe-deploy), github.com (the match repo).
      local host out=""
      v="$(ssh -G "$DEPLOY_HOST_ALIAS" 2>/dev/null | awk '$1 == "hostname" { print $2; exit }')"
      for host in "$DEPLOY_HOST_ALIAS" "$v" gitlab.com github.com; do
        [[ -n "$host" ]] || continue
        out="$out$(ssh-keygen -F "$host" 2>/dev/null | grep -v '^#' || true)
"
      done
      for host in gitlab.com github.com; do
        ssh-keygen -F "$host" >/dev/null 2>&1 || { echo "$host is not in ~/.ssh/known_hosts (ssh -T git@$host once)" >&2; return 1; }
      done
      if [[ -z "$v" ]] || ! ssh-keygen -F "$v" >/dev/null 2>&1; then echo "$DEPLOY_HOST_ALIAS ($v) is not in ~/.ssh/known_hosts" >&2; return 1; fi
      printf '%s' "$out" | awk 'NF && !seen[$0]++' ;;
    KINFRAME_DEPLOY_VAULT_PASSWORD)
      [[ -f "$DEPLOY_DIR/.vault-pass" ]] || { echo "no $DEPLOY_DIR/.vault-pass" >&2; return 1; }
      tr -d '\n' < "$DEPLOY_DIR/.vault-pass" ;;
    DEPLOY_GPG_PRIVATE_KEY)
      # Only the signing subkey (KINFRAME_DEPLOY_GPG_KEY, e.g. ABCD1234!), never the primary secret key.
      key="${KINFRAME_DEPLOY_GPG_KEY:-$(git -C "$DEPLOY_DIR" config user.signingkey 2>/dev/null || true)}"
      [[ -n "$key" ]] || { echo "set KINFRAME_DEPLOY_GPG_KEY to the signing subkey (KEYID!)" >&2; return 1; }
      gpg --armor --export-secret-subkeys "$key" ;;
    DEPLOY_GPG_PASSPHRASE)
      # Asked for; it is in no file. Empty for a key without a passphrase is fine.
      [[ -r /dev/tty ]] || { echo "DEPLOY_GPG_PASSPHRASE needs a terminal" >&2; return 1; }
      printf 'Passphrase of the deploy gpg key (input hidden): ' > /dev/tty
      IFS= read -rs v < /dev/tty; printf '\n' > /dev/tty
      printf '%s' "$v" ;;
    *) echo "unknown secret $name" >&2; return 1 ;;
  esac
}
