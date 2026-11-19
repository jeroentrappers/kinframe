#!/bin/sh
# Captures the Mac App Store screens of the Mac Catalyst Debug build (seed.py data, same scenes as capture.sh),
# English and Dutch, at 2880×1800: the app renders its own window (-kfSnapshot), so no screen recording is needed.
#   capture-mac.sh   → out/mac/<lang>-<n>.png
# Build first (unsigned is fine):
#   xcodebuild -project ios/Kinframe.xcodeproj -scheme Kinframe -configuration Debug \
#     -destination 'platform=macOS,variant=Mac Catalyst' -derivedDataPath build/dd-mac CODE_SIGNING_ALLOWED=NO build
# Env: APP (the built Kinframe.app), SERVER (default http://localhost:8110), WAIT (seconds, 10).
set -eu
here=$(cd "$(dirname "$0")" && pwd)
app=${APP:-$here/../../build/dd-mac/Build/Products/Debug-maccatalyst/Kinframe.app}
server=${SERVER:-http://localhost:8110}
q() { docker exec kfshots-postgres-1 psql -U kinframe -d kinframe -At -c "$1"; }
frame() { q "select id from frames where name = '$1'"; }
latest_send() { q "select s.id from sends s join users u on u.id = s.sender_user_id where u.email = '$1' order by s.created_at desc limit 1"; }
p=$here/photos
mkdir -p "$here/out/mac"
for lang in en nl; do
  if [ $lang = en ]; then
    email=emma@example.org; f1=$(frame 'Grandma’s frame'); f2=$(frame 'Lisa’s kitchen'); cap="Look who learned to swim! 🌊"
  else
    email=emma.nl@example.org; f1=$(frame 'Kader van oma'); f2=$(frame 'Keuken van Lisa'); cap="Kijk eens wie er kan zwemmen! 🌊"
  fi
  send=$(latest_send $email)
  shot() {
    out=$here/out/mac/$lang-$1.png; shift
    pkill -f "$app/Contents/MacOS/Kinframe" 2>/dev/null || true
    rm -f "$out"
    "$app/Contents/MacOS/Kinframe" -kfReset YES -kfLanguage $lang -kfServer "$server" -kfDevEmail "$email" \
      -kfSnapshot "$out" -kfSnapshotDelay "${WAIT:-10}" "$@" >/dev/null 2>&1 || true
    [ -f "$out" ] || { echo "no snapshot: $out" >&2; exit 1; }
    sips -z 1800 2880 "$out" >/dev/null  # the window can come out a point narrower
    echo "$out"
  }
  shot 1 -kfTab history
  shot 2 -kfTab frames
  shot 3 -kfTab send -kfCompose "$p/sailboat.jpg,$p/sunset.jpg,$p/strawberries.jpg,$p/tulips.jpg,$p/pancakes.jpg" -kfCaption "$cap" -kfComposeFrames "$f1,$f2"
  shot 4 -kfTab history -kfSend "$send"
  shot 5 -kfTab frames -kfFrame "$f1" -kfSection photos
done
