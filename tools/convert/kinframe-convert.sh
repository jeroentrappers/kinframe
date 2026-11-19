#!/bin/sh
# shellcheck disable=SC2016 # the command templates below hold literal $name placeholders
# kinframe-convert.sh: turn a stock Frameo photo frame into a kinframe frame over adb, or turn it back.
#
# The command-line twin of the website's convert tool (https://kinframe.appmire.be/convert). Same steps, same
# commands: see tools/convert/README.md. Needs Android platform-tools (adb), curl, tar, unzip and openssl (or apksigner).
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
ADUPS_PACKAGE='com.adups.fota'
DISABLE_PACKAGES='net.frameo.frame com.adups.fota com.adups.fota.sysoper android.rockchip.update.service'
EXTRA_PACKAGES='com.android.browser com.android.calculator2 com.android.music com.android.musicfx com.android.soundrecorder com.android.gallery3d android.rk.RockVideoPlayer com.android.rk com.android.rk.mediafloat com.android.dreams.basic com.android.dreams.phototable com.android.galaxy4 com.android.noisefield com.android.phasebeam com.android.wallpaper com.android.wallpaper.holospiral com.android.wallpaper.livepicker com.android.wallpapercropper com.android.htmlviewer com.android.bluetoothmidiservice com.android.printspooler com.android.providers.downloads.ui com.cghs.stresstest com.DeviceTest com.svox.pico'
STATE_FILE='/data/local/tmp/kinframe-state.txt'
ADUPS_HOSTS='fota5p.adups.com fota5p.adups.cn fruet.adups.com'
HOSTS_BEGIN='# >>> kinframe: block the Adups firmware update servers (kinframe revert removes this block)'
HOSTS_END='# <<< kinframe'
HOSTS='/system/etc/hosts'
GRANT_PERMISSIONS='android.permission.ACCESS_COARSE_LOCATION android.permission.READ_EXTERNAL_STORAGE android.permission.WRITE_EXTERNAL_STORAGE'
APPOPS_ALLOW='WRITE_SETTINGS:23 REQUEST_INSTALL_PACKAGES:26'
SUPPORTED_MODELS='denver/PFF_1024 denver/PFF-1515'
MIN_SDK='23'
SUPPORTED_ABIS='armeabi-v7a arm64-v8a'
SET_HOME_MIN_SDK='24'
WORK_DIR='/data/local/tmp/kinframe-convert'
INSTALL_NEWER_PRESENT='INSTALL_FAILED_VERSION_DOWNGRADE'
BOOT_BRAND='kinframe'
BOOT_ASSETS='/frame-boot'
BOOT_ANIMATION='/system/media/bootanimation.zip'
RESOURCE_BACKUP='resource.img'
BOOT_ANIMATION_BACKUP='bootanimation.zip'
SETTLE_SECONDS='15'
ROOT_CERT_FILE='6187b673.0'
ROOT_CERT='/system/etc/security/cacerts/6187b673.0'
ROOT_CERT_ASSETS='/frame-certs'
ROOT_CERT_SHA256='b3dce5684d06bf72f8d998ee2245c933df91443a7348eef6d57477796f13da14'
ROOT_CERT_BEFORE_SDK='24'
# Command templates: {name} in plan.ts is $name here (filled by fill(), never by the shell).
CMD_getprop='getprop'
CMD_rootCheck='su 0 id'
CMD_packagePath='pm path $pkg'
CMD_packageInfo='dumpsys package $pkg'
CMD_bootCompleted='getprop sys.boot_completed'
CMD_activities='dumpsys activity activities'
CMD_screenSize='wm size'
CMD_checkRootCert='ls /system/etc/security/cacerts/6187b673.0'
CMD_listDisabled='pm list packages -d'
CMD_listPackages='pm list packages'
CMD_readState='cat /data/local/tmp/kinframe-state.txt'
CMD_listRecovery='su 0 ls /cache/recovery'
CMD_readRecoveryCommand='su 0 cat /cache/recovery/command'
CMD_readUpdateResult='su 0 cat /data/data/com.adups.fota/files/updateResult'
CMD_readAdupsPrefs='su 0 cat /data/data/com.adups.fota/shared_prefs/adupsfota.xml'
CMD_listAdupsInternal='su 0 ls /data/data/com.adups.fota/files/adupsfota'
CMD_listAdupsExternal='ls -d /storage/*/Android/data/com.adups.fota/files/adupsfota /storage/emulated/0/Android/data/com.adups.fota/files/adupsfota /storage/*/adupsfota /storage/emulated/0/adupsfota'
CMD_checkPath='ls -d $path'
CMD_mkWork='mkdir -p $work'
CMD_listData='su 0 ls -lnRa /data/data/$pkg'
CMD_copyData='su 0 cp -a /data/data/$pkg $work/$pkg'
CMD_pruneData='su 0 rm -rf $work/$pkg/cache $work/$pkg/code_cache $work/$pkg/app_webview $work/$pkg/lib $work/$pkg/files/.crashlytics.v3'
CMD_readableData='su 0 chmod -R a+rX $work/$pkg'
CMD_removeDataCopy='su 0 rm -rf $work/$pkg'
CMD_install='pm install -r -g $work/kinframe.apk'
CMD_grant='pm grant $frame $perm'
CMD_appop='appops set $frame $op allow'
CMD_disable='su 0 pm disable-user $pkg'
CMD_settle='sleep $seconds'
CMD_forceStop='am force-stop $pkg'
CMD_clearData='pm clear $pkg'
CMD_removePath='rm -rf $path'
CMD_removePathRoot='su 0 rm -rf $path'
CMD_removeRecoveryCommand='su 0 rm -f /cache/recovery/command'
CMD_removeState='rm -f /data/local/tmp/kinframe-state.txt'
CMD_setHome='pm set-home-activity $activity'
CMD_cleanup='rm -rf $work'
CMD_provision='am start -n $activity --es server_url $server'
CMD_provisionLanguage='am start -n $activity --es server_url $server --es language $language'
CMD_restoreFrameo='pm install -r -d $work/restore.apk'
CMD_enable='su 0 pm enable $pkg'
CMD_uninstall='pm uninstall $frame'
CMD_uninstallKeepData='pm uninstall -k $frame'
CMD_uninstallRoot='su 0 pm uninstall $frame'
CMD_findResource='ls /dev/block/platform/*/by-name/resource'
CMD_mounts='mount'
CMD_readResource='su 0 dd if=$resource of=$work/$file bs=1048576'
CMD_readable='su 0 chmod 644 $work/$file'
CMD_writeResource='su 0 dd if=$work/$file of=$resource bs=1048576'
CMD_sync='su 0 sync'
CMD_cleanReboot='su 0 svc power reboot'
CMD_flushResource='su 0 blockdev --flushbufs $resource'
CMD_removeFile='su 0 rm -f $work/$file'
CMD_remountRw='su 0 mount -o remount,rw $system /system'
CMD_copyBootAnimation='su 0 cp $work/bootanimation.zip /system/media/bootanimation.zip'
CMD_chmodBootAnimation='su 0 chmod 644 /system/media/bootanimation.zip'
CMD_remountRo='su 0 mount -o remount,ro $system /system'
CMD_sha1BootAnimation='sha1sum /system/media/bootanimation.zip'
CMD_copyRootCert='su 0 cp $work/6187b673.0 /system/etc/security/cacerts/6187b673.0'
CMD_chmodRootCert='su 0 chmod 644 /system/etc/security/cacerts/6187b673.0'
CMD_chownRootCert='su 0 chown 0:0 /system/etc/security/cacerts/6187b673.0'
CMD_sha1RootCert='sha1sum /system/etc/security/cacerts/6187b673.0'
CMD_copyHosts='su 0 cp $work/hosts /system/etc/hosts'
CMD_chmodHosts='su 0 chmod 644 /system/etc/hosts'
CMD_chownHosts='su 0 chown 0:0 /system/etc/hosts'
CMD_sha1Hosts='sha1sum /system/etc/hosts'
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
BOOT=1
BOOT_DIR=''
ROOT_CERT_WANTED=1
KEEP_EXTRAS=0
RESTORE_RESOURCE=''
RESTORE_BOOTANIMATION=''
OFFLINE=''
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
  --no-boot-logo       Leave the start-up logo and animation alone. Otherwise they are replaced with kinframe's,
                       after the originals are saved to the backup directory (also with --no-backup).
                       The logo lives in a boot partition: it is read back and checked, and on any mismatch the
                       original is written back at once.
  --boot-assets DIR    Use the start-up logos and animations in DIR (manifest.json and its files, for example
                       web/static/frame-boot/kinframe in a checkout) instead of downloading them.
  --keep-extras        Leave the preinstalled apps a photo frame doesn't use (browser, music, live wallpapers,
                       factory test apps, ...) enabled. By default they are turned off, and --revert turns them on.
  --edit-hosts block|unblock IN OUT  No frame needed: write OUT, which is the hosts file IN with kinframe's block
                       added or removed.
  --no-root-cert       Don't add the Let's Encrypt root certificate to /system (only done on Android 6 frames
                       that lack it).
  --full-backup        Also copy every eMMC partition except user data (slow, read-only).
  --backup-dir DIR     Where backups go (default: ./kinframe-backup-<serial>-<date>).
  --apk FILE           Install this kinframe APK instead of downloading the latest release.
  --cert SHA256        Expected signing certificate (default: the kinframe release certificate).
  --keep-data          With --revert: keep kinframe's data on the frame.
  --restore-apk FILE   With --revert: reinstall this backed-up Frameo APK first.
  --restore-resource FILE       With --revert: write this backed-up resource.img (start-up logo) back.
  --restore-bootanimation FILE  With --revert: put this backed-up bootanimation.zip back.
  --check-resource FILE         No frame needed: check a resource image and list its entries.
  --patch-resource IN LOGO OUT  No frame needed: write OUT, which is IN with its boot logos replaced by LOGO.
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
	--no-boot-logo) BOOT=0 ;;
	--boot-assets) [ $# -ge 2 ] || die_usage "--boot-assets needs a directory"; BOOT_DIR=$2; shift ;;
	--no-root-cert) ROOT_CERT_WANTED=0 ;;
	--keep-extras) KEEP_EXTRAS=1 ;;
	--edit-hosts)
		[ $# -ge 4 ] || die_usage "--edit-hosts needs block|unblock IN OUT"
		case $2 in block | unblock) ;; *) die_usage "--edit-hosts needs block or unblock" ;; esac
		OFFLINE='hosts'; OFF_MODE=$2; OFF_IN=$3; OFF_OUT=$4
		shift 3
		;;
	--restore-resource) [ $# -ge 2 ] || die_usage "--restore-resource needs a file"; RESTORE_RESOURCE=$2; shift ;;
	--restore-bootanimation) [ $# -ge 2 ] || die_usage "--restore-bootanimation needs a file"; RESTORE_BOOTANIMATION=$2; shift ;;
	--check-resource) [ $# -ge 2 ] || die_usage "--check-resource needs a file"; OFFLINE='check'; OFF_IN=$2; shift ;;
	--patch-resource)
		[ $# -ge 4 ] || die_usage "--patch-resource needs IN LOGO OUT"
		OFFLINE='patch'; OFF_IN=$2; OFF_LOGO=$3; OFF_OUT=$4
		shift 3
		;;
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

[ -n "$OFFLINE" ] && LOG_FILE=/dev/null

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

# ---------------------------------------------------------------- resource images (boot logo)
#
# The resource partition of Rockchip frames is an "RSCE" image: a header block, one 512-byte block per entry
# ("ENTR", a 256-byte name, u32 content offset in blocks, u32 size in bytes, optional u32 hash size + hash) and
# the contents, each on a block boundary. Same rules as web/src/lib/convert/rsce.ts (rsce.test.ts and plan.test.ts
# check this implementation against it).

# u8 FILE OFFSET / u32 FILE OFFSET: unsigned little-endian numbers, byte by byte (independent of the host's order).
u8() { od -An -tu1 -j "$2" -N1 "$1" | tr -d ' \n'; }
u32() {
	# shellcheck disable=SC2046 # split the four numbers into $1..$4
	set -- $(od -An -tu1 -j "$2" -N4 "$1")
	echo $(($1 + $2 * 256 + $3 * 65536 + $4 * 16777216))
}
# put_u32 FILE OFFSET VALUE
put_u32() {
	_pv=$3
	_bytes=''
	for _k in 1 2 3 4; do
		_bytes="$_bytes\\$(printf '%03o' $((_pv % 256)))"
		_pv=$((_pv / 256))
	done
	# shellcheck disable=SC2059 # the format is the octal escapes built above
	printf "$_bytes" | dd of="$1" bs=1 seek="$2" conv=notrunc 2>/dev/null
}
size_of() { wc -c <"$1" | tr -d ' '; }
blocks_of() { echo $((($1 + 511) / 512)); }
# entry_name FILE OFFSET: the NUL-terminated name at OFFSET.
entry_name() { dd if="$1" bs=1 skip="$2" count=256 2>/dev/null | tr '\000' '\n' | head -n 1; }
# extract FILE BLOCK SIZE OUT: an entry's content.
extract() { dd if="$1" bs=512 skip="$2" count="$(blocks_of "$3")" 2>/dev/null | head -c "$3" >"$4"; }

# rsce_parse FILE: checks a resource image like parseResource(); sets RS_N, RS_TABLE, RS_ESIZE, RS_CONTENT and
# RS_NAME_i, RS_BLOCK_i, RS_SIZE_i, RS_HASH_i. Returns 1 with the reason in RS_PROBLEM.
rsce_parse() {
	RS_PROBLEM=''
	_len=$(size_of "$1")
	if [ "$_len" -lt 512 ] || [ "$(dd if="$1" bs=1 count=4 2>/dev/null)" != RSCE ]; then
		RS_PROBLEM='not a Rockchip resource image (no RSCE header)'
		return 1
	fi
	_hs=$(u8 "$1" 8)
	RS_TABLE=$(u8 "$1" 9)
	RS_ESIZE=$(u8 "$1" 10)
	RS_N=$(u32 "$1" 12)
	if [ "$_hs" -lt 1 ] || [ "$RS_ESIZE" -lt 1 ] || [ "$RS_TABLE" -lt "$_hs" ]; then RS_PROBLEM='bad RSCE header'; return 1; fi
	if [ "$RS_N" -lt 1 ] || [ "$RS_N" -gt 256 ]; then RS_PROBLEM="bad RSCE entry count $RS_N"; return 1; fi
	RS_CONTENT=$((RS_TABLE + RS_N * RS_ESIZE))
	if [ $((RS_CONTENT * 512)) -gt "$_len" ]; then RS_PROBLEM='RSCE entry table is cut off'; return 1; fi
	_i=0
	while [ $_i -lt "$RS_N" ]; do
		_o=$(((RS_TABLE + _i * RS_ESIZE) * 512))
		if [ "$(dd if="$1" bs=1 skip=$_o count=4 2>/dev/null)" != ENTR ]; then RS_PROBLEM="RSCE entry $_i has no ENTR tag"; return 1; fi
		_name=$(entry_name "$1" $((_o + 4)))
		case $_name in '' | *[!!-~]*) RS_PROBLEM="RSCE entry $_i has a bad name"; return 1 ;; esac
		_blk=$(u32 "$1" $((_o + 260)))
		_sz=$(u32 "$1" $((_o + 264)))
		_hash=0
		[ $((RS_ESIZE * 512)) -ge 272 ] && _hash=$(u32 "$1" $((_o + 268)))
		if [ "$_blk" -lt "$RS_CONTENT" ] || [ $((_blk * 512 + _sz)) -gt "$_len" ]; then RS_PROBLEM="RSCE entry $_name is out of bounds"; return 1; fi
		eval "RS_NAME_$_i=\$_name RS_BLOCK_$_i=\$_blk RS_SIZE_$_i=\$_sz RS_HASH_$_i=\$_hash"
		_i=$((_i + 1))
	done
	return 0
}

# check_resource FILE: valid, with a device tree, whole blocks (resourceProblem()). Reason in RS_PROBLEM.
check_resource() {
	rsce_parse "$1" || return 1
	_i=0
	while [ $_i -lt "$RS_N" ]; do
		eval "_name=\$RS_NAME_$_i"
		case $_name in *.dtb) [ $(($(size_of "$1") % 512)) = 0 ] && return 0; RS_PROBLEM='the resource image is not a whole number of 512-byte blocks'; return 1 ;; esac
		_i=$((_i + 1))
	done
	RS_PROBLEM='no device tree (.dtb) in the resource image'
	return 1
}

# bmp_format FILE [OFFSET]: "width height bpp compression" of a BMP, or nothing.
bmp_format() {
	_b=${2:-0}
	[ "$(dd if="$1" bs=1 skip="$_b" count=2 2>/dev/null)" = BM ] || return 1
	[ "$(u32 "$1" $((_b + 14)))" -ge 40 ] || return 1
	printf '%s %s %s %s\n' "$(u32 "$1" $((_b + 18)))" "$(u32 "$1" $((_b + 22)))" "$(od -An -tu2 -j $((_b + 28)) -N2 "$1" | tr -d ' ')" "$(u32 "$1" $((_b + 30)))"
}

# resource_logo_format FILE: "width height bpp compression" of the first logo entry of a resource image that is a
# BMP (resourceLogoFormat()), or nothing.
resource_logo_format() {
	check_resource "$1" || return 1
	for _want in logo.bmp logo_kernel.bmp; do
		_i=0
		while [ $_i -lt "$RS_N" ]; do
			eval "_name=\$RS_NAME_$_i _blk=\$RS_BLOCK_$_i"
			if [ "$_name" = "$_want" ] && bmp_format "$1" $((_blk * 512)); then return 0; fi
			_i=$((_i + 1))
		done
	done
	return 1
}

# brand_resource IN LOGO OUT: brandResource(): IN with logo.bmp and logo_kernel.bmp replaced by LOGO, which must
# have the same size, depth and compression. Contents are laid out again from the first block after the table, in
# table order; the image keeps its length. Returns 1 with the reason in RS_PROBLEM.
brand_resource() {
	check_resource "$1" || return 1
	_new=$(bmp_format "$2") || { RS_PROBLEM='the new logo is not a BMP file'; return 1; }
	_len=$(size_of "$1")
	_found=0
	dd if=/dev/zero of="$3" bs=512 count=$((_len / 512)) 2>/dev/null || { RS_PROBLEM="cannot write $3"; return 1; }
	dd if="$1" of="$3" bs=512 count="$RS_CONTENT" conv=notrunc 2>/dev/null
	_at=$RS_CONTENT
	_i=0
	while [ $_i -lt "$RS_N" ]; do
		eval "_name=\$RS_NAME_$_i _blk=\$RS_BLOCK_$_i _sz=\$RS_SIZE_$_i _hash=\$RS_HASH_$_i"
		_src="$TMP/entry"
		case $_name in
		logo.bmp | logo_kernel.bmp)
			_old=$(bmp_format "$1" $((_blk * 512))) || { RS_PROBLEM="$_name on the frame is not a BMP file"; return 1; }
			[ "$_old" = "$_new" ] || { RS_PROBLEM="$_name on the frame is $_old (width height bits compression); the new logo is $_new"; return 1; }
			[ "$_hash" = 0 ] || { RS_PROBLEM="$_name carries a hash; replacing it is not supported"; return 1; }
			_src=$2
			_found=1
			;;
		*) extract "$1" "$_blk" "$_sz" "$_src" ;;
		esac
		_sz=$(size_of "$_src")
		[ $((_at * 512 + _sz)) -le "$_len" ] || { RS_PROBLEM='the new resource image does not fit in the partition'; return 1; }
		dd if="$_src" of="$3" bs=512 seek="$_at" conv=notrunc 2>/dev/null
		_o=$(((RS_TABLE + _i * RS_ESIZE) * 512))
		put_u32 "$3" $((_o + 260)) "$_at"
		put_u32 "$3" $((_o + 264)) "$_sz"
		_at=$((_at + $(blocks_of "$_sz")))
		_i=$((_i + 1))
	done
	[ $_found = 1 ] || { RS_PROBLEM='no boot logo in the resource image'; return 1; }
	return 0
}

# hosts_edit block|unblock IN OUT: OUT is the hosts file IN without kinframe's block (removeHostsBlock() in plan.ts:
# the lines from HOSTS_BEGIN to HOSTS_END; an unterminated block ends at the first line that isn't ours), plus the
# block at the end when blocking (addHostsBlock()). Everything else is kept.
hosts_edit() {
	awk -v b="$HOSTS_BEGIN" -v e="$HOSTS_END" -v hosts="$ADUPS_HOSTS" '
		BEGIN { n = split(hosts, h, " "); for (i = 1; i <= n; i++) ours["0.0.0.0 " h[i]] = 1 }
		$0 == b { inb = 1; next }
		inb && $0 == e { inb = 0; next }
		inb && ($0 in ours) { next }
		{ inb = 0; print }
	' "$2" >"$3" || return 1
	if [ "$1" = block ]; then
		{
			printf '%s\n' "$HOSTS_BEGIN"
			for _h in $ADUPS_HOSTS; do printf '0.0.0.0 %s\n' "$_h"; done
			printf '%s\n' "$HOSTS_END"
		} >>"$3"
	fi
}

# Offline modes: no frame, no adb.
case $OFFLINE in
check)
	if check_resource "$OFF_IN"; then
		_i=0
		while [ $_i -lt "$RS_N" ]; do
			eval "printf '%s\tblock %s\t%s bytes\n' \"\$RS_NAME_$_i\" \"\$RS_BLOCK_$_i\" \"\$RS_SIZE_$_i\""
			_i=$((_i + 1))
		done
		exit 0
	fi
	printf 'kinframe-convert: %s: %s\n' "$OFF_IN" "$RS_PROBLEM" >&2
	exit 1
	;;
patch)
	brand_resource "$OFF_IN" "$OFF_LOGO" "$OFF_OUT" && exit 0
	printf 'kinframe-convert: %s\n' "$RS_PROBLEM" >&2
	exit 1
	;;
hosts)
	[ -f "$OFF_IN" ] || { printf 'kinframe-convert: no such file: %s\n' "$OFF_IN" >&2; exit 1; }
	hosts_edit "$OFF_MODE" "$OFF_IN" "$OFF_OUT" && exit 0
	exit 1
	;;
esac

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

# Restarts the frame. With root: Android's own restart (svc power reboot), which tells the apps first so kinframe
# saves its clean-shutdown marker (adb reboot skips that and the frame then reports a power loss). Falls back to
# adb reboot when the frame is still there after 30 seconds.
restart_frame() {
	if [ "$ROOT" = 1 ]; then
		log "\$ $CMD_cleanReboot"
		adb_ shell "$CMD_cleanReboot" >>"$LOG_FILE" 2>&1
		_i=0
		while [ $_i -lt 30 ] && [ "$(adb_ get-state 2>/dev/null)" = device ]; do
			sleep 1
			_i=$((_i + 1))
		done
		[ $_i -lt 30 ] && return 0
		log "the frame did not restart by itself"
	fi
	log "\$ adb reboot"
	adb_ reboot >>"$LOG_FILE" 2>&1
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
	rd "$(fill "$CMD_findResource")"
	RESOURCE_DEV=$(printf '%s\n' "$OUT" | grep -E '^/dev/block/platform/[^/[:space:]]+/by-name/resource$' | head -n 1)
	log "\$ $CMD_mounts"
	SYSTEM_DEV=$(adb_sh "$CMD_mounts" | awk '$2 == "/system" { print $1; exit } $3 == "/system" && $2 == "on" { print $1; exit }')
	rd "$(fill "$CMD_screenSize")"
	SCREEN=$(printf '%s\n' "$OUT" | sed -n 's/.*Physical size: *\([0-9]*x[0-9]*\).*/\1/p' | head -n 1)
	HWROTATION=$(getp ro.sf.hwrotation)
	case $HWROTATION in '' | *[!0-9]*) HWROTATION=0 ;; esac
	rd "$(fill "$CMD_checkRootCert")"
	if printf '%s\n' "$OUT" | grep -qxF "$ROOT_CERT"; then ROOT_CERT_STATE=present
	elif contains "$OUT" 'No such file'; then ROOT_CERT_STATE=missing
	else ROOT_CERT_STATE=unknown
	fi
	BOOT_LOGO_OK=0
	BOOT_ANIM_OK=0
	if [ $ROOT = 1 ] && [ -n "$RESOURCE_DEV" ]; then BOOT_LOGO_OK=1; fi
	case $SYSTEM_DEV in /dev/block/dm-*) ;; /dev/block/*) [ $ROOT = 1 ] && BOOT_ANIM_OK=1 ;; esac
	# rootCertNeeded(): Android before 7, the Let's Encrypt root known to be missing, /system changeable.
	ROOT_CERT_OK=0
	if [ "$SDK" -lt "$ROOT_CERT_BEFORE_SDK" ] && [ $ROOT_CERT_STATE = missing ] && [ $BOOT_ANIM_OK = 1 ]; then ROOT_CERT_OK=1; fi

	say "  device:      $BRAND $MODEL ($KEY), $(getp ro.product.manufacturer), $(getp ro.board.platform)"
	say "  android:     $(getp ro.build.version.release) (API $SDK), ABIs: $ABIS, root: $([ $ROOT = 1 ] && echo yes || echo no)"
	say "  boot:        resource ${RESOURCE_DEV:-none}, /system on ${SYSTEM_DEV:-?}, screen ${SCREEN:-?}, hwrotation $HWROTATION"
	say "  CA store:    Let's Encrypt root $ROOT_CERT_STATE"
	say "  fingerprint: $(getp ro.build.fingerprint)"
	say "  firmware:    $(getp ro.build.display.id), updater version $(getp ro.fota.version)"
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
	case ${FRAMEO_PATHS:-} in /system/*) say "  Frameo:      the copy in /system" ;; '') ;; *) say "  Frameo:      updated, in /data/app" ;; esac
	UPDATERS_INSTALLED=''
	for _p in $DISABLE_PACKAGES; do case $(state_of "$_p") in 1:*) UPDATERS_INSTALLED="$UPDATERS_INSTALLED $_p" ;; esac; done
	identify_more

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

# names OUTPUT: the names an `ls` listing gives, one per line, without its error lines (parseNames()).
names() { printf '%s\n' "$1" | sed 's/^ *//; s/ *$//' | grep -v -E '^ls:|No such file|Permission denied|not found|^$'; }
# file_content OUTPUT: a file's content from `cat`, or nothing when it is missing (parseFile()).
file_content() { case $1 in *'No such file'* | *'Permission denied'* | cat:*) ;; *) printf '%s' "$1" ;; esac; }
# has_line TEXT LINE
has_line() { printf '%s\n' "$1" | grep -qxF "$2"; }
# pref_string XML NAME: a <string> of a SharedPreferences file, unescaped.
pref_string() {
	printf '%s\n' "$1" | sed -n "s/.*<string name=\"$2\">\([^<]*\)<\/string>.*/\1/p" | head -n 1 |
		sed 's/&lt;/</g; s/&gt;/>/g; s/&quot;/"/g; s/&apos;/'"'"'/g; s/&amp;/\&/g'
}

# identify_more: the package lists, the extras record and the firmware updater's leftovers (updaterLeftovers(),
# describeUpdater() in plan.ts). The updater's preferences hold a device id, so they are summarised, not logged.
identify_more() {
	_raw=$(adb_sh "$(fill "$CMD_listPackages")")
	log "\$ $CMD_listPackages ($(printf '%s\n' "$_raw" | grep -c '^package:') packages)"
	INSTALLED_LIST=$(printf '%s\n' "$_raw" | sed -n 's/^package:\([^ ]*\).*/\1/p')
	rd "$(fill "$CMD_listDisabled")"
	DISABLED_LIST=$(printf '%s\n' "$OUT" | sed -n 's/^package:\([^ ]*\).*/\1/p')
	rd "$(fill "$CMD_readState")"
	EXTRAS_STATE=''
	STATE_EXISTS=0
	if [ -n "$OUT" ] && ! contains "$OUT" 'No such file' && ! contains "$OUT" 'cat:'; then
		STATE_EXISTS=1
		for _p in $EXTRA_PACKAGES; do has_line "$OUT" "$_p" && EXTRAS_STATE="$EXTRAS_STATE $_p"; done
	fi
	rd "$(fill "$CMD_listRecovery")"
	RECOVERY_LIST=$(names "$OUT")
	rd "$(fill "$CMD_readRecoveryCommand")"
	RECOVERY_COMMAND=$(file_content "$OUT")
	rd "$(fill "$CMD_readUpdateResult")"
	UPDATE_RESULT=$(file_content "$OUT")
	_xml=$(adb_sh "$(fill "$CMD_readAdupsPrefs")")
	log "\$ $CMD_readAdupsPrefs ($(printf '%s\n' "$_xml" | grep -c 'name=') entries)"
	UPDATER_STATUS=$(printf '%s\n' "$_xml" | sed -n 's/.*<[a-z]* name="ota_update_status" value="\(-\{0,1\}[0-9]*\)".*/\1/p' | head -n 1)
	UPDATE_PATH=$(pref_string "$_xml" update_package_path)
	rd "$(fill "$CMD_listAdupsInternal")"
	ADUPS_INTERNAL=$(names "$OUT")
	rd "$(fill "$CMD_listAdupsExternal")"
	ADUPS_DOWNLOADS=''
	ADUPS_OTHER=''
	for _d in $(names "$OUT" | grep '^/storage/' | sort -u); do
		safe_arg "$_d" || { log "skipping an unusual path: $_d"; continue; }
		case $_d in */Android/data/com.adups.fota/*) ADUPS_DOWNLOADS="$ADUPS_DOWNLOADS $_d" ;; *) ADUPS_OTHER="$ADUPS_OTHER $_d" ;; esac
	done
	EXTRAS_TODO=''
	for _p in $EXTRA_PACKAGES; do
		has_line "$INSTALLED_LIST" "$_p" && ! has_line "$DISABLED_LIST" "$_p" && EXTRAS_TODO="$EXTRAS_TODO $_p"
	done

	case $UPDATER_STATUS in
	'') _st=unknown ;; 0) _st=idle ;; 1) _st='new version found' ;; 2) _st=downloading ;; 3) _st=paused ;; 4) _st='update downloaded' ;; *) _st="state $UPDATER_STATUS" ;;
	esac
	say "  updater:     $_st${UPDATE_PATH:+, update at $UPDATE_PATH}"
	say "  recovery:    /cache/recovery: $(printf '%s' "$RECOVERY_LIST" | tr '\n' ' ' | sed 's/ $//; s/ /, /g')${RECOVERY_COMMAND:+ (command: $(printf '%s' "$RECOVERY_COMMAND" | tr '\n' ' '))}"
	[ -n "$UPDATE_RESULT" ] && say "  last update result: $(printf '%s' "$UPDATE_RESULT" | tr '\n' ' ')"
	[ -n "$ADUPS_INTERNAL" ] && say "  internal downloads: $(printf '%s' "$ADUPS_INTERNAL" | tr '\n' ' ')"
	[ -n "$ADUPS_DOWNLOADS" ] && say "  download folders:$ADUPS_DOWNLOADS"
	[ -n "$ADUPS_OTHER" ] && say "  update folders on storage:$ADUPS_OTHER"
	say "  extras:      to turn off:${EXTRAS_TODO:- none}; record: $([ $STATE_EXISTS = 1 ] && echo "${EXTRAS_STATE:- empty}" || echo none)"
	UPDATE_PENDING=0
	case $UPDATER_STATUS in 1 | 2 | 3 | 4) UPDATE_PENDING=1 ;; esac
	[ -n "$UPDATE_PATH" ] && UPDATE_PENDING=1
	printf '%s\n' "$ADUPS_INTERNAL" | grep -q '\.zip$' && UPDATE_PENDING=1
	has_line "$RECOVERY_LIST" command && UPDATE_PENDING=1
	if [ $UPDATE_PENDING = 1 ]; then
		warn "the firmware updater has a pending or downloaded update; converting clears it and turns the updater off"
	fi
	return 0
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
			if ! backup_data "$FRAMEO_PACKAGE" "$BACKUP_DIR/$FRAMEO_PACKAGE-data.tgz" "$BACKUP_DIR/$FRAMEO_PACKAGE-data-files.txt"; then
				DATA_WARNING="could not back up the data of $FRAMEO_PACKAGE ($DATA_PROBLEM). It stays on the frame: converting only turns the app off, and --revert turns it on again with its data."
				warn "$DATA_WARNING"
			fi
			wr "$(fill "$CMD_cleanup")"
		fi
	fi
	[ "$FULL_BACKUP" = 1 ] && full_backup
	[ "$DRY_RUN" = 1 ] || say "  saved in $BACKUP_DIR"
}

# archive_tree PARENT NAME DEST: DEST is a .tgz of PARENT/NAME with names NAME/…, made with the computer's tar
# (GNU tar and bsdtar store long names). Returns 1 with the reason in DATA_PROBLEM.
archive_tree() {
	[ -d "$1/$2" ] || { DATA_PROBLEM="nothing was copied from the frame"; return 1; }
	_n=$(find "$1/$2" -type f | wc -l | tr -d ' ')
	[ "$_n" -gt 0 ] || { DATA_PROBLEM="no files were copied from the frame"; return 1; }
	command -v tar >/dev/null 2>&1 || { DATA_PROBLEM="tar not found on this computer (the copy is in $1/$2)"; return 1; }
	tar -czf "$3" -C "$1" "$2" 2>>"$LOG_FILE" || { DATA_PROBLEM="tar failed on this computer"; return 1; }
	say "  $_n files archived as ${3##*/}"
	return 0
}

# backup_data PKG DEST LIST: the app data as a .tgz (backupData() in runner.ts). Toybox tar on some frames can't store
# names over 100 bytes, so nothing is archived on the frame: the data is copied as root into the work directory
# (without caches, WebView data, crash reports and the lib symlink), made readable, pulled as a directory and
# archived here; then the copy is removed. LIST gets `ls -lnRa` of the original (modes and numeric owners, for a
# restore). Returns 1 with the reason in DATA_PROBLEM; DATA_NOTE when some files could not be copied.
backup_data() {
	DATA_PROBLEM=''
	DATA_NOTE=''
	wr "$(fill "$CMD_mkWork")"
	wr "$(fill "$CMD_listData" "pkg=$1")"
	if [ "$DRY_RUN" = 0 ] && [ -n "$OUT" ] && ! contains "$OUT" 'No such file'; then printf '%s\n' "$OUT" >"$3"; fi
	wr "$(fill "$CMD_copyData" "pkg=$1")"
	_copy=$OUT
	wr "$(fill "$CMD_pruneData" "pkg=$1")"
	wr "$(fill "$CMD_readableData" "pkg=$1")"
	_pulled="$TMP/data-$1"
	if [ "$DRY_RUN" = 1 ]; then
		say "  would copy: $WORK_DIR/$1 -> $_pulled, and archive it as $2"
	else
		mkdir -p "$_pulled"
		log "pull $WORK_DIR/$1 -> $_pulled/"
		adb_ pull "$WORK_DIR/$1" "$_pulled/" >>"$LOG_FILE" 2>&1
		# adb puts the directory inside the existing target; should one put its contents there instead, nest them.
		if [ ! -d "$_pulled/$1" ] && [ -n "$(ls -A "$_pulled")" ]; then
			mkdir -p "$_pulled.nest" && mv "$_pulled" "$_pulled.nest/$1" && _pulled="$_pulled.nest"
		fi
	fi
	wr "$(fill "$CMD_removeDataCopy" "pkg=$1")"
	[ "$DRY_RUN" = 1 ] && return 0
	archive_tree "$_pulled" "$1" "$2" || return 1
	_bad=$(printf '%s\n' "$_copy" | grep -E 'Error|Exception|not found|denied|Failure|not permitted|Read-only file system|No such file' | head -n 1)
	if [ -n "$_bad" ]; then
		DATA_NOTE="some files of $1 could not be copied ($_bad); the backup has the rest"
		warn "$DATA_NOTE"
	fi
	return 0
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

# ---------------------------------------------------------------- start-up branding (boot logo and animation)

sha1_of() {
	if command -v sha1sum >/dev/null 2>&1; then sha1sum "$1" | cut -d' ' -f1
	elif command -v shasum >/dev/null 2>&1; then shasum -a 1 "$1" | cut -d' ' -f1
	else openssl dgst -sha1 "$1" | sed 's/.*= *//'
	fi
}

ensure_backup_dir() {
	[ -n "$BACKUP_DIR" ] || BACKUP_DIR="kinframe-backup-${DEV_SERIAL:-frame}-$(date +%Y%m%d-%H%M%S)"
	[ "$DRY_RUN" = 1 ] || mkdir -p "$BACKUP_DIR" || fail "cannot create $BACKUP_DIR"
}

# read_resource NAME DEST: copies the resource partition to DEST via a file in the work directory.
read_resource() {
	wr "$(fill "$CMD_mkWork")"
	wr "$(fill "$CMD_readResource" "resource=$RESOURCE_DEV" "file=$1")"
	[ "$DRY_RUN" = 1 ] || contains "$OUT" 'records out' || return 1
	wr "$(fill "$CMD_readable" "file=$1")"
	pull "$WORK_DIR/$1" "$2"
}

# The originals, before anything changes: the boot animation and the whole resource partition.
boot_backup() {
	ensure_backup_dir
	step "Back up the start-up logo and animation ($BACKUP_DIR)"
	if [ "$BOOT_ANIM_OK" = 1 ]; then
		pull "$BOOT_ANIMATION" "$BACKUP_DIR/$BOOT_ANIMATION_BACKUP" || fail "could not copy $BOOT_ANIMATION"
	fi
	if [ "$BOOT_LOGO_OK" = 1 ]; then
		read_resource "$RESOURCE_BACKUP" "$BACKUP_DIR/$RESOURCE_BACKUP" || fail "could not copy the resource partition $RESOURCE_DEV"
		wr "$(fill "$CMD_removeFile" "file=$RESOURCE_BACKUP")"
		if [ "$DRY_RUN" = 0 ]; then
			check_resource "$BACKUP_DIR/$RESOURCE_BACKUP" || fail "the resource partition is not a resource image we know ($RS_PROBLEM). Run again with --no-boot-logo."
		fi
	fi
	[ "$DRY_RUN" = 1 ] || say "  saved in $BACKUP_DIR"
}

# mf ENTRY KEY: a field of a one-line manifest entry ({"file": "…", "width": 800, …}).
mf() { printf '%s\n' "$1" | sed -n "s/.*\"$2\": *\"\{0,1\}\([^\",}]*\).*/\1/p"; }

# boot_get BASE FILE DEST: a file of the start-up branding, from --boot-assets or the server at BASE.
boot_get() {
	if [ -n "$BOOT_DIR" ]; then cp "$BOOT_DIR/$2" "$3" 2>>"$LOG_FILE"
	else curl -fsSL -o "$3" "$1/$2" 2>>"$LOG_FILE"
	fi
}

# boot_pick BASE: picks and fetches the variants for this frame from BASE's manifest.json into $BOOT_FILES/logo.bmp
# and bootanimation.zip, like the runner's fetchBoot (pickBootLogo(), pickBootAnimation() in plan.ts): the logo with
# exactly the format of the frame's own (from the backed-up resource image) made for its panel rotation, the animation
# for its screen size (either orientation). Each is checked against the manifest's sha256. Returns 1 with the reason
# in BOOT_PROBLEM.
boot_pick() {
	BOOT_PROBLEM=''
	boot_get "$1" manifest.json "$BOOT_FILES/manifest.json" || { BOOT_PROBLEM="no manifest.json at $1"; return 1; }
	if [ "$BOOT_LOGO_OK" = 1 ]; then
		if [ "$DRY_RUN" = 1 ]; then
			say "  the boot logo is picked once the frame's own logo has been read"
		else
			_fmt=$(resource_logo_format "$BACKUP_DIR/$RESOURCE_BACKUP") || { BOOT_PROBLEM='no boot logo in the resource image'; return 1; }
			_entry=''
			while IFS= read -r _line; do
				case $_line in *'"hwrotation"'*) ;; *) continue ;; esac
				if [ "$(mf "$_line" width) $(mf "$_line" height) $(mf "$_line" bpp) $(mf "$_line" compression) $(mf "$_line" hwrotation)" = "$_fmt $HWROTATION" ]; then
					_entry=$_line
					break
				fi
			done <"$BOOT_FILES/manifest.json"
			if [ -z "$_entry" ]; then
				BOOT_PROBLEM="no kinframe boot logo for this frame (its logo is $_fmt as width height bits compression, panel rotation $HWROTATION)"
				return 1
			fi
			say "  the frame's logo is $_fmt (width height bits compression), rotation $HWROTATION: using $(mf "$_entry" file)"
			boot_get "$1" "$(mf "$_entry" file)" "$BOOT_FILES/logo.bmp" || { BOOT_PROBLEM="could not get $(mf "$_entry" file)"; return 1; }
			[ "$(sha256_of "$BOOT_FILES/logo.bmp")" = "$(mf "$_entry" sha256)" ] || { BOOT_PROBLEM="$(mf "$_entry" file) does not match its sha256"; return 1; }
			bmp_format "$BOOT_FILES/logo.bmp" >/dev/null || { BOOT_PROBLEM='the boot logo is not a BMP file'; return 1; }
		fi
	fi
	if [ "$BOOT_ANIM_OK" = 1 ]; then
		_entry=''
		if [ -n "$SCREEN" ]; then
			for _want in "$SCREEN" "${SCREEN#*x}x${SCREEN%%x*}"; do
				_entry=$(grep -F "\"screen\": \"$_want\"" "$BOOT_FILES/manifest.json" | head -n 1)
				[ -n "$_entry" ] && break
			done
		fi
		[ -n "$_entry" ] || { BOOT_PROBLEM="no kinframe boot animation for this frame's screen (${SCREEN:-unknown size})"; return 1; }
		say "  screen $SCREEN: using $(mf "$_entry" file)"
		boot_get "$1" "$(mf "$_entry" file)" "$BOOT_FILES/bootanimation.zip" || { BOOT_PROBLEM="could not get $(mf "$_entry" file)"; return 1; }
		[ "$(sha256_of "$BOOT_FILES/bootanimation.zip")" = "$(mf "$_entry" sha256)" ] || { BOOT_PROBLEM="$(mf "$_entry" file) does not match its sha256"; return 1; }
		[ "$(dd if="$BOOT_FILES/bootanimation.zip" bs=1 count=2 2>/dev/null)" = PK ] || { BOOT_PROBLEM='the boot animation is not a zip file'; return 1; }
	fi
	return 0
}

# fetch_boot SERVER: the start-up branding for this frame into $BOOT_FILES (from --boot-assets, or the server's static
# files, falling back to the hosted server).
fetch_boot() {
	step "Download the start-up logo and animation"
	BOOT_FILES="$TMP/boot"
	mkdir -p "$BOOT_FILES"
	if [ -n "$BOOT_DIR" ]; then
		boot_pick "$BOOT_DIR" || fail "$BOOT_PROBLEM ($BOOT_DIR). Run again with --no-boot-logo."
		say "  using $BOOT_DIR"
	else
		_ok=0
		_why=''
		for _base in "$1" "$DEFAULT_SERVER"; do
			_url="$_base$BOOT_ASSETS/$BOOT_BRAND"
			if boot_pick "$_url"; then
				_ok=1
				say "  from $_url"
				break
			fi
			log "$_url: $BOOT_PROBLEM"
			_why="$BOOT_PROBLEM"
		done
		[ $_ok = 1 ] || fail "could not get the start-up logo and animation: $_why. Run again with --no-boot-logo, or use --boot-assets."
	fi
	[ -f "$BOOT_FILES/logo.bmp" ] && log "boot logo sha256 $(sha256_of "$BOOT_FILES/logo.bmp")"
	[ -f "$BOOT_FILES/bootanimation.zip" ] && log "boot animation sha256 $(sha256_of "$BOOT_FILES/bootanimation.zip")"
	return 0
}

# fetch_root_cert SERVER: the Let's Encrypt root into $TMP/root-cert, only when its sha256 is the pinned one (from a
# checkout next to this script, the server, or the hosted server). Returns 1 when there is none.
fetch_root_cert() {
	_here=$(dirname "$0")
	for _src in "$_here/../../web/static$ROOT_CERT_ASSETS/$ROOT_CERT_FILE" "$1$ROOT_CERT_ASSETS/$ROOT_CERT_FILE" "$DEFAULT_SERVER$ROOT_CERT_ASSETS/$ROOT_CERT_FILE"; do
		: >"$TMP/root-cert"
		case $_src in
		http://* | https://*) curl -fsSL -o "$TMP/root-cert" "$_src" 2>>"$LOG_FILE" || continue ;;
		*) if [ -f "$_src" ]; then cp "$_src" "$TMP/root-cert" || continue; else continue; fi ;;
		esac
		if [ "$(sha256_of "$TMP/root-cert")" = "$ROOT_CERT_SHA256" ]; then
			log "Let's Encrypt root certificate from $_src"
			return 0
		fi
		log "$_src: sha256 does not match $ROOT_CERT_SHA256"
	done
	return 1
}

# write_root_cert FILE: adds the Let's Encrypt root to the system CA store like the boot animation: push, remount
# /system writable, copy, mode 644 root:root, remount read-only, check sha1. Failures are warnings (returns 1).
write_root_cert() {
	wr "$(fill "$CMD_mkWork")"
	push "$1" "$WORK_DIR/$ROOT_CERT_FILE" || { warn "could not copy the root certificate to the frame"; return 1; }
	wr "$(fill "$CMD_remountRw" "system=$SYSTEM_DEV")"
	[ -z "$OUT" ] || { warn "could not make /system writable: $OUT"; return 1; }
	wr "$(fill "$CMD_copyRootCert")"
	wr "$(fill "$CMD_chmodRootCert")"
	wr "$(fill "$CMD_chownRootCert")"
	wr "$(fill "$CMD_remountRo" "system=$SYSTEM_DEV")"
	[ -z "$OUT" ] || warn "could not make /system read-only again ($OUT); it will be after the restart"
	wr "$(fill "$CMD_removeFile" "file=$ROOT_CERT_FILE")"
	[ "$DRY_RUN" = 1 ] && return 0
	rd "$(fill "$CMD_sha1RootCert")"
	[ "${OUT%% *}" = "$(sha1_of "$1")" ] || { warn "the root certificate on the frame is not the one copied ($OUT)"; return 1; }
	say "  Let's Encrypt root certificate added ($ROOT_CERT)"
}

# disabled_missing PACKAGES: those of PACKAGES that `pm list packages -d` doesn't list, in MISSING.
disabled_missing() {
	rd "$(fill "$CMD_listDisabled")"
	MISSING=''
	for _p in $1; do
		printf '%s\n' "$OUT" | grep -qxF "package:$_p" || MISSING="$MISSING $_p"
	done
	MISSING=${MISSING# }
}

# path_exists PATH: `ls -d PATH` shows it.
path_exists() {
	rd "$(fill "$CMD_checkPath" "path=$1")"
	has_line "$OUT" "$1"
}

# clear_updater: the clearUpdater action: removes the updater's download folders on the volumes (as the shell user,
# then as root if still there) and a pending /cache/recovery/command, checking each.
clear_updater() {
	for _d in $ADUPS_DOWNLOADS; do
		wr "$(fill "$CMD_removePath" "path=$_d")"
		[ "$DRY_RUN" = 1 ] && continue
		if path_exists "$_d"; then
			wr "$(fill "$CMD_removePathRoot" "path=$_d")"
			path_exists "$_d" && warn "could not remove the updater's download folder $_d"
		fi
	done
	if has_line "$RECOVERY_LIST" command; then
		say "  /cache/recovery/command (removed): $(printf '%s' "$RECOVERY_COMMAND" | tr '\n' ' ')"
		wr "$(fill "$CMD_removeRecoveryCommand")"
		if [ "$DRY_RUN" = 0 ]; then
			rd "$(fill "$CMD_listRecovery")"
			has_line "$(names "$OUT")" command && warn "could not remove /cache/recovery/command"
		fi
	fi
	return 0
}

# edit_hosts block|unblock: the editHosts action: pulls /system/etc/hosts, builds the new file here (hosts_edit) and,
# when it changes, copies it in like the boot animation (remount rw, cp, chmod 644, chown 0:0, remount ro, sha1).
# Failures are warnings (returns 1).
edit_hosts() {
	if [ "$DRY_RUN" = 1 ]; then
		say "  would $1 the update servers in $HOSTS (built here from the pulled file, copied in with remount rw / ro, sha1 checked)"
		return 0
	fi
	pull "$HOSTS" "$TMP/hosts.orig" || { warn "could not read $HOSTS"; return 1; }
	hosts_edit "$1" "$TMP/hosts.orig" "$TMP/hosts.new" || { warn "could not build the new hosts file"; return 1; }
	if cmp -s "$TMP/hosts.orig" "$TMP/hosts.new"; then
		if [ "$1" = block ]; then say "  the update servers are already blocked"; else say "  no kinframe block in the hosts file"; fi
		return 0
	fi
	if [ "$1" = block ]; then ensure_backup_dir && cp "$TMP/hosts.orig" "$BACKUP_DIR/hosts"; fi
	wr "$(fill "$CMD_mkWork")"
	push "$TMP/hosts.new" "$WORK_DIR/hosts" || { warn "could not copy the hosts file to the frame"; return 1; }
	wr "$(fill "$CMD_remountRw" "system=$SYSTEM_DEV")"
	[ -z "$OUT" ] || { warn "could not make /system writable: $OUT"; return 1; }
	wr "$(fill "$CMD_copyHosts")"
	wr "$(fill "$CMD_chmodHosts")"
	wr "$(fill "$CMD_chownHosts")"
	wr "$(fill "$CMD_remountRo" "system=$SYSTEM_DEV")"
	[ -z "$OUT" ] || warn "could not make /system read-only again ($OUT); it will be after the restart"
	wr "$(fill "$CMD_removeFile" "file=hosts")"
	rd "$(fill "$CMD_sha1Hosts")"
	[ "${OUT%% *}" = "$(sha1_of "$TMP/hosts.new")" ] || { warn "$HOSTS on the frame is not the one copied ($OUT)"; return 1; }
	if [ "$1" = block ]; then say "  update servers blocked in $HOSTS"; else say "  kinframe block removed from $HOSTS"; fi
}

# save_state: the saveState action: records the extra packages this convert disabled (those of EXTRAS_TODO that are
# disabled now) plus the earlier record, in STATE_FILE on the frame and in the backup directory.
save_state() {
	if [ "$DRY_RUN" = 1 ]; then
		say "  would record the disabled extra packages in $STATE_FILE"
		return 0
	fi
	rd "$(fill "$CMD_listDisabled")"
	_off=$OUT
	_rec=''
	for _p in $EXTRA_PACKAGES; do
		if in_list "$_p" "$EXTRAS_STATE"; then _rec="$_rec $_p"
		elif in_list "$_p" "$EXTRAS_TODO"; then
			if has_line "$_off" "package:$_p"; then _rec="$_rec $_p"; else warn "still enabled: $_p"; fi
		fi
	done
	{
		printf '%s\n' '# kinframe convert: the extra packages it disabled; kinframe revert enables these again'
		for _p in $_rec; do printf '%s\n' "$_p"; done
	} >"$TMP/state"
	push "$TMP/state" "$STATE_FILE" || warn "could not record the disabled extra packages on the frame"
	ensure_backup_dir && cp "$TMP/state" "$BACKUP_DIR/kinframe-state.txt"
	say "  recorded in $STATE_FILE:${_rec:- nothing}"
}

# settle: PackageManager saves package states about 10 s after a change; wait, then flush, before a restart.
settle() {
	say "  waiting ${SETTLE_SECONDS}s so the frame saves the changes"
	wr "$(fill "$CMD_settle" "seconds=$SETTLE_SECONDS")"
	wr "$(fill "$CMD_sync")"
}

# flash_resource IMAGE: writes a resource image to the resource partition like the website's flashResource():
# refuses an invalid image, reads the partition first (the way back), writes, flushes, reads back, compares sha256,
# and on a mismatch writes the previous content back at once.
flash_resource() {
	check_resource "$1" || fail "refusing to write $1: $RS_PROBLEM"
	if [ "$DRY_RUN" = 1 ]; then
		say "  would write $1 to $RESOURCE_DEV, read it back and compare"
		return 0
	fi
	read_resource resource-current.img "$TMP/resource-current.img" || fail "could not read $RESOURCE_DEV"
	[ "$(size_of "$TMP/resource-current.img")" = "$(size_of "$1")" ] ||
		fail "the resource partition is $(size_of "$TMP/resource-current.img") bytes, the image $(size_of "$1"): not written"
	_before=$(sha256_of "$TMP/resource-current.img")
	_want=$(sha256_of "$1")
	log "resource partition sha256 $_before, new image sha256 $_want"
	if [ "$_before" = "$_want" ]; then
		say "  the resource partition already has this image"
		wr "$(fill "$CMD_removeFile" "file=resource-current.img")"
		return 0
	fi
	push "$1" "$WORK_DIR/resource-new.img" || fail "could not copy the resource image to the frame"
	wr "$(fill "$CMD_writeResource" "resource=$RESOURCE_DEV" "file=resource-new.img")"
	wr "$(fill "$CMD_sync")"
	wr "$(fill "$CMD_flushResource" "resource=$RESOURCE_DEV")"
	read_resource resource-check.img "$TMP/resource-check.img"
	_got=$(sha256_of "$TMP/resource-check.img" 2>/dev/null)
	log "read back sha256 $_got"
	if [ "$_got" = "$_want" ]; then
		say "  written and verified (sha256 $(printf '%s' "$_want" | cut -c1-12)…)"
		for _f in resource-new.img resource-check.img resource-current.img; do wr "$(fill "$CMD_removeFile" "file=$_f")"; done
		return 0
	fi
	warn "the resource partition did not read back correctly; writing the previous content back"
	wr "$(fill "$CMD_writeResource" "resource=$RESOURCE_DEV" "file=resource-current.img")"
	wr "$(fill "$CMD_sync")"
	wr "$(fill "$CMD_flushResource" "resource=$RESOURCE_DEV")"
	rm -f "$TMP/resource-check.img"
	read_resource resource-check.img "$TMP/resource-check.img"
	_again=$(sha256_of "$TMP/resource-check.img" 2>/dev/null)
	log "read back sha256 $_again"
	if [ "$_again" = "$_before" ]; then
		for _f in resource-new.img resource-check.img resource-current.img; do wr "$(fill "$CMD_removeFile" "file=$_f")"; done
		fail "the new boot logo did not read back correctly; the previous one was written back and checked. Nothing else was changed."
	fi
	fail "the resource partition could not be restored (sha256 $_again, expected $_before). Do NOT restart the frame. Write the backup back first: see docs/hardware/denver-pff-1024.md. A copy is on the frame at $WORK_DIR/resource-current.img."
}

# write_bootanimation FILE: push, remount /system writable, copy, make readable, remount read-only, check sha1.
write_bootanimation() {
	wr "$(fill "$CMD_mkWork")"
	push "$1" "$WORK_DIR/bootanimation.zip" || fail "could not copy the boot animation to the frame"
	wr "$(fill "$CMD_remountRw" "system=$SYSTEM_DEV")"
	[ -z "$OUT" ] || fail "could not make /system writable: $OUT"
	wr "$(fill "$CMD_copyBootAnimation")"
	wr "$(fill "$CMD_chmodBootAnimation")"
	wr "$(fill "$CMD_remountRo" "system=$SYSTEM_DEV")"
	[ -z "$OUT" ] || warn "could not make /system read-only again ($OUT); it will be after the restart"
	wr "$(fill "$CMD_removeFile" "file=bootanimation.zip")"
	[ "$DRY_RUN" = 1 ] && return 0
	rd "$(fill "$CMD_sha1BootAnimation")"
	[ "${OUT%% *}" = "$(sha1_of "$1")" ] || fail "the boot animation on the frame is not the one copied ($OUT)"
	say "  boot animation in place"
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
	BOOT_NOW=0
	if [ "$BOOT" = 1 ]; then
		if [ "$BOOT_LOGO_OK" = 1 ] || [ "$BOOT_ANIM_OK" = 1 ]; then
			BOOT_NOW=1
		else
			say ""
			say "  This frame's start-up logo and animation can't be replaced; leaving them alone."
		fi
	fi
	[ "$BOOT_NOW" = 1 ] && boot_backup
	fetch_release

	# Start-up branding right after the downloads and before the app changes, so a failure leaves the frame as it was.
	if [ "$BOOT_NOW" = 1 ]; then
		fetch_boot "$_server"
		if [ "$BOOT_LOGO_OK" = 1 ]; then
			step "Write the start-up logo (resource partition $RESOURCE_DEV)"
			if [ "$DRY_RUN" = 1 ]; then
				say "  would put the boot logo into $BACKUP_DIR/$RESOURCE_BACKUP, write it, read it back and compare"
			else
				brand_resource "$BACKUP_DIR/$RESOURCE_BACKUP" "$BOOT_FILES/logo.bmp" "$TMP/resource-$BOOT_BRAND.img" ||
					fail "boot logo not changed: $RS_PROBLEM. Run again with --no-boot-logo."
				flash_resource "$TMP/resource-$BOOT_BRAND.img"
			fi
		fi
		if [ "$BOOT_ANIM_OK" = 1 ]; then
			step "Write the start-up animation"
			write_bootanimation "$BOOT_FILES/bootanimation.zip"
		fi
	fi

	# A fallback for frames whose CA store lacks the Let's Encrypt root (rootCertNeeded()); failures are warnings.
	if [ "$ROOT_CERT_OK" = 1 ] && [ "$ROOT_CERT_WANTED" = 1 ]; then
		step "Add the Let's Encrypt root certificate"
		if fetch_root_cert "$_server"; then
			write_root_cert "$TMP/root-cert" || CERT_WARNING="the Let's Encrypt root certificate could not be added; kinframe may not reach a server with a Let's Encrypt certificate"
		else
			CERT_WARNING="could not download the Let's Encrypt root certificate; it was not added"
			warn "$CERT_WARNING"
		fi
	fi

	# Defence in depth that survives a factory reset: the Adups servers resolve to 0.0.0.0. Failures are warnings.
	step "Block the firmware update servers ($HOSTS)"
	if [ "$BOOT_ANIM_OK" = 1 ]; then
		edit_hosts block || HOSTS_WARNING="the firmware update servers could not be blocked in $HOSTS"
	else
		HOSTS_WARNING="/system cannot be changed on this frame (no root, or dm-verity): the update servers are not blocked in $HOSTS"
		warn "$HOSTS_WARNING"
	fi

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
	# First stop the updater and drop what it has queued, so nothing is half-way when it is turned off.
	case $(state_of "$ADUPS_PACKAGE") in
	1:*)
		wr "$(fill "$CMD_forceStop" "pkg=$ADUPS_PACKAGE")"
		wr_expect "$(fill "$CMD_clearData" "pkg=$ADUPS_PACKAGE")" 'Success' || warn "could not clear the data of $ADUPS_PACKAGE: $OUT"
		;;
	esac
	clear_updater
	_changed=0
	for _p in $DISABLE_PACKAGES; do
		case $(state_of "$_p") in
		1:enabled)
			wr_expect "$(fill "$CMD_disable" "pkg=$_p")" 'disabled-user' || fail "could not disable $_p: $OUT"
			_changed=1
			;;
		1:disabled) say "  $_p is already disabled" ;;
		*) say "  $_p is not installed" ;;
		esac
	done
	if [ "$DRY_RUN" = 0 ] && [ $_changed = 1 ]; then
		disabled_missing "$UPDATERS_INSTALLED"
		[ -z "$MISSING" ] || fail "still enabled after pm disable-user: $MISSING"
	fi

	EXTRAS_DONE=''
	if [ "$KEEP_EXTRAS" = 0 ] && [ -n "$EXTRAS_TODO" ]; then
		step "Turn off apps a photo frame doesn't use"
		for _p in $EXTRAS_TODO; do
			wr_expect "$(fill "$CMD_disable" "pkg=$_p")" 'disabled-user' || warn "could not disable $_p: $OUT"
		done
		save_state
		EXTRAS_DONE=$EXTRAS_TODO
	fi

	step "Make kinframe the home app"
	if [ "$SDK" -ge "$SET_HOME_MIN_SDK" ]; then
		wr "$(fill "$CMD_setHome")"
		contains "$OUT" 'Error' && warn "could not set the home app; pick kinframe and 'Always' if the frame asks"
	else
		say "  Android $SDK: kinframe is the home app because the stock app is disabled"
	fi

	wr "$(fill "$CMD_cleanup")"

	step "Restart"
	settle
	if [ "$DRY_RUN" = 1 ]; then
		say "  would run: adb reboot"
	else
		restart_frame
		wait_boot || fail "the frame did not come back within 3 minutes. If it shows kinframe, set it up on the screen."
		# The updaters must stay off: com.adups.fota.sysoper can install firmware updates through recovery.
		disabled_missing "$UPDATERS_INSTALLED"
		if [ -n "$MISSING" ]; then
			warn "$MISSING came back after the restart; turning it off again"
			for _p in $MISSING; do wr "$(fill "$CMD_disable" "pkg=$_p")"; done
			settle
			_back=$MISSING
			disabled_missing "$UPDATERS_INSTALLED"
			[ -z "$MISSING" ] || fail "$MISSING came back after the restart and could not be turned off again. Run the convert again, or turn it off by hand: adb shell su 0 pm disable-user <package>"
			DISABLE_WARNING="$_back came back after the restart and was turned off again"
		else
			say "  Frameo and the updaters stayed off"
		fi
		# The extras: the same check, but only a warning.
		if [ -n "$EXTRAS_DONE" ]; then
			disabled_missing "$EXTRAS_DONE"
			if [ -n "$MISSING" ]; then
				for _p in $MISSING; do wr "$(fill "$CMD_disable" "pkg=$_p")"; done
				settle
				_back=$MISSING
				disabled_missing "$EXTRAS_DONE"
				if [ -n "$MISSING" ]; then EXTRAS_WARNING="$MISSING could not be kept turned off"; else EXTRAS_WARNING="$_back came back after the restart and was turned off again"; fi
				warn "$EXTRAS_WARNING"
			fi
		fi
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
		for _w in "${DATA_WARNING:-}" "${DATA_NOTE:-}" "${CERT_WARNING:-}" "${HOSTS_WARNING:-}" "${DISABLE_WARNING:-}" "${EXTRAS_WARNING:-}"; do
			[ -n "$_w" ] && say "Note: $_w"
		done
		say "Done. Follow the setup on the frame (language, Wi-Fi, time zone)."
		say "At the end it shows a code: enter it at $_server/frames/add to add the frame to your family."
		say "Photos sent to the frame with Frameo are then imported automatically, with their captions and senders."
		say "A factory reset turns the original app and its firmware updaters back on: run this convert again after one."
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

	if [ -n "$RESTORE_RESOURCE" ]; then
		step "Write the backed-up start-up logo back"
		[ -f "$RESTORE_RESOURCE" ] || fail "no such file: $RESTORE_RESOURCE"
		[ "$BOOT_LOGO_OK" = 1 ] || fail "this frame has no resource partition to write $RESTORE_RESOURCE to"
		flash_resource "$RESTORE_RESOURCE"
	fi
	if [ -n "$RESTORE_BOOTANIMATION" ]; then
		step "Put the backed-up start-up animation back"
		[ -f "$RESTORE_BOOTANIMATION" ] || fail "no such file: $RESTORE_BOOTANIMATION"
		[ "$(dd if="$RESTORE_BOOTANIMATION" bs=1 count=2 2>/dev/null)" = PK ] || fail "$RESTORE_BOOTANIMATION is not a zip file"
		[ "$BOOT_ANIM_OK" = 1 ] || fail "/system on this frame can't be changed"
		write_bootanimation "$RESTORE_BOOTANIMATION"
	fi

	if [ "$BOOT_ANIM_OK" = 1 ]; then
		step "Unblock the firmware update servers ($HOSTS)"
		edit_hosts unblock
	fi

	step "Enable Frameo and the firmware updaters"
	for _p in $DISABLE_PACKAGES; do
		case $(state_of "$_p") in
		1:disabled) wr_expect "$(fill "$CMD_enable" "pkg=$_p")" 'enabled' || warn "could not enable $_p: $OUT" ;;
		1:enabled) say "  $_p is already enabled" ;;
		*) say "  $_p is not installed" ;;
		esac
	done
	# extrasToEnable(): what the convert recorded, or without a record every extra package that is disabled.
	if [ $STATE_EXISTS = 1 ]; then _recorded=$EXTRAS_STATE; else _recorded=$EXTRA_PACKAGES; fi
	for _p in $EXTRA_PACKAGES; do
		if in_list "$_p" "$_recorded" && has_line "$DISABLED_LIST" "$_p"; then
			wr_expect "$(fill "$CMD_enable" "pkg=$_p")" 'enabled' || warn "could not enable $_p: $OUT"
		fi
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
	[ $STATE_EXISTS = 1 ] && wr "$(fill "$CMD_removeState")"

	step "Restart"
	settle
	if [ "$DRY_RUN" = 1 ]; then
		say "  would run: adb reboot"
		say ""
		say "Dry run: nothing on the frame was changed. The log is in $LOG_FILE."
		return
	fi
	restart_frame
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
