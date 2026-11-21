#!/usr/bin/env bash
# Sets the release secrets (docs/release.md#secrets) in the GitHub environment `production` of jeroentrappers/kinframe,
# where only the approved release jobs can read them. Run it yourself, on a trusted machine:
#   export BW_SESSION=$(bw unlock --raw)
#   scripts/release-secrets.sh                      # every secret, from Bitwarden (folder kinframe-release)
#   scripts/release-secrets.sh --from-files         # from the local files of the manual setup instead (mini4/laptop)
#   scripts/release-secrets.sh MATCH_PASSWORD ...   # only these
#   scripts/release-secrets.sh --dry-run            # what would be set, from where; nothing is read or sent
# Values go from Bitwarden or the files to `gh secret set` through a private temporary file and stdin: never echoed,
# never on a command line. A secret whose source is missing is skipped with the reason; the rest go on.
# shellcheck source-path=SCRIPTDIR
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
# shellcheck source=release/secret-catalog.sh
. "$here/release/secret-catalog.sh"
# shellcheck source=release/bitwarden.sh
. "$here/release/bitwarden.sh"

repo="${KINFRAME_REPO:-jeroentrappers/kinframe}"
environment="${KINFRAME_ENVIRONMENT:-production}"
source=bitwarden
dry_run=0
only=""
for arg in "$@"; do
  case "$arg" in
    --from-files) source=files ;;
    --dry-run) dry_run=1 ;;
    -h|--help) sed -n '2,11p' "$0"; exit 0 ;;
    -*) echo "unknown option $arg" >&2; exit 2 ;;
    *) [[ -n "$(secret_kind "$arg")" ]] || { echo "unknown secret $arg" >&2; exit 2; }; only="$only $arg" ;;
  esac
done
names="${only:-$(secret_names | tr '\n' ' ')}"

if (( dry_run )); then
  for name in $names; do echo "$name ($(secret_kind "$name")) from $source → $repo environment $environment"; done
  exit 0
fi

command -v gh >/dev/null 2>&1 || { echo "gh is not installed" >&2; exit 1; }
gh auth status >/dev/null 2>&1 || { echo "gh is not signed in (gh auth login)" >&2; exit 1; }
if [[ "$source" == bitwarden ]]; then
  bw_require
  folder="$(bw_folder_id 0)"
  [[ -n "$folder" ]] || { echo "no Bitwarden folder $BW_FOLDER: run scripts/secrets-backup-to-bitwarden.sh first" >&2; exit 1; }
fi

umask 077
tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT
set_count=0
skipped=""
for name in $names; do
  kind="$(secret_kind "$name")"
  value="$tmp/value"
  rm -f "$value"
  if [[ "$source" == files ]]; then
    secret_from_files "$name" > "$value" 2> "$tmp/err" || { skipped="$skipped $name"; echo "skip $name: $(cat "$tmp/err")"; continue; }
  else
    id="$(bw_item_id "$folder" "$name")" || exit 1
    [[ -n "$id" ]] || { skipped="$skipped $name"; echo "skip $name: no Bitwarden item"; continue; }
    bw_read "$id" "$name" "$kind" > "$value" 2> "$tmp/err" || { skipped="$skipped $name"; echo "skip $name: $(cat "$tmp/err")"; continue; }
  fi
  if [[ ! -s "$value" && "$name" != DEPLOY_GPG_PASSPHRASE ]]; then
    skipped="$skipped $name"; echo "skip $name: empty"; continue
  fi
  gh secret set "$name" --env "$environment" --repo "$repo" < "$value" >/dev/null
  echo "set $name ($kind, $(wc -c < "$value" | tr -d ' ') bytes)"
  set_count=$((set_count + 1))
done
echo "$set_count secrets set in $repo environment $environment."
[[ -z "$skipped" ]] || echo "Skipped:$skipped"
