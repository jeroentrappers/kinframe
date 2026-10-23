#!/bin/sh
# shellcheck disable=SC2016 # the command templates below hold literal $name placeholders
# kinframe-convert.sh: turn a stock Frameo photo frame into a kinframe frame over adb, or turn it back.
#
# The command-line twin of the website's convert tool (https://kinframe.appmire.be/convert). Same steps, same
# commands: see tools/convert/README.md. Needs Android platform-tools (adb), curl, unzip and openssl (or apksigner).
#
#   sh kinframe-convert.sh --dry-run          report what would change, change nothing
#   sh kinframe-convert.sh                    convert to kinframe (hosted server)
#   sh kinframe-convert.sh --server URL       convert, with your own kinframe server
#   sh kinframe-convert.sh --revert           back to the stock app
#
# SPDX-License-Identifier: AGPL-3.0-or-later

set -u

# >>> shared plan: keep in sync with web/src/lib/convert/plan.ts (web/src/lib/convert/plan.test.ts checks it)
FRAME_PACKAGE='app.kinframe.frame'
FRAME_ACTIVITY='app.kinframe.frame/.MainActivity'
RELEASE_CERT_SHA256='99c5e36c1a7fd3740caa5115d9e303c982a18555f7bd29336b55e4481a2a205c'
DEFAULT_SERVER='https://kinframe.appmire.be'
RELEASES_REPO='jeroentrappers/kinframe'
FRAMEO_PACKAGE='net.frameo.frame'
DISABLE_PACKAGES='net.frameo.frame com.adups.fota com.adups.fota.sysoper'
GRANT_PERMISSIONS='android.permission.ACCESS_COARSE_LOCATION android.permission.READ_EXTERNAL_STORAGE android.permission.WRITE_EXTERNAL_STORAGE'
APPOPS_ALLOW='WRITE_SETTINGS:23 REQUEST_INSTALL_PACKAGES:26'
SUPPORTED_MODELS='denver/PFF_1024'
MIN_SDK='23'
SUPPORTED_ABIS='armeabi-v7a arm64-v8a'
SET_HOME_MIN_SDK='24'
WORK_DIR='/data/local/tmp/kinframe-convert'
INSTALL_NEWER_PRESENT='INSTALL_FAILED_VERSION_DOWNGRADE'
# Command templates: {name} in plan.ts is $name here (filled by fill(), never by the shell).
CMD_getprop='getprop'
CMD_rootCheck='su 0 id'
CMD_packagePath='pm path $pkg'
CMD_packageInfo='dumpsys package $pkg'
CMD_bootCompleted='getprop sys.boot_completed'
CMD_activities='dumpsys activity activities'
CMD_mkWork='mkdir -p $work'
CMD_backupData='su 0 tar -czf $work/$pkg-data.tgz -C /data/data $pkg'
CMD_backupReadable='su 0 chmod 644 $work/$pkg-data.tgz'
CMD_install='pm install -r -g $work/kinframe.apk'
CMD_grant='pm grant $frame $perm'
CMD_appop='appops set $frame $op allow'
CMD_disable='su 0 pm disable-user $pkg'
CMD_setHome='pm set-home-activity $activity'
CMD_cleanup='rm -rf $work'
CMD_provision='am start -n $activity --es server_url $server'
CMD_provisionLanguage='am start -n $activity --es server_url $server --es language $language'
CMD_restoreFrameo='pm install -r -d $work/restore.apk'
CMD_enable='su 0 pm enable $pkg'
CMD_uninstall='pm uninstall $frame'
CMD_uninstallKeepData='pm uninstall -k $frame'
CMD_uninstallRoot='su 0 pm uninstall $frame'
# <<< shared plan

# ---------------------------------------------------------------- options

MODE=convert
DRY_RUN=0
FORCE=0
SERVER=$DEFAULT_SERVER
LANGUAGE=''
BACKUP=1
FULL_BACKUP=0
BACKUP_DIR=''
KEEP_DATA=0
RESTORE_APK=''
LOCAL_APK=''
PINNED_CERT=$RELEASE_CERT_SHA256
SERIAL=''
LOG_FILE="kinframe-convert-$(date +%Y%m%d-%H%M%S).log"

usage() {
	cat <<'EOF'
Usage: kinframe-convert.sh [options]

Turns a stock Frameo photo frame into a kinframe frame over USB (adb), or reverts it.

  --dry-run            Only report what would change. Nothing on the frame is changed.
  --revert             Back to the stock app: enable Frameo and its updaters, uninstall kinframe, restart.
  --server URL         Your own kinframe server (default: https://kinframe.appmire.be).
  --language CODE      Frame language (en, nl, de, fr, …). Default: the frame asks.
  --force              Continue on a model that hasn't been tested yet.
  --no-backup          Skip the backup of the Frameo app and its data.
  --full-backup        Also copy every eMMC partition except user data (slow, read-only).
  --backup-dir DIR     Where backups go (default: ./kinframe-backup-<serial>-<date>).
  --apk FILE           Install this kinframe APK instead of downloading the latest release.
  --cert SHA256        Expected signing certificate (default: the kinframe release certificate).
  --keep-data          With --revert: keep kinframe's data on the frame.
  --restore-apk FILE   With --revert: reinstall this backed-up Frameo APK first.
  -s SERIAL            The frame's adb serial, when more than one device is connected.
  --log FILE           Log file (default: ./kinframe-convert-<date>.log). Share it when asking for help.
  -h, --help           This help.

Environment: ADB (path to adb, default: adb on PATH).
EOF
}

die_usage() {
	printf 'kinframe-convert: %s\n\n' "$1" >&2
	usage >&2
	exit 2
}

while [ $# -gt 0 ]; do
	case $1 in
	--dry-run) DRY_RUN=1 ;;
	--revert) MODE=revert ;;
	--server) [ $# -ge 2 ] || die_usage "--server needs a URL"; SERVER=$2; shift ;;
	--server=*) SERVER=${1#*=} ;;
	--language) [ $# -ge 2 ] || die_usage "--language needs a code"; LANGUAGE=$2; shift ;;
	--force) FORCE=1 ;;
	--no-backup) BACKUP=0 ;;
	--full-backup) FULL_BACKUP=1 ;;
	--backup-dir) [ $# -ge 2 ] || die_usage "--backup-dir needs a directory"; BACKUP_DIR=$2; shift ;;
	--apk) [ $# -ge 2 ] || die_usage "--apk needs a file"; LOCAL_APK=$2; shift ;;
	--cert) [ $# -ge 2 ] || die_usage "--cert needs a fingerprint"; PINNED_CERT=$2; shift ;;
	--keep-data) KEEP_DATA=1 ;;
	--restore-apk) [ $# -ge 2 ] || die_usage "--restore-apk needs a file"; RESTORE_APK=$2; shift ;;
	-s) [ $# -ge 2 ] || die_usage "-s needs a serial"; SERIAL=$2; shift ;;
	--log) [ $# -ge 2 ] || die_usage "--log needs a file"; LOG_FILE=$2; shift ;;
	-h | --help) usage; exit 0 ;;
	*) die_usage "unknown option: $1" ;;
	esac
	shift
done

# ---------------------------------------------------------------- output and log

: >"$LOG_FILE" || { printf 'kinframe-convert: cannot write %s\n' "$LOG_FILE" >&2; exit 2; }
TMP=$(mktemp -d "${TMPDIR:-/tmp}/kinframe-convert.XXXXXX") || exit 2
trap 'rm -rf "$TMP"' EXIT

log() { printf '%s\n' "$*" >>"$LOG_FILE"; }
say() { printf '%s\n' "$*"; log "$*"; }
step() { printf '\n==> %s\n' "$*"; log ""; log "## $*"; }
warn() { printf 'warning: %s\n' "$*" >&2; log "warning: $*"; }
fail() {
	printf '\nerror: %s\n' "$*" >&2
	log "error: $*"
	printf 'The log is in %s. Please include it when you ask for help.\n' "$LOG_FILE" >&2
	exit 1
}

contains() { case $1 in *"$2"*) return 0 ;; *) return 1 ;; esac; }
in_list() { for _x in $2; do [ "$_x" = "$1" ] && return 0; done; return 1; }

# Values that end up on the frame's command line: no spaces, quotes or shell metacharacters (plan.ts SAFE_ARG).
safe_arg() {
	case $1 in '' | *[!A-Za-z0-9._:/@+=,~-]*) return 1 ;; esac
	return 0
}

# fill TEMPLATE [name=value]...: fills $work, $frame, $activity and the given names, like fill() in plan.ts.
fill() {
	_t=$1
	shift
	for _kv in "work=$WORK_DIR" "frame=$FRAME_PACKAGE" "activity=$FRAME_ACTIVITY" "$@"; do
		_k=${_kv%%=*}
		_v=${_kv#*=}
		safe_arg "$_v" || fail "unsafe value for \$$_k: $_v"
		while contains "$_t" "\$$_k"; do
			_t="${_t%%"\$$_k"*}$_v${_t#*"\$$_k"}"
		done
	done
	contains "$_t" '$' && fail "command template not filled: $_t"
	printf '%s' "$_t"
}

# ---------------------------------------------------------------- adb

ADB=${ADB:-adb}
command -v "$ADB" >/dev/null 2>&1 || fail "adb not found. Install Android platform-tools or set ADB=/path/to/adb."

adb_() {
	if [ -n "$SERIAL" ]; then "$ADB" -s "$SERIAL" "$@"; else "$ADB" "$@"; fi
}

# Android 6's adb shell returns CRLF and no exit status, so success is read from the output.
adb_sh() { adb_ shell "$1" 2>&1 | tr -d '\r'; }

# rd CMD: a read-only command, always run. Output in $OUT.
rd() {
	log "\$ $1"
	OUT=$(adb_sh "$1")
	[ -n "$OUT" ] && log "$OUT"
	return 0
}

# wr CMD: a modifying command; only logged in a dry run. Output in $OUT.
wr() {
	if [ "$DRY_RUN" = 1 ]; then
		say "  would run: $1"
		OUT=''
		return 0
	fi
	log "\$ $1"
	OUT=$(adb_sh "$1")
	[ -n "$OUT" ] && log "$OUT"
	return 0
}

# wr_expect CMD TEXT [TEXT2]: a modifying command whose output must contain TEXT (or TEXT2).
wr_expect() {
	wr "$1"
	[ "$DRY_RUN" = 1 ] && return 0
	contains "$OUT" "$2" && return 0
	[ $# -ge 3 ] && contains "$OUT" "$3" && return 0
	return 1
}

pull() {
	if [ "$DRY_RUN" = 1 ]; then
		say "  would copy: $1 -> $2"
		return 0
	fi
	log "pull $1 -> $2"
	adb_ pull "$1" "$2" >>"$LOG_FILE" 2>&1 && [ -s "$2" ]
}

push() {
	if [ "$DRY_RUN" = 1 ]; then
		say "  would copy: $1 -> $2"
		return 0
	fi
	log "push $1 -> $2"
	adb_ push "$1" "$2" >>"$LOG_FILE" 2>&1
}

wait_boot() {
	say "  waiting for the frame to restart…"
	sleep 5
	adb_ wait-for-device >>"$LOG_FILE" 2>&1
	_i=0
	while [ $_i -lt 90 ]; do
		[ "$(adb_sh "$(fill "$CMD_bootCompleted")")" = 1 ] && { sleep 3; return 0; }
		sleep 2
		_i=$((_i + 1))
	done
	return 1
}

# ---------------------------------------------------------------- identify

getp() {
	_re=$(printf '%s' "$1" | sed 's/[.]/\\./g')
	printf '%s\n' "$PROPS" | sed -n "s/^\[$_re\]: \[\(.*\)\]\$/\1/p" | head -n 1
}

# pkg_state PKG: sets PKG_INSTALLED (0/1), PKG_ENABLED (enabled/disabled/missing), PKG_VERSION, PKG_PATHS.
pkg_state() {
	rd "$(fill "$CMD_packagePath" "pkg=$1")"
	PKG_PATHS=$(printf '%s\n' "$OUT" | sed -n 's/^package:\([^ ]*\).*/\1/p')
	PKG_INSTALLED=0
	PKG_ENABLED=missing
	PKG_VERSION=''
	[ -n "$PKG_PATHS" ] || return 0
	PKG_INSTALLED=1
	_info=$(adb_sh "$(fill "$CMD_packageInfo" "pkg=$1")")
	log "\$ $(fill "$CMD_packageInfo" "pkg=$1")"
	_name=$(printf '%s\n' "$_info" | sed -n 's/.*versionName=\([^ ]*\).*/\1/p' | head -n 1)
	_code=$(printf '%s\n' "$_info" | sed -n 's/.*versionCode=\([0-9]*\).*/\1/p' | head -n 1)
	_en=$(printf '%s\n' "$_info" | sed -n 's/.*User 0:.*enabled=\([0-9]\).*/\1/p' | head -n 1)
	PKG_VERSION="$_name ($_code)"
	case $_en in 2 | 3 | 4) PKG_ENABLED=disabled ;; *) PKG_ENABLED=enabled ;; esac
}

identify() {
	step "Identify the frame"
	_n=$(adb_ devices | sed -n '2,$p' | grep -c 'device$')
	if [ -z "$SERIAL" ] && [ "$_n" -gt 1 ]; then fail "more than one device is connected; pick one with -s SERIAL (see: adb devices)"; fi
	if [ "$_n" -lt 1 ]; then
		adb_ devices -l >>"$LOG_FILE" 2>&1
		fail "no frame found. Turn on ADB on the frame, connect it with a USB cable and allow this computer on the frame's screen."
	fi
	log "\$ $CMD_getprop"
	PROPS=$(adb_sh "$CMD_getprop")
	BRAND=$(getp ro.product.brand)
	DEVICE=$(getp ro.product.device)
	MODEL=$(getp ro.product.model)
	SDK=$(getp ro.build.version.sdk)
	case $SDK in '' | *[!0-9]*) SDK=0 ;; esac
	ABIS=$(getp ro.product.cpu.abilist | tr ',' ' ')
	[ -n "$ABIS" ] || ABIS="$(getp ro.product.cpu.abi) $(getp ro.product.cpu.abi2)"
	DEV_SERIAL=$(getp ro.serialno)
	KEY="$(printf '%s' "$BRAND" | tr '[:upper:]' '[:lower:]')/$DEVICE"
	rd "$(fill "$CMD_rootCheck")"
	ROOT=0
	contains "$OUT" 'uid=0' && ROOT=1

	say "  device:      $BRAND $MODEL ($KEY), $(getp ro.product.manufacturer), $(getp ro.board.platform)"
	say "  android:     $(getp ro.build.version.release) (API $SDK), ABIs: $ABIS, root: $([ $ROOT = 1 ] && echo yes || echo no)"
	log "  fingerprint: $(getp ro.build.fingerprint)"
	STATES=''
	for _p in $FRAME_PACKAGE $DISABLE_PACKAGES; do
		pkg_state "$_p"
		if [ "$PKG_INSTALLED" = 1 ]; then
			say "  $_p: $PKG_VERSION, $PKG_ENABLED"
		else
			say "  $_p: not installed"
		fi
		STATES="$STATES $_p=$PKG_INSTALLED:$PKG_ENABLED"
		[ "$_p" = "$FRAMEO_PACKAGE" ] && FRAMEO_PATHS=$PKG_PATHS
	done

	ISSUES=''
	[ "$SDK" -lt "$MIN_SDK" ] && ISSUES="$ISSUES sdk"
	_abi_ok=0
	for _a in $ABIS; do in_list "$_a" "$SUPPORTED_ABIS" && _abi_ok=1; done
	[ $_abi_ok = 1 ] || ISSUES="$ISSUES abi"
	[ $ROOT = 1 ] || ISSUES="$ISSUES root"
	in_list "$KEY" "$SUPPORTED_MODELS" || ISSUES="$ISSUES model"
	case $ISSUES in
	*sdk* | *abi* | *root*) VERDICT=unsupported ;;
	*model*) VERDICT=untested ;;
	*) VERDICT=supported ;;
	esac
	say "  verdict:     $VERDICT${ISSUES:+ (${ISSUES# })}"
}

# state_of PKG: "installed:enabled" from the identification.
state_of() {
	for _s in $STATES; do
		[ "${_s%%=*}" = "$1" ] && { printf '%s' "${_s#*=}"; return; }
	done
	printf '0:missing'
}

# ---------------------------------------------------------------- APK download and verification

sha256_of() {
	if command -v sha256sum >/dev/null 2>&1; then sha256sum "$1" | cut -d' ' -f1
	elif command -v shasum >/dev/null 2>&1; then shasum -a 256 "$1" | cut -d' ' -f1
	else openssl dgst -sha256 "$1" | sed 's/.*= *//'
	fi
}

version_code() {
	printf '%s\n' "$1" | sed -n 's/^v\([0-9]*\)\.\([0-9]*\)\.\([0-9]*\)\(-beta\.\([0-9]*\)\)\{0,1\}$/\1 \2 \3 \5/p' | {
		read -r _ma _mi _pa _be || exit 1
		[ -n "$_be" ] || _be=99
		echo $((_ma * 1000000 + _mi * 10000 + _pa * 100 + _be))
	}
}

find_apksigner() {
	command -v apksigner 2>/dev/null && return 0
	for _home in "${ANDROID_HOME:-}" "${ANDROID_SDK_ROOT:-}" "$HOME/Android/Sdk" "$HOME/Library/Android/sdk"; do
		if [ -z "$_home" ] || [ ! -d "$_home/build-tools" ]; then continue; fi
		_bt=$(find "$_home/build-tools" -maxdepth 2 -name apksigner -type f 2>/dev/null | sort | tail -n 1)
		[ -n "$_bt" ] && { printf '%s\n' "$_bt"; return 0; }
	done
	return 1
}

# verify_apk FILE [SHA256]: checks the sha256 and that every signing certificate is the pinned one.
verify_apk() {
	_got=$(sha256_of "$1")
	log "sha256 $_got"
	if [ -n "${2:-}" ] && [ "$_got" != "$2" ]; then fail "the downloaded APK does not match its published sha256 (got $_got)"; fi
	_pin=$(printf '%s' "$PINNED_CERT" | tr -d ': ' | tr '[:upper:]' '[:lower:]')
	if _signer=$(find_apksigner); then
		# Full signature check for every Android version from the frame's up.
		_v=$("$_signer" verify --print-certs --min-sdk-version "$SDK" "$1" 2>&1) || { log "$_v"; fail "the APK's signature does not verify"; }
		_certs=$(printf '%s\n' "$_v" | sed -n 's/.*certificate SHA-256 digest: *\([0-9a-f]*\).*/\1/p' | sort -u)
	else
		if ! command -v unzip >/dev/null 2>&1 || ! command -v openssl >/dev/null 2>&1; then
			fail "to check the APK's certificate, install unzip and openssl (or Android build-tools' apksigner)"
		fi
		# The v1 (JAR) signature's certificate, which is what Android 6 checks on install.
		_certs=$(unzip -Z1 "$1" 2>/dev/null | grep -E '^META-INF/[^/]+\.(RSA|DSA|EC)$' | while read -r _e; do
			unzip -p "$1" "$_e" | openssl pkcs7 -inform DER -print_certs 2>/dev/null | openssl x509 -outform DER 2>/dev/null >"$TMP/cert.der"
			sha256_of "$TMP/cert.der"
		done | sort -u)
	fi
	log "certificates: $_certs"
	[ -n "$_certs" ] || fail "the APK is not signed"
	for _c in $_certs; do
		[ "$_c" = "$_pin" ] || fail "the APK is signed with an unexpected certificate ($_c), refusing to install it"
	done
	say "  signature OK: release certificate $(printf '%s' "$_pin" | cut -c1-8)…"
}

fetch_release() {
	step "Download kinframe"
	if [ -n "$LOCAL_APK" ]; then
		[ -f "$LOCAL_APK" ] || fail "no such file: $LOCAL_APK"
		APK=$LOCAL_APK
		say "  using $APK"
		verify_apk "$APK"
		return
	fi
	command -v curl >/dev/null 2>&1 || fail "curl not found"
	_latest=$(curl -fsSLI -o /dev/null -w '%{url_effective}' "https://github.com/$RELEASES_REPO/releases/latest") ||
		fail "could not reach GitHub to find the latest release"
	_tag=${_latest##*/}
	_code=$(version_code "$_tag") || fail "unexpected release tag: $_tag"
	_asset="kinframe-frame-${_tag#v}-$_code.apk"
	_base="https://github.com/$RELEASES_REPO/releases/download/$_tag"
	say "  latest release: $_tag ($_asset)"
	APK="$TMP/$_asset"
	curl -fsSL -o "$APK" "$_base/$_asset" || fail "download failed: $_base/$_asset"
	curl -fsSL -o "$APK.sha256" "$_base/$_asset.sha256" || fail "download failed: $_base/$_asset.sha256"
	_want=$(cut -d' ' -f1 <"$APK.sha256")
	verify_apk "$APK" "$_want"
}

# ---------------------------------------------------------------- backup

backup() {
	[ -n "$BACKUP_DIR" ] || BACKUP_DIR="kinframe-backup-${DEV_SERIAL:-frame}-$(date +%Y%m%d-%H%M%S)"
	step "Back up the Frameo app ($BACKUP_DIR)"
	[ "$DRY_RUN" = 1 ] || mkdir -p "$BACKUP_DIR" || fail "cannot create $BACKUP_DIR"
	if [ -z "${FRAMEO_PATHS:-}" ]; then
		say "  Frameo is not installed: nothing to back up"
	else
		for _path in $FRAMEO_PATHS; do
			pull "$_path" "$BACKUP_DIR/$FRAMEO_PACKAGE-${_path##*/}" || fail "could not copy $_path"
		done
		if [ $ROOT = 1 ]; then
			wr "$(fill "$CMD_mkWork")"
			wr "$(fill "$CMD_backupData" "pkg=$FRAMEO_PACKAGE")"
			wr "$(fill "$CMD_backupReadable" "pkg=$FRAMEO_PACKAGE")"
			pull "$WORK_DIR/$FRAMEO_PACKAGE-data.tgz" "$BACKUP_DIR/$FRAMEO_PACKAGE-data.tgz" || fail "could not copy the Frameo data"
			wr "$(fill "$CMD_cleanup")"
		fi
	fi
	[ "$FULL_BACKUP" = 1 ] && full_backup
	[ "$DRY_RUN" = 1 ] || say "  saved in $BACKUP_DIR"
}

# Every eMMC partition except the largest (user data), via a temporary file: binary output through su is not clean.
full_backup() {
	rd 'cat /proc/partitions'
	_parts=$(printf '%s\n' "$OUT" | awk '$4 ~ /^mmcblk[0-9]+p[0-9]+$/ { print $3, $4 }' | sort -n | sed '$d' | awk '{ print $2 }')
	rd 'ls -l /dev/block/by-name /dev/block/platform/*/by-name'
	[ "$DRY_RUN" = 1 ] || printf '%s\n' "$OUT" >"$BACKUP_DIR/partitions-by-name.txt"
	wr "$(fill "$CMD_mkWork")"
	for _p in $_parts; do
		wr "su 0 dd if=/dev/block/$_p of=$WORK_DIR/$_p.img bs=1048576"
		wr "su 0 chmod 644 $WORK_DIR/$_p.img"
		pull "$WORK_DIR/$_p.img" "$BACKUP_DIR/$_p.img" || fail "could not copy partition $_p"
		wr "rm -f $WORK_DIR/$_p.img"
	done
	wr "$(fill "$CMD_cleanup")"
}

# ---------------------------------------------------------------- convert

convert() {
	identify
	case $VERDICT in
	unsupported) fail "this frame can't run kinframe ($ISSUES). See docs/hardware/supported-devices.md." ;;
	untested)
		if [ "$FORCE" = 1 ]; then
			warn "this model ($KEY) hasn't been tested; continuing because of --force"
		else
			fail "this model ($KEY) hasn't been tested. Run with --dry-run to see what would change, and --force to continue anyway. Please report how it went."
		fi
		;;
	esac
	_server=$SERVER
	case $_server in http://* | https://*) ;; *) _server="https://$_server" ;; esac
	_server=${_server%/}
	safe_arg "$_server" || fail "not a valid server address: $SERVER"
	if [ -n "$LANGUAGE" ]; then safe_arg "$LANGUAGE" || fail "not a valid language code: $LANGUAGE"; fi

	[ "$BACKUP" = 1 ] && backup
	fetch_release

	step "Install kinframe"
	wr "$(fill "$CMD_mkWork")"
	push "$APK" "$WORK_DIR/kinframe.apk" || fail "could not copy the APK to the frame"
	if ! wr_expect "$(fill "$CMD_install")" 'Success' "$INSTALL_NEWER_PRESENT"; then
		contains "$OUT" 'UPDATE_INCOMPATIBLE' && fail "a kinframe signed with another key is installed. Run --revert first (without --keep-data)."
		fail "install failed: $OUT"
	fi
	contains "$OUT" "$INSTALL_NEWER_PRESENT" && say "  a newer kinframe is already installed; keeping it"

	step "Grant permissions"
	for _perm in $GRANT_PERMISSIONS; do
		wr "$(fill "$CMD_grant" "perm=$_perm")"
		contains "$OUT" 'Exception' && warn "could not grant $_perm"
	done
	for _ao in $APPOPS_ALLOW; do
		[ "$SDK" -ge "${_ao#*:}" ] || continue
		wr "$(fill "$CMD_appop" "op=${_ao%%:*}")"
	done

	step "Disable Frameo and the firmware updaters"
	for _p in $DISABLE_PACKAGES; do
		case $(state_of "$_p") in
		1:enabled) wr_expect "$(fill "$CMD_disable" "pkg=$_p")" 'disabled-user' || fail "could not disable $_p: $OUT" ;;
		1:disabled) say "  $_p is already disabled" ;;
		*) say "  $_p is not installed" ;;
		esac
	done

	step "Make kinframe the home app"
	if [ "$SDK" -ge "$SET_HOME_MIN_SDK" ]; then
		wr "$(fill "$CMD_setHome")"
		contains "$OUT" 'Error' && warn "could not set the home app; pick kinframe and 'Always' if the frame asks"
	else
		say "  Android $SDK: kinframe is the home app because the stock app is disabled"
	fi

	wr "$(fill "$CMD_cleanup")"

	step "Restart"
	if [ "$DRY_RUN" = 1 ]; then
		say "  would run: adb reboot"
	else
		log "\$ adb reboot"
		adb_ reboot >>"$LOG_FILE" 2>&1
		wait_boot || fail "the frame did not come back within 3 minutes. If it shows kinframe, set it up on the screen."
	fi

	step "Connect kinframe to $_server"
	if [ -n "$LANGUAGE" ]; then
		wr "$(fill "$CMD_provisionLanguage" "server=$_server" "language=$LANGUAGE")"
	else
		wr "$(fill "$CMD_provision" "server=$_server")"
	fi
	if [ "$DRY_RUN" = 0 ]; then
		sleep 3
		rd "$(fill "$CMD_activities")"
		contains "$OUT" "$FRAME_PACKAGE/" || warn "kinframe is not in front yet; if the frame asks which app to use, pick kinframe and 'Always'"
	fi

	if [ "$DRY_RUN" = 1 ]; then
		say ""
		say "Dry run: nothing on the frame was changed. The log is in $LOG_FILE."
	else
		say ""
		say "Done. Follow the setup on the frame (language, Wi-Fi, time zone)."
		say "At the end it shows a code: enter it at $_server/frames/add to add the frame to your family."
		say "The log is in $LOG_FILE."
	fi
}

# ---------------------------------------------------------------- revert

revert() {
	identify
	[ $ROOT = 1 ] || fail "reverting needs root (su) on the frame to enable the stock app again"

	if [ -n "$RESTORE_APK" ]; then
		step "Reinstall the backed-up Frameo app"
		[ -f "$RESTORE_APK" ] || fail "no such file: $RESTORE_APK"
		wr "$(fill "$CMD_mkWork")"
		push "$RESTORE_APK" "$WORK_DIR/restore.apk" || warn "could not copy $RESTORE_APK"
		wr_expect "$(fill "$CMD_restoreFrameo")" 'Success' || warn "reinstalling the Frameo APK failed: $OUT"
		wr "$(fill "$CMD_cleanup")"
	fi

	step "Enable Frameo and the firmware updaters"
	for _p in $DISABLE_PACKAGES; do
		case $(state_of "$_p") in
		1:disabled) wr_expect "$(fill "$CMD_enable" "pkg=$_p")" 'enabled' || warn "could not enable $_p: $OUT" ;;
		1:enabled) say "  $_p is already enabled" ;;
		*) say "  $_p is not installed" ;;
		esac
	done

	step "Uninstall kinframe"
	case $(state_of "$FRAME_PACKAGE") in
	1:*)
		if [ "$KEEP_DATA" = 1 ]; then _cmd=$CMD_uninstallKeepData; else _cmd=$CMD_uninstall; fi
		if ! wr_expect "$(fill "$_cmd")" 'Success'; then
			wr_expect "$(fill "$CMD_uninstallRoot")" 'Success' || warn "could not uninstall kinframe: $OUT"
		fi
		;;
	*) say "  kinframe is not installed" ;;
	esac
	wr "$(fill "$CMD_cleanup")"

	step "Restart"
	if [ "$DRY_RUN" = 1 ]; then
		say "  would run: adb reboot"
		say ""
		say "Dry run: nothing on the frame was changed. The log is in $LOG_FILE."
		return
	fi
	log "\$ adb reboot"
	adb_ reboot >>"$LOG_FILE" 2>&1
	wait_boot || warn "the frame did not come back within 3 minutes"
	rd "$(fill "$CMD_activities")"
	contains "$OUT" "$FRAME_PACKAGE/" && warn "kinframe is still in front"
	say ""
	say "Done. The frame starts the stock app again. The log is in $LOG_FILE."
}

log "kinframe-convert $MODE$([ "$DRY_RUN" = 1 ] && echo ' (dry run)') $(date)"
log "host: $(uname -a)"
log "adb: $("$ADB" version 2>/dev/null | head -n 1)"
[ "$DRY_RUN" = 1 ] && say "Dry run: only reading from the frame."
if [ "$MODE" = revert ]; then revert; else convert; fi
