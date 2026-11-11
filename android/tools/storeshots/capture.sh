#!/bin/sh
# Captures the Play Store screens (the seed.py data of ios/tools/storeshots) on a running emulator, English and Dutch.
#   capture.sh <phone|tablet> [<serial>]   → out/raw/<device>-<lang>-<n>.png
# Needs: the debug app installed (./gradlew :family:installDebug), the dev server (compose project kfshots) on
# localhost:8110 and `python3 -I seed.py keepalive http://127.0.0.1:8110` running so the frames show online.
# Env: WAIT (seconds per screen, default 9), ANDROID_HOME.
set -eu
here=$(cd "$(dirname "$0")" && pwd)
ios=$here/../../../ios/tools/storeshots
dev=$1
adb="${ANDROID_HOME:-$HOME/Library/Android/sdk}/platform-tools/adb${2:+ -s $2}"
pkg=app.kinframe.family
server=http://localhost:8110  # adb reverse: the server's discovery document names localhost

q() { docker exec kfshots-postgres-1 psql -U kinframe -d kinframe -At -c "$1"; }
frame() { q "select id from frames where name = '$1'"; }
latest_send() { q "select s.id from sends s join users u on u.id = s.sender_user_id where u.email = '$1' order by s.created_at desc limit 1"; }

$adb wait-for-device
$adb reverse tcp:8110 tcp:8110 >/dev/null
$adb shell pm grant $pkg android.permission.POST_NOTIFICATIONS 2>/dev/null || true

# Clean status bar: 09:41, full battery, wifi, no notification icons.
demo() { $adb shell am broadcast -a com.android.systemui.demo -e command "$@" >/dev/null; }
$adb shell settings put global sysui_demo_allowed 1
demo enter
demo clock -e hhmm 0941
demo battery -e level 100 -e plugged false -e powersave false
demo network -e wifi show -e level 4 -e fully true
demo network -e mobile hide
demo notifications -e visible false

# The composer's photos, readable by the app as file:// URIs in its own files directory.
photos="sailboat sunset strawberries tulips pancakes"
$adb shell mkdir -p /data/local/tmp/kfshots
for p in $photos; do $adb push "$ios/photos/$p.jpg" /data/local/tmp/kfshots/ >/dev/null 2>&1; done
$adb shell chmod 644 '/data/local/tmp/kfshots/*'
$adb shell run-as $pkg sh -c "'mkdir -p files/storeshots && cp /data/local/tmp/kfshots/*.jpg files/storeshots/'"
compose=$(for p in $photos; do printf 'file:///data/user/0/%s/files/storeshots/%s.jpg,' $pkg $p; done)

launch() {
  $adb shell am force-stop $pkg
  $adb shell am start -W -n $pkg/.MainActivity --es kfServer $server --es kfDevEmail "$email" --es kfLanguage $lang "$@" >/dev/null
}

for lang in en nl; do
  if [ $lang = en ]; then
    email=emma@example.org; f1=$(frame 'Grandma’s frame'); f2=$(frame 'Lisa’s kitchen'); cap="Look who learned to swim! 🌊"
  else
    email=emma.nl@example.org; f1=$(frame 'Kader van oma'); f2=$(frame 'Keuken van Lisa'); cap="Kijk eens wie er kan zwemmen! 🌊"
  fi
  send=$(latest_send $email)
  o=$here/out/raw/$dev-$lang
  mkdir -p "$(dirname "$o")"
  # Sign in as this language's family and switch the app language (recreates the activity once).
  launch --ez kfReset true --es kfTab history
  sleep "${WAIT:-9}"
  shot() {
    n=$1; shift
    launch "$@"
    sleep "${WAIT:-9}"
    $adb exec-out screencap -p > "$o-$n.png"
    echo "$o-$n.png"
  }
  shot 1 --es kfTab history
  shot 2 --es kfTab frames
  shot 3 --es kfTab send --es kfCompose "$compose" --es kfCaption "'$cap'" --es kfComposeFrames "$f1,$f2"
  shot 4 --es kfTab history --es kfSend "$send"
  shot 5 --es kfTab frames --es kfFrame "$f1" --es kfSection photos
done
demo exit
