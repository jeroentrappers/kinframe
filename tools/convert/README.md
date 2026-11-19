# Convert a frame

Turns a stock Frameo photo frame (tested: Denver PFF-1024 and Denver PFF-1515)
into a kinframe frame, and turns it back. There are two
tools that run the same steps:

- **Website**: `https://<your kinframe server>/convert` (for example https://kinframe.appmire.be/convert). Works in
  Chrome, Edge or Opera on Windows, macOS, Linux and Android over WebUSB. Nothing to install.
- **Command line**: `kinframe-convert.sh` in this folder, for Firefox and Safari users and for anyone who prefers a
  terminal. Needs Android [platform-tools](https://developer.android.com/tools/releases/platform-tools) (`adb`), `curl`, `tar`,
  `unzip` and `openssl` (or `apksigner` from Android build-tools). Runs with any POSIX shell: Linux, macOS, and on
  Windows in Git Bash with the Windows `adb.exe`.

Supported and untested frames: [`docs/hardware/supported-devices.md`](../../docs/hardware/supported-devices.md).

## Before you start

1. Denver frames have USB access (ADB) turned on from the factory: skip to step 2. On other Frameo frames, open
   Settings → About and turn on ADB access first (on some frames it is under the beta program).
2. Connect the frame to the computer with a USB cable. The first time, the frame asks to allow the computer: tick
   "Always allow" and tap OK.
3. Only one program can use the frame's USB connection. For the website, stop adb first (`adb kill-server`) and
   close Android Studio.
4. On Windows the website may need the Google USB driver (or any WinUSB driver) for the frame's ADB interface.

## Command line

```sh
sh kinframe-convert.sh --dry-run            # report what would change; changes nothing on the frame
sh kinframe-convert.sh                      # convert, using https://kinframe.appmire.be
sh kinframe-convert.sh --server frames.example.org --language nl
sh kinframe-convert.sh --revert             # back to the stock app
sh kinframe-convert.sh --revert --keep-data --restore-apk kinframe-backup-…/net.frameo.frame-base.apk
sh kinframe-convert.sh --revert --restore-resource kinframe-backup-…/resource.img \
  --restore-bootanimation kinframe-backup-…/bootanimation.zip   # also put the stock start-up screen back
```

| Option | Meaning |
|---|---|
| `--dry-run` | Only read from the frame (and download and verify the APK). Every change is printed as "would run". |
| `--revert` | Enable the stock app and the updaters, uninstall kinframe, restart. |
| `--server URL` | Your own kinframe server. Default `https://kinframe.appmire.be`. |
| `--language CODE` | The frame's language (`en`, `nl`, `de`, …; the 36 codes of `docs/i18n.md`). Default: the frame asks. |
| `--force` | Continue on a model that hasn't been tested. |
| `--no-backup` / `--backup-dir DIR` | Skip the backup of the stock app / choose where backups go (default `./kinframe-backup-<serial>-<date>`). |
| `--no-boot-logo` | Leave the start-up logo and animation alone (see [Boot logo and animation](#boot-logo-and-animation)). |
| `--boot-assets DIR` | Use the start-up logos and animations in `DIR` (its `manifest.json` and the files it lists, for example `web/static/frame-boot/kinframe` in a checkout) instead of downloading them. The same variant is picked, and checked against the manifest's sha256. |
| `--no-root-cert` | Don't add the Let's Encrypt root certificate (see [Let's Encrypt root](#lets-encrypt-root-certificate)). |
| `--keep-extras` | Leave the preinstalled apps a photo frame doesn't use enabled (see [Extra packages](#extra-packages)). The website has a checkbox for the same. |
| `--full-backup` | Also copy every eMMC partition except user data, for a last-resort restore. Read-only, but slow. |
| `--apk FILE` / `--cert SHA256` | Install a local APK instead of the latest release / expect another signing certificate (your own build). |
| `--keep-data` | With `--revert`: keep kinframe's data, so a later install is still in the family. |
| `--restore-apk FILE` | With `--revert`: reinstall the backed-up stock APK first (if it updated itself and broke). |
| `--restore-resource FILE` / `--restore-bootanimation FILE` | With `--revert`: write the backed-up `resource.img` (start-up logo) / `bootanimation.zip` back. A resource image that doesn't parse as one, with a device tree, is refused. |
| `--check-resource FILE` / `--patch-resource IN LOGO OUT` | No frame needed: check a resource image and list its entries / write `OUT`, which is `IN` with its boot logos replaced by `LOGO`. |
| `--edit-hosts block\|unblock IN OUT` | No frame needed: write `OUT`, which is the hosts file `IN` with kinframe's block added or removed. |
| `-s SERIAL` | Pick the frame when more than one device is connected. |
| `--log FILE` | Log file (default `./kinframe-convert-<date>.log`). Include it when you ask for help. |

`ADB=/path/to/adb` picks another adb.

## What it does

The website and the script run the same commands, from one plan. Convert:

| Step | Commands on the frame | Notes |
|---|---|---|
| Identify | `getprop`, `su 0 id`, `ls /dev/block/platform/*/by-name/resource`, `mount`, `wm size`, `ls /system/etc/security/cacerts/6187b673.0`, `pm list packages`, `pm list packages -d`, `cat /data/local/tmp/kinframe-state.txt`, the updater's leftovers (see [Firmware updaters](#firmware-updaters)), `pm path <pkg>`, `dumpsys package <pkg>` | Read-only. Refuses unsupported frames; untested models need `--force` or the website's checkbox. Shows the firmware (`ro.build.fingerprint`, `ro.build.display.id`, `ro.fota.version`), whether Frameo is the copy in /system or an update in /data/app, and warns when the updater has an update waiting. |
| Back up | `adb pull` of every path from `pm path net.frameo.frame`; the app data: `su 0 ls -lnRa /data/data/net.frameo.frame`, `su 0 cp -a /data/data/net.frameo.frame <work>/net.frameo.frame`, `su 0 rm -rf <copy>/cache <copy>/code_cache <copy>/app_webview <copy>/lib <copy>/files/.crashlytics.v3`, `su 0 chmod -R a+rX <copy>`, the copy pulled as a directory and archived on the computer, `su 0 rm -rf <copy>` | The website saves the files to your downloads. See [The data backup](#the-data-backup). A data backup that fails is a **warning**, not an error: converting only turns the stock app off, its data stays on the frame and a revert brings it back. |
| Back up the start-up screen | `adb pull /system/media/bootanimation.zip`; `su 0 dd if=<resource partition> of=/data/local/tmp/kinframe-convert/resource.img bs=1048576`, `su 0 chmod 644 …`, pulled, removed | Only with the boot logo option (on by default), and then also with `--no-backup`. Saved as `bootanimation.zip` and `resource.img` next to the other backups. |
| Download and verify | none | Latest stable release. The website gets it from its own server (`GET /v1/frame-app/latest`, which mirrors the GitHub Releases, so the browser needs no cross-origin download); the script gets it from GitHub Releases. Both check the published sha256 and that **every** signing certificate is the release certificate `99:C5:E3:6C:…:2A:20:5C`. With the boot logo option, also `<server>/frame-boot/kinframe/manifest.json` and the logo and animation picked from it for this frame, each checked against the manifest's sha256. Where the root certificate is added, also `<server>/frame-certs/6187b673.0`, checked against its pinned sha256. |
| Start-up logo | see [Boot logo and animation](#boot-logo-and-animation) | Before the app changes, so a failure leaves the frame as it was. |
| Start-up animation | `adb push` to the work directory, `su 0 mount -o remount,rw <system device> /system`, `su 0 cp … /system/media/bootanimation.zip`, `su 0 chmod 644 …`, `su 0 mount -o remount,ro <system device> /system`, then `sha1sum /system/media/bootanimation.zip` must match | /system is always remounted read-only again, also when the copy failed. Skipped when /system is a dm-verity device. |
| Block the update servers | `adb pull /system/etc/hosts`, the new file built on the computer, `adb push` to the work directory, `su 0 mount -o remount,rw …`, `su 0 cp <work>/hosts /system/etc/hosts`, `su 0 chmod 644 …`, `su 0 chown 0:0 …`, `su 0 mount -o remount,ro …`, then `sha1sum /system/etc/hosts` must match | Adds kinframe's block (see [Firmware updaters](#firmware-updaters)); nothing is written when it is already there. The original is saved as `hosts` with the backups. Failures are warnings; skipped with a warning when /system can't be changed (dm-verity). |
| Let's Encrypt root | `adb push` to the work directory, `su 0 mount -o remount,rw …`, `su 0 cp <work>/6187b673.0 /system/etc/security/cacerts/6187b673.0`, `su 0 chmod 644 …`, `su 0 chown 0:0 …`, `su 0 mount -o remount,ro …`, then `sha1sum …` must match | Only on Android 6 (API < 24) frames whose CA store lacks it, with root and a /system that is not dm-verity. Failures are warnings. See [Let's Encrypt root](#lets-encrypt-root-certificate). |
| Install | `adb push` to `/data/local/tmp/kinframe-convert/kinframe.apk`, `pm install -r -g …` | A newer kinframe already installed is kept. |
| Permissions | `pm grant app.kinframe.frame <perm>`, `appops set app.kinframe.frame WRITE_SETTINGS allow` (+ `REQUEST_INSTALL_PACKAGES` on Android 8+) | Failures are warnings. |
| Turn off | `am force-stop com.adups.fota`, `pm clear com.adups.fota`, the updater's download folders and a pending `/cache/recovery/command` removed (see [Firmware updaters](#firmware-updaters)); then `su 0 pm disable-user net.frameo.frame`, `com.adups.fota`, `com.adups.fota.sysoper`, `android.rockchip.update.service`, and `pm list packages -d` must list each | Only after kinframe is installed. **Never uninstalls** system packages. |
| Extra packages | `su 0 pm disable-user <pkg>` for each [extra package](#extra-packages) that is installed and enabled, then the list of those now disabled is pushed to `/data/local/tmp/kinframe-state.txt` (and saved as `kinframe-state.txt` with the backups) | Optional, on by default (`--keep-extras`, website checkbox). Failures are warnings. |
| Home app | Android 7+: `pm set-home-activity app.kinframe.frame/.MainActivity`. Android 6: nothing; kinframe is the only home app left. | |
| Clean up, restart | `rm -rf /data/local/tmp/kinframe-convert`, `sleep 15`, `su 0 sync`, reboot, wait for `sys.boot_completed`, then `pm list packages -d` again | Android saves package states about 10 s after a change, so a restart right away can lose the last `pm disable-user` (it did on a Denver PFF-1515: `com.adups.fota.sysoper` was enabled again). After the restart, a package that came back is turned off again, the tool waits again, and it warns; if it still doesn't stay off, the step fails (for the extra packages it only warns). `com.adups.fota.sysoper` can install firmware updates silently through recovery, so this matters. |
| Provision | `am start -n app.kinframe.frame/.MainActivity --es server_url <URL> [--es language <code>]` | After the restart: the frame keeps a provisioned server only until it enrols. |
| Check | `dumpsys activity activities` | Warns when kinframe isn't in front. |

The frame then runs kinframe's setup (language, Wi-Fi, time zone) and shows a code; enter it at `<server>/frames/add`.

**After a factory reset, run the convert again.** A factory reset wipes /data, where Android keeps which packages are
disabled: the stock app and the firmware updaters come back on (the hosts block and the other /system changes stay).
Both tools say so at the end of a convert.
Once it belongs to a family, the frame imports the photos and videos it received with the stock app, with their
captions, dates and senders (the stock app's data stays on the frame for the revert; `docs/spec/frameo-import.md`).

Revert: optional reinstall of a backed-up stock APK (`pm install -r -d`), optional write-back of the backed-up
`resource.img` and `bootanimation.zip` (same steps as above, so the resource image is verified too), kinframe's block
removed from `/system/etc/hosts` (everything else in the file is kept; same copy pattern), `su 0 pm enable` for each
updater package that is disabled and for the [extra packages](#extra-packages) the convert recorded (without a record:
every extra package that is disabled; never anything else, so System UI, which the vendor disables on some frames,
stays off), the record removed, `pm uninstall [-k] app.kinframe.frame` (falling back to `su 0 pm uninstall`), clean up, wait 15 s and sync
(as above), restart. Every step is independent and best effort, so it also repairs a half-converted frame: kinframe
missing or crashing, or the stock app turned off by hand. The revert leaves the Let's Encrypt root in place: it is a
standard public root that newer Android versions ship themselves.

### The data backup

The stock app's data (`/data/data/net.frameo.frame`: databases, shared preferences, and `files/` with the received
photos and videos) is saved as two files:

- `net.frameo.frame-data.tgz`: the data, with names `net.frameo.frame/…` (extract it in `/data/data` for a restore).
- `net.frameo.frame-data-files.txt`: `ls -lnRa` of the original directory, with the modes and the numeric owner (the
  app's uid) of every file. The archive itself holds the modes after the copy was made readable and owner 0, so a
  restore needs this list (or simply `chown -R <app uid>:<app uid>` and `restorecon -R`).

**No archive is made on the frame.** Toybox tar on some firmware (the Denver PFF-1515's) can't store names longer
than 100 bytes at all: it stops with `tar: '…' > 100 bytes` and leaves an empty archive, and Frameo has files whose
name alone is about 90 bytes (Firebase's `files/PersistedInstallation.<base64>.json`) besides deeply nested crash
reports. Instead, both tools:

1. copy the data as root into the work directory (`su 0 cp -a /data/data/net.frameo.frame <work>/net.frameo.frame`);
2. drop what is not user data from the copy: `cache`, `code_cache`, `app_webview`, `files/.crashlytics.v3` and the
   `lib` symlink (it points into `/system`);
3. make the copy readable for the adb shell user (`su 0 chmod -R a+rX <copy>`; behind `su` only plain arguments);
4. pull the copy as a directory: the script with `adb pull <copy> <temp dir>/`, the website file by file over the
   adb sync protocol (LIST to walk the tree, then RECV per file; each file's size is checked against the listing).
   Symlinks and special files are skipped;
5. remove the copy (`su 0 rm -rf <copy>`), also when the pull failed;
6. archive what came back on the computer: the script with the computer's `tar` (GNU tar and bsdtar store long
   names), the website with its own tar writer (`web/src/lib/convert/tar.ts`: ustar with pax headers for long or
   non-ASCII names) and the browser's gzip.

If nothing could be copied, the step ends with a warning that names the reason, and the convert goes on (the data
stays on the frame). If `cp` reported files it could not copy, the backup holds the rest and the step warns.

## Firmware updaters

The frames come with two firmware updaters, besides the stock app updating itself:

- **Adups** (`com.adups.fota` and `com.adups.fota.sysoper`): checks the vendor's servers (`fota5p.adups.com`, falling
  back to `fota5p.adups.cn`; reports go to the same servers and `fruet.adups.com`) about once a day (checked when the
  network comes up, after a restart and on a timer), downloads updates (to its own data folder or `Android/data/com.adups.fota/files/adupsfota` on a
  storage volume), and installs them through recovery. The server can mark an update to be installed without asking.
  Only `com.adups.fota.sysoper` can start recovery; with it disabled an install attempt does nothing. Disabling
  `com.adups.fota` also stops the checks and downloads. An update would replace /system (and with it kinframe's changes
  there) and possibly the boot partitions.
- **Rockchip's RKUpdateService** (`android.rockchip.update.service`): after a restart or when a card or USB stick is
  mounted it looks for `update.img` / `update.zip` and offers to install it.

What the convert does about them:

1. **Report** (identify): the firmware identifiers, what is in `/cache/recovery` (`su 0 ls`, and the content of
   `command`, which recovery runs on its next start), the result file recovery leaves after an update, the updater's
   state and the path of a downloaded update (from its preferences file, read with `su 0 cat` and parsed on the
   computer; only a summary goes into the log), and its download folders (`ls -d` over `/storage/*` and
   `/storage/emulated/0`). A found, downloading or downloaded update, a downloaded file or a recovery command is a
   warning (website: a callout in step 3).
2. **Clear** before disabling: `am force-stop com.adups.fota`, `pm clear com.adups.fota` (drops a downloaded update
   and its pending state), its download folders on the volumes removed (`rm -rf` as the shell user, then as root if
   still there, each checked), and a pending `/cache/recovery/command` removed (`su 0 rm -f`, only that file; logged
   first). `adupsfota` folders at the top of a volume are reported, not removed.
3. **Disable** all three packages (plus the stock app), with the checks and the wait before the restart described
   above, and check again after it.
4. **Block** the servers in `/system/etc/hosts`, which survives a factory reset: the tools pull the file, add this
   block at the end (replacing an earlier one, so running again changes nothing) and copy it back as root:

   ```
   # >>> kinframe: block the Adups firmware update servers (kinframe revert removes this block)
   0.0.0.0 fota5p.adups.com
   0.0.0.0 fota5p.adups.cn
   0.0.0.0 fruet.adups.com
   # <<< kinframe
   ```

   The revert removes exactly the lines between the two markers. `removeHostsBlock` / `addHostsBlock` in `plan.ts`
   and `hosts_edit` in the script build the same bytes (`plan.test.ts` compares them through `--edit-hosts`).

## Extra packages

A photo frame running kinframe needs none of these preinstalled apps and services; the convert disables those that are
installed and enabled (`EXTRA_PACKAGES` in `plan.ts`), unless you pass `--keep-extras` or untick the website's
checkbox:

| Package | What it is, and why a frame doesn't need it |
|---|---|
| `com.android.browser` | Web browser; kinframe has no browsing. |
| `com.android.calculator2` | Calculator. |
| `com.android.music` | Music player. |
| `com.android.musicfx` | Audio effects for music players. |
| `com.android.soundrecorder` | Sound recorder (the frames have no usable microphone). |
| `com.android.gallery3d` | Stock gallery and camera app; kinframe shows the photos itself. |
| `android.rk.RockVideoPlayer` | Rockchip video player; kinframe plays videos itself. |
| `com.android.rk` | Rockchip file explorer (RkExplorer). |
| `com.android.rk.mediafloat` | Rockchip floating media window. |
| `com.android.dreams.basic` | Screen saver (colours); kinframe is the screen. |
| `com.android.dreams.phototable` | Screen saver (photo table). |
| `com.android.galaxy4` | Live wallpaper. |
| `com.android.noisefield` | Live wallpaper. |
| `com.android.phasebeam` | Live wallpaper. |
| `com.android.wallpaper` | Live wallpapers. |
| `com.android.wallpaper.holospiral` | Live wallpaper. |
| `com.android.wallpaper.livepicker` | Live wallpaper picker. |
| `com.android.wallpapercropper` | Wallpaper cropper. |
| `com.android.htmlviewer` | HTML viewer for local files. |
| `com.android.bluetoothmidiservice` | Bluetooth MIDI (the frames have no Bluetooth). |
| `com.android.printspooler` | Printing. |
| `com.android.providers.downloads.ui` | The Downloads app (the download service itself stays). |
| `com.cghs.stresstest` | Factory stress test. |
| `com.DeviceTest` | Factory device test. |
| `com.svox.pico` | Text-to-speech voice. |

Never disabled, because kinframe or Android needs them: Settings (kinframe opens the Wi-Fi settings), the package
installer (kinframe's self-update), the keyboards, Documents and external storage, the providers (media, downloads,
settings, telephony, contacts, calendar, user dictionary), WebView, the captive portal login, the certificate
installer, KeyChain, Shell, the default container service, phone, telecom and MMS services, System UI (some vendors
disable it themselves; the revert leaves it alone), provisioning, fused location and the proxy services.
`plan.test.ts` checks none of these is on a list.

**Which ones the convert disabled** is recorded in `/data/local/tmp/kinframe-state.txt` on the frame (one package per
line), merged with an earlier record when the convert runs again, and saved as `kinframe-state.txt` with the backups.
`/data/local/tmp` belongs to the adb shell user: no app can read or remove the file, it is not on the storage the user
sees, and it survives restarts and app updates (the convert's own work directory next to it is removed, the record
is not). Only a factory reset removes it, and that enables every package again anyway. The revert enables the recorded
packages that are disabled and then removes the record; without a record (a frame converted by an older version) it
enables every extra package that is disabled. It never enables anything that is not on the list.

## Boot logo and animation

A converted frame starts with kinframe's logo instead of the stock one (the "DENVER" logo on the Denver PFF-1024) and
kinframe's start-up animation instead of the stock app's. Both are on by default, behind one option (website checkbox,
CLI `--no-boot-logo` to skip). Where they live and how the frame boots: [denver-pff-1024.md, "Boot chain"](../../docs/hardware/denver-pff-1024.md#boot-chain)
and [denver-pff-1515.md](../../docs/hardware/denver-pff-1515.md#boot-chain).

- **The logo** is in the **resource partition** (`/dev/block/platform/*/by-name/resource`, 16 MiB), a Rockchip
  "RSCE" image with the kernel device tree (`rk-kernel.dtb`) and the logos the bootloader and kernel show
  (`logo.bmp`, `logo_kernel.bmp`). **Without an intact device tree the frame does not boot**, so this step is careful:
  1. The whole partition is backed up first (`resource.img`), and checked to be a resource image with a device tree.
  2. Our logo must have exactly the format of the frame's logos, or nothing is written. kinframe ships one logo per
     panel (see below); the tools pick the one whose width, height, bits per pixel and compression equal the frame's
     own logo **and** whose panel rotation equals the frame's `ro.sf.hwrotation` (the bootloader shows the logo
     unrotated, so a logo for another mounting would appear turned). Without such a logo the tools refuse before
     anything is written; `brand_resource` / `brandResource()` compare the formats once more.
  3. The new image is the backup with only the two logo entries replaced: the device tree stays byte for byte the
     same, entry offsets and sizes are recomputed, and the image keeps the partition's size
     (`web/src/lib/convert/rsce.ts`, and `brand_resource` in the script; tests check they give the same bytes).
  4. The partition is read again (the way back stays on the frame as `resource-current.img`), the new image is pushed
     and written with `su 0 dd … of=<resource partition> bs=1048576`, then `su 0 sync` and
     `su 0 blockdev --flushbufs <partition>`, read back and compared by **sha256** on the computer.
  5. On a mismatch the previous content is written back at once, read back and compared, and the step fails (the
     convert stops before the app is touched). If even that fails, the tool says so: **do not restart the frame**;
     write the backup back first ([recovery](../../docs/hardware/denver-pff-1024.md#recovering-a-damaged-resource-partition)).
- **The animation** is `/system/media/bootanimation.zip` (see the table above). A broken copy is harmless: Android
  then shows its default animation. Android draws the animation's frame centred on the screen, so it is made per
  screen size (the wordmark keeps the size and place it has in the logo): the tools pick the one for the frame's
  `wm size` (either orientation), and refuse when there is none.

Keep the backup from the **first** conversion: a second run backs up what is there, which is then kinframe's.

The images are built from our own brand assets by [`tools/brand/boot/build.py`](../brand/boot/build.py) into
`web/static/frame-boot/<brand>/` (reproducible; a `--brand` parameter leaves room for a retail brand). The website
serves them, so the script downloads them from the chosen server (falling back to the hosted one). `manifest.json`
lists every file with its format and sha256, one entry per line:

| File | For | Format |
|---|---|---|
| `logo-800x1280-r270.bmp` | Denver PFF-1024 (1280×800 screen, panel mounted at 270°) | 800×1280, 8-bit palette, RLE8, stored turned 90° counter-clockwise |
| `logo-1920x1080-r180.bmp` | Denver PFF-1515 (1920×1080 screen, panel mounted at 180°) | 1920×1080, 8-bit palette, RLE8, stored upside down |
| `bootanimation-1280x800.zip` | 1280×800 screens | 720×336 frame, 20 fps |
| `bootanimation-1920x1080.zip` | 1920×1080 screens | 972×454 frame, 20 fps |

A new panel is a new entry in `TARGETS` in `build.py`; rebuild and commit the output (see the command at the top of
`build.py`).

## Let's Encrypt root certificate

kinframe's hosted server uses a Let's Encrypt certificate, whose root (ISRG Root X1) Android only ships from 7.1.1 on.
Android 6 frames with older firmware (the Denver PFF-1515's, from 2025) don't have it in
`/system/etc/security/cacerts`, so kinframe can't connect. The app handles this itself in newer versions; as a
fallback the convert tools add the root to the system CA store when the frame runs Android before 7 (API < 24), the
file `/system/etc/security/cacerts/6187b673.0` is missing (`ls` says "No such file"), and /system can be changed
(root, not dm-verity). It uses the boot animation's pattern: push, remount /system writable, copy, `chmod 644`,
`chown 0:0`, remount read-only (always), compare the sha1 on the frame. A failure is a warning; the convert goes on.

The file is [`web/static/frame-certs/6187b673.0`](../../web/static/frame-certs/6187b673.0), served by the website at
`<server>/frame-certs/6187b673.0`: Android's CA store format (the PEM, then the certificate as text and its SHA-1
fingerprint), named after OpenSSL's `subject_hash_old`. It holds the same certificate as the app's
`android/frame/src/main/res/raw/isrg_root_x1.pem` (SHA-256 fingerprint
`96:BC:EC:06:26:49:76:F3:74:60:77:9A:CF:28:C5:A7:CF:E8:A3:C0:AA:E1:1A:8F:FC:EE:05:C0:BD:DF:08:C6`), made with
`openssl x509 -in isrg_root_x1.pem; openssl x509 -in isrg_root_x1.pem -noout -text; openssl x509 -in isrg_root_x1.pem -noout -fingerprint -sha1`
(with `sha1` written `SHA1` as in Android's files). Both tools only accept the file whose sha256 is
`ROOT_CERT_SHA256` in the shared plan, so no server can plant another certificate; the script prefers the copy in a
checkout next to it, then the chosen server, then the hosted one. `plan.test.ts` checks the file, its fingerprint and
the pinned sha256. The revert leaves it in place (a standard public root); to remove it by hand, remount /system
writable and `su 0 rm /system/etc/security/cacerts/6187b673.0`.

## One plan, two tools

The constants (packages, permissions, model allow-list, certificates, default server, scratch directory, wait time) and every
command template live in [`web/src/lib/convert/plan.ts`](../../web/src/lib/convert/plan.ts). The script repeats them
between `# >>> shared plan` and `# <<< shared plan`, with `{name}` written as `$name`.
`web/src/lib/convert/plan.test.ts` parses that block and fails when the two differ, or when the script stops using a
template. To change a step: change `plan.ts` and the block together, then run `npm test` in `web/` and
`shellcheck -s sh tools/convert/kinframe-convert.sh`.

The script's `--full-backup` (partition images) has no website equivalent: copying gigabytes over WebUSB is too slow.

## Tests

- `web/`: `npm test` covers the planner, device identification, APK certificate and sha256 checks (with signed
  fixtures in `web/src/lib/convert/fixtures/`, made with a throwaway test key), the runner (order, dry run, stopping
  before Frameo is disabled when the install fails, revert going on after errors, the data backup's fallback and
  warning, a package that came back after the restart, the root certificate step, the updater leftovers and their
  clean-up, the hosts block, the extra packages and their record) and the shared-plan check.
  `tar.test.ts` extracts the website's archives (a 120-byte file name, deep paths, non-ASCII names) with the system
  `tar`; `runner.test.ts` does the same for a whole data backup of a fake frame.
- Boot logo: `rsce.test.ts` round-trips the resource image format on a synthetic image; `runner.test.ts` simulates a
  partition (write and verify, a corrupted write that is rolled back, a rollback that fails, refusing a wrong logo or a
  backup without a device tree); `plan.test.ts` runs the script's `--patch-resource` and compares it with `rsce.ts`.
  `plan.test.ts` also checks the committed boot files against `manifest.json` and the variant choice.
  Checked by hand (not in the tests, the images are the vendor's): parsing a real Denver PFF-1024 resource partition and
  rebuilding it with its own logos gives the identical 16 MiB image, with both implementations; a real Denver PFF-1515
  resource partition passes `--check-resource`, and `--patch-resource` with `logo-1920x1080-r180.bmp` keeps the header
  and the device tree byte for byte, with the same bytes from `rsce.ts`.
- The script: `shellcheck -s sh kinframe-convert.sh`; `--dry-run` against a frame.
- On a frame (by hand, see the issue's "Done when"): convert and revert the Denver PFF-1024 from a clean state,
  repeatedly, with both tools.
