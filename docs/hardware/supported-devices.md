# Supported frames

Which stock photo frames the convert tool (website `/convert`, `tools/convert/kinframe-convert.sh`) can turn into
kinframe frames. Both tools read the model from `ro.product.brand` and `ro.product.device` and compare it with
`SUPPORTED_MODELS` in `web/src/lib/convert/plan.ts`.

## Requirements

A frame can run kinframe when **all** of these hold. The tools check them before they change anything:

| Requirement | How it is checked | Why |
|---|---|---|
| Android 6.0 (API 23) or newer | `getprop ro.build.version.sdk` | The frame app's `minSdk`. |
| ARM CPU (`armeabi-v7a` or `arm64-v8a`) | `getprop ro.product.cpu.abilist` | Native libraries in the APK. |
| ADB can be turned on | The frame shows up in `adb devices` | It's the only way in without opening the frame. |
| Root for the adb shell (`su 0 id` prints `uid=0`) | `su 0 id` | Needed to turn off the stock app and the firmware updaters with `pm disable-user`. Without that, the stock app and kinframe compete for the home screen and the updater may undo the change. |

Optional, for kinframe's start-up screen (the tools skip what a frame doesn't allow and still convert it):

| Feature | Needs | Checked by |
|---|---|---|
| Boot logo | Root and a Rockchip resource partition (`/dev/block/platform/*/by-name/resource`) holding an RSCE image with a device tree and `logo.bmp` / `logo_kernel.bmp`, and a kinframe logo with exactly the same format and panel rotation (`ro.sf.hwrotation`). kinframe has 800×1280 at 270° and 1920×1080 at 180°, 8-bit, RLE8. | `ls` of the by-name link; the backup is parsed before anything is written, and the format compared. |
| Boot animation | Root, a `/system` that is not a dm-verity device (so it can be remounted writable), and a kinframe animation for the screen size (`wm size`: 1280×800 or 1920×1080). | `mount`, `wm size`. |
| Let's Encrypt root | Android 6 (API < 24) without ISRG Root X1 in `/system/etc/security/cacerts`, root, and a `/system` that is not dm-verity. Added as `6187b673.0`. | `ls /system/etc/security/cacerts/6187b673.0`. |

## Status

| Frame | `brand/device` | Status | Notes |
|---|---|---|---|
| Denver PFF-1024 (10.1", 1280×800) | `denver/PFF_1024` | **Supported** (tested) | Rockchip RK3126, Android 6.0.1, root via `/system/xbin/su`. Boot logo and animation replacement is implemented but not yet tried on a frame (boot chain and recovery: [denver-pff-1024.md](denver-pff-1024.md#boot-chain)). Profile: [denver-pff-1024.md](denver-pff-1024.md). |
| Denver PFF-1515 (15.6", 1920×1080) | `denver/PFF-1515` | **Untested** (converted once) | Rockchip RK3128, Android 6.0.1, root via `/system/xbin/su`. The first conversion (with `--force`) worked, but found four problems, now fixed in both tools and not yet re-tried on the frame: the data backup (tar's 100-byte limit), the boot logo (needed a 1920×1080 variant), an updater enabled again after the restart, and a missing Let's Encrypt root. Revert not yet tried. Still needs `--force` / the checkbox until a convert and revert pass with the fixes. Profile: [denver-pff-1515.md](denver-pff-1515.md). |
| Other Frameo frames on Android 6+ with adb root | — | **Untested** | Meet the requirements, so they will probably work. The tools refuse them unless you confirm (website checkbox, CLI `--force`). Run a dry run first and please report the result. |
| Frames on Android 5 or older | — | **Unsupported** | Below the frame app's `minSdk`. |
| Frames where `su` is missing in the adb shell | — | **Unsupported** | The stock app can't be turned off reliably. |
| Frames where ADB can't be turned on | — | **Unsupported** | No way to install. |

Other models are listed here once someone has converted one and reverted it again.

## Reporting a frame

Run the tool's dry run (website: tick "Dry run"; CLI: `--dry-run`) and open an issue with:

- the log (website: "Copy log"; CLI: the `kinframe-convert-<date>.log` file),
- whether converting and reverting worked, and anything you had to do by hand on the frame.

The log contains the model, Android version, fingerprint and the installed app versions. It holds no Wi-Fi
passwords, photos or account details.
