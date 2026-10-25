# Convert a frame

Turns a stock Frameo photo frame (tested: Denver PFF-1024) into a kinframe frame, and turns it back. There are two
tools that run the same steps:

- **Website**: `https://<your kinframe server>/convert` (for example https://kinframe.appmire.be/convert). Works in
  Chrome, Edge or Opera on Windows, macOS, Linux and Android over WebUSB. Nothing to install.
- **Command line**: `kinframe-convert.sh` in this folder, for Firefox and Safari users and for anyone who prefers a
  terminal. Needs Android [platform-tools](https://developer.android.com/tools/releases/platform-tools) (`adb`), `curl`,
  `unzip` and `openssl` (or `apksigner` from Android build-tools). Runs with any POSIX shell: Linux, macOS, and on
  Windows in Git Bash with the Windows `adb.exe`.

Supported and untested frames: [`docs/hardware/supported-devices.md`](../../docs/hardware/supported-devices.md).

## Before you start

1. On the frame, open Settings → About and turn on ADB access (on some frames it is under the beta program).
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
| `--boot-assets DIR` | Use `logo.bmp` and `bootanimation.zip` from `DIR` (for example `web/static/frame-boot/kinframe` in a checkout) instead of downloading them. |
| `--full-backup` | Also copy every eMMC partition except user data, for a last-resort restore. Read-only, but slow. |
| `--apk FILE` / `--cert SHA256` | Install a local APK instead of the latest release / expect another signing certificate (your own build). |
| `--keep-data` | With `--revert`: keep kinframe's data, so a later install is still in the family. |
| `--restore-apk FILE` | With `--revert`: reinstall the backed-up stock APK first (if it updated itself and broke). |
| `--restore-resource FILE` / `--restore-bootanimation FILE` | With `--revert`: write the backed-up `resource.img` (start-up logo) / `bootanimation.zip` back. A resource image that doesn't parse as one, with a device tree, is refused. |
| `--check-resource FILE` / `--patch-resource IN LOGO OUT` | No frame needed: check a resource image and list its entries / write `OUT`, which is `IN` with its boot logos replaced by `LOGO`. |
| `-s SERIAL` | Pick the frame when more than one device is connected. |
| `--log FILE` | Log file (default `./kinframe-convert-<date>.log`). Include it when you ask for help. |

`ADB=/path/to/adb` picks another adb.

## What it does

The website and the script run the same commands, from one plan. Convert:

| Step | Commands on the frame | Notes |
|---|---|---|
| Identify | `getprop`, `su 0 id`, `ls /dev/block/platform/*/by-name/resource`, `mount`, `pm path <pkg>`, `dumpsys package <pkg>` | Read-only. Refuses unsupported frames; untested models need `--force` or the website's checkbox. |
| Back up | `adb pull` of every path from `pm path net.frameo.frame`; `su 0 tar -czf … -C /data/data net.frameo.frame` into `/data/local/tmp/kinframe-convert`, pulled, then removed | The website saves the files to your downloads. |
| Back up the start-up screen | `adb pull /system/media/bootanimation.zip`; `su 0 dd if=<resource partition> of=/data/local/tmp/kinframe-convert/resource.img bs=1048576`, `su 0 chmod 644 …`, pulled, removed | Only with the boot logo option (on by default), and then also with `--no-backup`. Saved as `bootanimation.zip` and `resource.img` next to the other backups. |
| Download and verify | none | Latest stable release. The website gets it from its own server (`GET /v1/frame-app/latest`, which mirrors the GitHub Releases, so the browser needs no cross-origin download); the script gets it from GitHub Releases. Both check the published sha256 and that **every** signing certificate is the release certificate `99:C5:E3:6C:…:2A:20:5C`. With the boot logo option, also `<server>/frame-boot/kinframe/{manifest.json,logo.bmp,bootanimation.zip}`, checked against the manifest's sha256. |
| Start-up logo | see [Boot logo and animation](#boot-logo-and-animation) | Before the app changes, so a failure leaves the frame as it was. |
| Start-up animation | `adb push` to the work directory, `su 0 mount -o remount,rw <system device> /system`, `su 0 cp … /system/media/bootanimation.zip`, `su 0 chmod 644 …`, `su 0 mount -o remount,ro <system device> /system`, then `sha1sum /system/media/bootanimation.zip` must match | /system is always remounted read-only again, also when the copy failed. Skipped when /system is a dm-verity device. |
| Install | `adb push` to `/data/local/tmp/kinframe-convert/kinframe.apk`, `pm install -r -g …` | A newer kinframe already installed is kept. |
| Permissions | `pm grant app.kinframe.frame <perm>`, `appops set app.kinframe.frame WRITE_SETTINGS allow` (+ `REQUEST_INSTALL_PACKAGES` on Android 8+) | Failures are warnings. |
| Turn off | `su 0 pm disable-user net.frameo.frame`, `com.adups.fota`, `com.adups.fota.sysoper` | Only after kinframe is installed. **Never uninstalls** system packages. |
| Home app | Android 7+: `pm set-home-activity app.kinframe.frame/.MainActivity`. Android 6: nothing; kinframe is the only home app left. | |
| Clean up, restart | `rm -rf /data/local/tmp/kinframe-convert`, reboot, wait for `sys.boot_completed` | |
| Provision | `am start -n app.kinframe.frame/.MainActivity --es server_url <URL> [--es language <code>]` | After the restart: the frame keeps a provisioned server only until it enrols. |
| Check | `dumpsys activity activities` | Warns when kinframe isn't in front. |

The frame then runs kinframe's setup (language, Wi-Fi, time zone) and shows a code; enter it at `<server>/frames/add`.

Revert: optional reinstall of a backed-up stock APK (`pm install -r -d`), optional write-back of the backed-up
`resource.img` and `bootanimation.zip` (same steps as above, so the resource image is verified too), `su 0 pm enable` for each package that is
disabled, `pm uninstall [-k] app.kinframe.frame` (falling back to `su 0 pm uninstall`), clean up, restart. Every step
is independent and best effort, so it also repairs a half-converted frame: kinframe missing or crashing, or the stock
app turned off by hand.

Commands that run behind `su 0` only take plain arguments: the `su` on Rockchip frame firmware re-parses its
arguments, so quoting, pipes and redirects break there. Values put on the frame's command line (server URL,
language) are checked against a strict character set first.

## Boot logo and animation

A converted frame starts with kinframe's logo instead of the stock one (the "DENVER" logo on the Denver PFF-1024) and
kinframe's start-up animation instead of the stock app's. Both are on by default, behind one option (website checkbox,
CLI `--no-boot-logo` to skip). Where they live and how the frame boots: [denver-pff-1024.md, "Boot chain"](../../docs/hardware/denver-pff-1024.md#boot-chain).

- **The logo** is in the **resource partition** (`/dev/block/platform/*/by-name/resource`, 16 MiB), a Rockchip
  "RSCE" image with the kernel device tree (`rk-kernel.dtb`) and the logos the bootloader and kernel show
  (`logo.bmp`, `logo_kernel.bmp`). **Without an intact device tree the frame does not boot**, so this step is careful:
  1. The whole partition is backed up first (`resource.img`), and checked to be a resource image with a device tree.
  2. Our logo must have exactly the format of the frame's logos (800×1280, 8-bit palette, RLE8 on the Denver), or
     nothing is written.
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
  then shows its default animation.

Keep the backup from the **first** conversion: a second run backs up what is there, which is then kinframe's.

The images are built from our own brand assets by [`tools/brand/boot/build.py`](../brand/boot/build.py) into
`web/static/frame-boot/<brand>/` (reproducible; a `--brand` parameter leaves room for a retail brand). The website
serves them, so the script downloads them from the chosen server (falling back to the hosted one).

## One plan, two tools

The constants (packages, permissions, model allow-list, certificate, default server, scratch directory) and every
command template live in [`web/src/lib/convert/plan.ts`](../../web/src/lib/convert/plan.ts). The script repeats them
between `# >>> shared plan` and `# <<< shared plan`, with `{name}` written as `$name`.
`web/src/lib/convert/plan.test.ts` parses that block and fails when the two differ, or when the script stops using a
template. To change a step: change `plan.ts` and the block together, then run `npm test` in `web/` and
`shellcheck -s sh tools/convert/kinframe-convert.sh`.

The script's `--full-backup` (partition images) has no website equivalent: copying gigabytes over WebUSB is too slow.

## Tests

- `web/`: `npm test` covers the planner, device identification, APK certificate and sha256 checks (with signed
  fixtures in `web/src/lib/convert/fixtures/`, made with a throwaway test key), the runner (order, dry run, stopping
  before Frameo is disabled when the install fails, revert going on after errors) and the shared-plan check.
- Boot logo: `rsce.test.ts` round-trips the resource image format on a synthetic image; `runner.test.ts` simulates a
  partition (write and verify, a corrupted write that is rolled back, a rollback that fails, refusing a wrong logo or a
  backup without a device tree); `plan.test.ts` runs the script's `--patch-resource` and compares it with `rsce.ts`.
  Checked by hand (not in the tests, the image is the vendor's): parsing a real Denver PFF-1024 resource partition and
  rebuilding it with its own logos gives the identical 16 MiB image, with both implementations.
- The script: `shellcheck -s sh kinframe-convert.sh`; `--dry-run` against a frame.
- On a frame (by hand, see the issue's "Done when"): convert and revert the Denver PFF-1024 from a clean state,
  repeatedly, with both tools.
