#!/bin/sh
# Captures the App Store screens (seed.py data) on the iPhone 6.9" and iPad 13" simulators, English and Dutch.
#   capture.sh <iphone-udid> <ipad-udid> [<duo-udid>]   → out/raw/<device>-<lang>-<n>.png
# DEVICES limits the run, e.g. DEVICES=duo. The Duo is captured unfolded (inner display); open it in Device Hub first.
# Frame and send ids are looked up in the dev database (compose project kfshots).
set -eu
here=$(cd "$(dirname "$0")" && pwd)
q() { docker exec kfshots-postgres-1 psql -U kinframe -d kinframe -At -c "$1"; }
frame() { q "select id from frames where name = '$1'"; }
latest_send() { q "select s.id from sends s join users u on u.id = s.sender_user_id where u.email = '$1' order by s.created_at desc limit 1"; }
p=$here/photos
for lang in en nl; do
  if [ $lang = en ]; then
    email=emma@example.org; f1=$(frame 'Grandma’s frame'); f2=$(frame 'Lisa’s kitchen'); cap="Look who learned to swim! 🌊"
  else
    email=emma.nl@example.org; f1=$(frame 'Kader van oma'); f2=$(frame 'Keuken van Lisa'); cap="Kijk eens wie er kan zwemmen! 🌊"
  fi
  send=$(latest_send $email)
  for dev in ${DEVICES:-iphone ipad duo}; do
    case $dev in iphone) udid=$1 ;; ipad) udid=$2 ;; duo) udid=${3:-}; [ -n "$udid" ] || continue ;; esac
    [ $dev = duo ] && export SIM_DISPLAY=3 || unset SIM_DISPLAY
    o=$here/out/raw/$dev-$lang
    shot() { n=$1; shift; NO_INSTALL=${NO_INSTALL:-} WAIT=${WAIT:-9} "$here/shoot.sh" "$udid" "$o-$n.png" "$email" -kfReset YES -kfLanguage $lang "$@" >/dev/null; NO_INSTALL=1; }
    NO_INSTALL=
    shot 1 -kfTab history
    shot 2 -kfTab frames
    shot 3 -kfTab send -kfCompose "$p/sailboat.jpg,$p/sunset.jpg,$p/strawberries.jpg,$p/tulips.jpg,$p/pancakes.jpg" -kfCaption "$cap" -kfComposeFrames "$f1,$f2"
    shot 4 -kfTab history -kfSend "$send"
    shot 5 -kfTab frames -kfFrame "$f1" -kfSection photos
    echo "$o-*.png"
  done
done
