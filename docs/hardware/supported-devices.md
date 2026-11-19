# Supported frames

kinframe runs on two kinds of devices:

- **Converted Frameo frames** (below): the convert tool replaces the stock app with kinframe's GitHub build, which uses
  the frame's root for the things only root can do.
- **Android tablets** (and phones), with the frame app from Google Play: see
  [Android tablets from Google Play](#android-tablets-from-google-play) and [play-frame.md](../play-frame.md).

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
| Denver PFF-1024 (10.1", 1280×800) | `denver/PFF_1024` | **Supported** (tested) | Rockchip RK3126, Android 6.0.1, root via `/system/xbin/su`. Converted, hardened (updaters, hosts block, unused apps) and checked after the restart, boot logo and animation included (2026-11-19). Boot chain and recovery: [denver-pff-1024.md](denver-pff-1024.md#boot-chain). Profile: [denver-pff-1024.md](denver-pff-1024.md). |
| Denver PFF-1515 (15.6", 1920×1080) | `denver/PFF-1515` | **Supported** (tested) | Rockchip RK3128, Android 6.0.1, root via `/system/xbin/su`. Converted, reverted (`--keep-data`) and converted again with the fixes from its first conversion (data backup with long names, 1920×1080 boot logo, updater kept off across the restart, Let's Encrypt root), including a frame whose Frameo had updated itself (2026-11-18/19). Profile: [denver-pff-1515.md](denver-pff-1515.md). |
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

## Android tablets from Google Play

Any Android tablet can be a kinframe frame with the frame app's Google Play build (`play` flavor,
[play-frame.md](../play-frame.md)). Nothing is converted: install kinframe from Play, go through the setup, and in its
last step make kinframe the home app.

| Requirement | Why |
|---|---|
| Android 6.0 (API 23) or newer | The frame app's `minSdk`. |
| ARM CPU (`armeabi-v7a` or `arm64-v8a`) | Native libraries (QuickJS for widgets). Play doesn't offer the app to x86 tablets. |
| Wi-Fi | Photos come from the kinframe server. |
| Google Play (Store and services) | To install it and for updates. Without Play, the GitHub APK can be installed by hand; it then updates itself from the server. |
| kinframe as the home app (recommended) | Starts at power-on, shows over the lock screen, Back stays in kinframe. Without it kinframe is an ordinary app. |
| Charger connected (recommended) | The screen stays on all day. |

What works without root (everything else is the same as on a converted frame):

| Feature | On a tablet from Play |
|---|---|
| Slideshow, videos, captions, reactions, widgets, calendars, birthdays, weather, owner PIN | Same. |
| Sleep schedule | The screen goes black at the lowest backlight instead of the backlight switching off; touch wakes it. On LCD panels a faint glow can show in the dark. |
| Brightness | kinframe's own window brightness; the tablet's system brightness stays as it was. |
| Updates | Google Play in-app updates (asks once when the frame is idle, installs when idle again; at once when the server requires a newer version). No rollback. |
| Clock | Android's network time. If the clock is wrong (no network after a restart), the frame says so and hides the time until it is right. |
| Start at power-on | Only when kinframe is the home app. |
| Restart (frame menu, remote `restart`) | Restarts the app, not the tablet. |
| Power off | Stops playback, saves everything and shows "safe to unplug"; file systems are not remounted read-only. Holding the tablet's power button is the better way to switch it off. |
| SD card / USB stick export and import | Through Android's folder picker; eject from Android's settings. |
| Importing photos from Frameo | Not applicable (Frameo isn't installed). |
| Boot logo, boot animation, Let's Encrypt root | Not changed (that's the convert tool's job on frames). Android 6 tablets get the Let's Encrypt roots from the app itself. |
