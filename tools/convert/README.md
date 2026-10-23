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
```

| Option | Meaning |
|---|---|
| `--dry-run` | Only read from the frame (and download and verify the APK). Every change is printed as "would run". |
| `--revert` | Enable the stock app and the updaters, uninstall kinframe, restart. |
| `--server URL` | Your own kinframe server. Default `https://kinframe.appmire.be`. |
| `--language CODE` | The frame's language (`en`, `nl`, `de`, …; the 36 codes of `docs/i18n.md`). Default: the frame asks. |
| `--force` | Continue on a model that hasn't been tested. |
| `--no-backup` / `--backup-dir DIR` | Skip the backup / choose where it goes (default `./kinframe-backup-<serial>-<date>`). |
| `--full-backup` | Also copy every eMMC partition except user data, for a last-resort restore. Read-only, but slow. |
| `--apk FILE` / `--cert SHA256` | Install a local APK instead of the latest release / expect another signing certificate (your own build). |
| `--keep-data` | With `--revert`: keep kinframe's data, so a later install is still in the family. |
| `--restore-apk FILE` | With `--revert`: reinstall the backed-up stock APK first (if it updated itself and broke). |
| `-s SERIAL` | Pick the frame when more than one device is connected. |
| `--log FILE` | Log file (default `./kinframe-convert-<date>.log`). Include it when you ask for help. |

`ADB=/path/to/adb` picks another adb.

## What it does

The website and the script run the same commands, from one plan. Convert:

| Step | Commands on the frame | Notes |
|---|---|---|
| Identify | `getprop`, `su 0 id`, `pm path <pkg>`, `dumpsys package <pkg>` | Read-only. Refuses unsupported frames; untested models need `--force` or the website's checkbox. |
| Back up | `adb pull` of every path from `pm path net.frameo.frame`; `su 0 tar -czf … -C /data/data net.frameo.frame` into `/data/local/tmp/kinframe-convert`, pulled, then removed | The website saves the files to your downloads. |
| Download and verify | none | Latest stable release. The website gets it from its own server (`GET /v1/frame-app/latest`, which mirrors the GitHub Releases, so the browser needs no cross-origin download); the script gets it from GitHub Releases. Both check the published sha256 and that **every** signing certificate is the release certificate `99:C5:E3:6C:…:2A:20:5C`. |
| Install | `adb push` to `/data/local/tmp/kinframe-convert/kinframe.apk`, `pm install -r -g …` | A newer kinframe already installed is kept. |
| Permissions | `pm grant app.kinframe.frame <perm>`, `appops set app.kinframe.frame WRITE_SETTINGS allow` (+ `REQUEST_INSTALL_PACKAGES` on Android 8+) | Failures are warnings. |
| Turn off | `su 0 pm disable-user net.frameo.frame`, `com.adups.fota`, `com.adups.fota.sysoper` | Only after kinframe is installed. **Never uninstalls** system packages. |
| Home app | Android 7+: `pm set-home-activity app.kinframe.frame/.MainActivity`. Android 6: nothing; kinframe is the only home app left. | |
| Clean up, restart | `rm -rf /data/local/tmp/kinframe-convert`, reboot, wait for `sys.boot_completed` | |
| Provision | `am start -n app.kinframe.frame/.MainActivity --es server_url <URL> [--es language <code>]` | After the restart: the frame keeps a provisioned server only until it enrols. |
| Check | `dumpsys activity activities` | Warns when kinframe isn't in front. |

The frame then runs kinframe's setup (language, Wi-Fi, time zone) and shows a code; enter it at `<server>/frames/add`.

Revert: optional reinstall of a backed-up stock APK (`pm install -r -d`), `su 0 pm enable` for each package that is
disabled, `pm uninstall [-k] app.kinframe.frame` (falling back to `su 0 pm uninstall`), clean up, restart. Every step
is independent and best effort, so it also repairs a half-converted frame: kinframe missing or crashing, or the stock
app turned off by hand.

Commands that run behind `su 0` only take plain arguments: the `su` on Rockchip frame firmware re-parses its
arguments, so quoting, pipes and redirects break there. Values put on the frame's command line (server URL,
language) are checked against a strict character set first.

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
- The script: `shellcheck -s sh kinframe-convert.sh`; `--dry-run` against a frame.
- On a frame (by hand, see the issue's "Done when"): convert and revert the Denver PFF-1024 from a clean state,
  repeatedly, with both tools.
