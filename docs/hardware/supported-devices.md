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

## Status

| Frame | `brand/device` | Status | Notes |
|---|---|---|---|
| Denver PFF-1024 (10.1", 1280×800) | `denver/PFF_1024` | **Supported** (tested) | Rockchip RK3126, Android 6.0.1, root via `/system/xbin/su`. Profile: [denver-pff-1024.md](denver-pff-1024.md). |
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
