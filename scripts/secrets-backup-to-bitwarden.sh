#!/usr/bin/env bash
# Backs up the release secrets (docs/release.md#secrets) from the local files of the manual setup into Bitwarden:
# one item per secret, named after its GitHub secret, in folder kinframe-release. Run it yourself on the machine that
# holds the files (mini4 or the laptop), after unlocking the vault yourself; this script never asks for or handles
# the master password:
#   export BW_SESSION=$(bw unlock --raw)
#   scripts/secrets-backup-to-bitwarden.sh                   # every secret whose source exists
#   scripts/secrets-backup-to-bitwarden.sh MATCH_PASSWORD    # only these
# Running it again updates the items (fields replaced, attachment replaced), never duplicates them. Values go through
# a private temporary directory, never through the terminal or a command line.
# shellcheck source-path=SCRIPTDIR
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
# shellcheck source=release/secret-catalog.sh
. "$here/release/secret-catalog.sh"
# shellcheck source=release/bitwarden.sh
. "$here/release/bitwarden.sh"

only=""
for arg in "$@"; do
  case "$arg" in
    -h|--help) sed -n '2,11p' "$0"; exit 0 ;;
    -*) echo "unknown option $arg" >&2; exit 2 ;;
    *) [[ -n "$(secret_kind "$arg")" ]] || { echo "unknown secret $arg" >&2; exit 2; }; only="$only $arg" ;;
  esac
done
names="${only:-$(secret_names | tr '\n' ' ')}"

bw_require
folder="$(bw_folder_id 1)"
[[ -n "$folder" ]] || { echo "could not create the Bitwarden folder $BW_FOLDER" >&2; exit 1; }

umask 077
tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT
stamp="$(date -u +%Y-%m-%dT%H:%MZ)"
host="$(hostname -s 2>/dev/null || hostname)"
saved=0
skipped=""
for name in $names; do
  kind="$(secret_kind "$name")"
  value="$tmp/$name.txt"
  secret_from_files "$name" > "$value" 2> "$tmp/err" || { skipped="$skipped $name"; echo "skip $name: $(cat "$tmp/err")"; rm -f "$value"; continue; }
  if [[ ! -s "$value" && "$name" != DEPLOY_GPG_PASSPHRASE ]]; then skipped="$skipped $name"; echo "skip $name: empty"; continue; fi

  id="$(bw_item_id "$folder" "$name")" || exit 1
  if [[ -z "$id" ]]; then base="$(bw get template item)"; else base="$(bw get item "$id")"; fi
  # The item: a secure note; a value in the hidden field "value"; a file as attachment (or note text, see bitwarden.sh).
  printf '%s' "$base" | python3 -I -c '
import json, os, sys
name, kind, folder, path, note = sys.argv[1:6]
files_as_notes = os.environ.get("KINFRAME_BW_FILES_AS_NOTES") == "1"
d = json.load(sys.stdin)
d.update(type=2, name=name, folderId=folder, secureNote={"type": 0}, login=None, card=None, identity=None)
fields = [f for f in d.get("fields") or [] if f.get("name") != "value"]
text = open(path, encoding="utf-8").read()
if kind == "value":
    fields.append({"name": "value", "value": text, "type": 1})
    d["notes"] = note
elif files_as_notes:
    if len(text) > 10000:
        sys.exit(f"{name} is {len(text)} characters; a note holds 10000 (use an attachment)")
    d["notes"] = text
else:
    d["notes"] = note
d["fields"] = fields
print(json.dumps(d))' "$name" "$kind" "$folder" "$value" "kinframe release secret ($kind), saved $stamp from $host. See docs/release.md." \
    > "$tmp/item.json"
  if [[ -z "$id" ]]; then
    id="$(bw encode < "$tmp/item.json" | bw create item | python3 -I -c 'import json,sys; print(json.load(sys.stdin)["id"])')"
  else
    bw encode < "$tmp/item.json" | bw edit item "$id" >/dev/null
  fi
  if [[ "$kind" == file && "${KINFRAME_BW_FILES_AS_NOTES:-0}" != 1 ]]; then
    # Replace the attachment: delete the old <name>.txt, attach the new one.
    for old in $(bw get item "$id" | python3 -I -c '
import json, sys
print(" ".join(a["id"] for a in json.load(sys.stdin).get("attachments") or [] if a["fileName"] == sys.argv[1]))' "$name.txt"); do
      bw delete attachment "$old" --itemid "$id" >/dev/null
    done
    bw create attachment --file "$value" --itemid "$id" >/dev/null
  fi
  rm -f "$value" "$tmp/item.json"
  echo "saved $name ($kind)"
  saved=$((saved + 1))
done
echo "$saved secrets saved in Bitwarden folder $BW_FOLDER."
[[ -z "$skipped" ]] || echo "Skipped:$skipped"
