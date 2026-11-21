# shellcheck shell=bash
# Bitwarden password manager (bw CLI) helpers for the release secrets: one item per secret, named after it, in folder
# kinframe-release (docs/release.md#bitwarden). A "value" secret is the hidden custom field "value" of a secure note; a
# "file" secret is the attachment <name>.txt (attachments need Premium or an organization; with
# KINFRAME_BW_FILES_AS_NOTES=1 it is the note's text instead, up to 10 000 characters). Needs BW_SESSION, set by the
# user (export BW_SESSION=$(bw unlock --raw)); these helpers never see the master password.

BW_FOLDER="${BW_FOLDER:-kinframe-release}"

bw_require() {
  command -v bw >/dev/null 2>&1 || { echo "bw is not installed (docs/release.md#bitwarden)" >&2; exit 1; }
  # shellcheck disable=SC2016 # shown literally, for the user to run
  [[ -n "${BW_SESSION:-}" ]] || { echo 'Unlock the vault first: export BW_SESSION=$(bw unlock --raw)' >&2; exit 1; }
  bw sync --quiet >/dev/null
  local status
  status="$(bw status | python3 -I -c 'import json,sys; print(json.load(sys.stdin).get("status",""))')"
  [[ "$status" == unlocked ]] || { echo "the Bitwarden vault is $status; unlock it and export BW_SESSION" >&2; exit 1; }
}

# The folder's id; created when create=1 and missing.
bw_folder_id() {
  local id
  id="$(bw list folders --search "$BW_FOLDER" | python3 -I -c '
import json, sys
name = sys.argv[1]
print(next((f["id"] for f in json.load(sys.stdin) if f["name"] == name), ""))' "$BW_FOLDER")"
  if [[ -z "$id" && "${1:-0}" == 1 ]]; then
    id="$(bw get template folder | python3 -I -c '
import json, sys
d = json.load(sys.stdin); d["name"] = sys.argv[1]; print(json.dumps(d))' "$BW_FOLDER" | bw encode | bw create folder \
      | python3 -I -c 'import json,sys; print(json.load(sys.stdin)["id"])')"
  fi
  printf '%s' "$id"
}

# The id of item NAME in the folder (exact name), empty when there is none; fails when there are several.
bw_item_id() {
  bw list items --folderid "$1" --search "$2" | python3 -I -c '
import json, sys
ids = [i["id"] for i in json.load(sys.stdin) if i["name"] == sys.argv[1]]
if len(ids) > 1:
    sys.exit(f"{len(ids)} Bitwarden items are named {sys.argv[1]}; keep one")
print(ids[0] if ids else "")' "$2"
}

# Prints secret NAME (kind value|file) from item ITEM_ID.
bw_read() {
  local id="$1" name="$2" kind="$3" item
  item="$(bw get item "$id")"
  if [[ "$kind" == value ]]; then
    printf '%s' "$item" | python3 -I -c '
import json, sys
f = next((f for f in json.load(sys.stdin).get("fields") or [] if f.get("name") == "value"), None)
if f is None:
    sys.exit("no field value")
sys.stdout.write(f.get("value") or "")'
  elif printf '%s' "$item" | python3 -I -c '
import json, sys
sys.exit(0 if any(a["fileName"] == sys.argv[1] for a in json.load(sys.stdin).get("attachments") or []) else 1)' "$name.txt"; then
    bw get attachment "$name.txt" --itemid "$id" --raw
  else
    printf '%s' "$item" | python3 -I -c '
import json, sys
n = json.load(sys.stdin).get("notes")
if not n:
    sys.exit("no attachment and no note text")
sys.stdout.write(n)'
  fi
}
