#!/bin/sh
# Captures one screen of the Debug app in a simulator, signed in as a seeded user (seed.py).
#   shoot.sh <udid> <out.png> <email> [launch args…]
# e.g. shoot.sh $UDID out/frames.png emma@example.org -kfTab frames -kfLanguage en
# Env: APP (the built Kinframe.app, default build/dd-sim), SERVER (default http://localhost:8110), WAIT (seconds, 6),
#      APPEARANCE (light|dark; left alone when unset), SIM_DISPLAY (simctl --display, e.g. 3 = iPhone Duo inner).
set -eu
udid=$1 out=$2 email=$3
shift 3
here=$(cd "$(dirname "$0")" && pwd)
app=${APP:-$here/../../build/dd-sim/Build/Products/Debug-iphonesimulator/Kinframe.app}
server=${SERVER:-http://localhost:8110}

xcrun simctl boot "$udid" 2>/dev/null || true
xcrun simctl bootstatus "$udid" -b >/dev/null
[ -n "${APPEARANCE:-}" ] && xcrun simctl ui "$udid" appearance "$APPEARANCE"  # can hang; only when asked
timeout 20 xcrun simctl status_bar "$udid" override --time 9:41 --dataNetwork wifi --wifiMode active --wifiBars 3 \
  --cellularMode active --cellularBars 4 --batteryState charged --batteryLevel 100 >/dev/null 2>&1 || true
if [ -z "${NO_INSTALL:-}" ]; then
  xcrun simctl install "$udid" "$app"
fi
xcrun simctl terminate "$udid" app.kinframe.family 2>/dev/null || true
xcrun simctl launch "$udid" app.kinframe.family -kfServer "$server" -kfDevEmail "$email" "$@" >/dev/null
sleep "${WAIT:-6}"
mkdir -p "$(dirname "$out")"
xcrun simctl io "$udid" screenshot --type=png ${SIM_DISPLAY:+--display=$SIM_DISPLAY} "$out" >/dev/null 2>&1
echo "$out"
