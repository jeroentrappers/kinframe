# Denver PFF-1024 (10.1" Frameo frame): hardware profile

Probed over USB adb on 2026-10-22 using read-only commands only. The frame ships with the stock Frameo app.
Supported by the convert tool (website `/convert`, `tools/convert/`); see [supported-devices.md](supported-devices.md).

## Summary

| Area | Finding | Impact on kinframe |
|------|---------|--------------------|
| SoC | Rockchip RK3126: 4× Cortex-A7, 1.0 GHz, 32-bit `armeabi-v7a` only | Ship armeabi-v7a native libs. Keep the frame app lean and avoid heavy animation. |
| GPU | Mali-400 MP, OpenGL ES 2.0 | Crossfade and Ken Burns effects are fine; no GLES3 effects. |
| RAM | 1 GB total, about 130 MB free with Frameo running | Decode bitmaps at screen size. Never load full-resolution originals. |
| OS | Android 6.0.1 (API 23). Security patch 2017-01-01. Kernel 3.10. ODM build 2026-05-20 (`F6010T_ZhiHeXing_Frameo`) | **minSdk 23**, which still works with current AndroidX and Compose. The OS gets no updates, so the device must never accept inbound connections. |
| Display | 1280×800 at 160 dpi (mdpi), about 56.6 Hz. Panel is mounted rotated (`ro.sf.hwrotation=270`). Landscape by default. | Server renders frame images at a 1280×800 target, i.e. fit within 1280 on the long edge. |
| Touch | `gsl3673` capacitive multitouch, 1280×800 | Swipe, tap and long-press gestures are all possible. |
| Backlight | `rk28_bl` PWM backlight, 0–255. Android brightness range 1–255. Auto-brightness off (no real light sensor). | Brightness via the window brightness API (no root). Schedule- or sun-based brightness in the app. |
| Buttons | `rk29-keypad`: power, wake, volume up and volume down | Power/wake toggles the screen. Volume keys can control video volume or be remapped. |
| Motion | Accelerometer `gs_mir3da` produces live data (about 1g split across y and z in its current tilt). Gyroscope, compass, pressure, temperature, light and proximity are **stock placeholder entries in the Rockchip sensor list**: no matching input device exists. | Auto-rotate between landscape and portrait. Detect bumps and pick-ups. **No light or proximity sensing.** |
| Camera | None (0 camera devices, no `/dev/video*`) | No camera-based presence detection. |
| Audio out | Built-in speaker (RK312x codec) | Video sound and notification chime. |
| Audio in | Codec exposes capture PCMs. Mixer has `Capture MIC Path` (currently `MIC OFF`) and `Mic Jack Switch`. Audio policy lists a built-in mic, but `android.hardware.microphone` is **not** advertised. | **No microphone fitted** (confirmed by test, see below). |
| Wi-Fi | Rockchip RK915 SDIO, 2.4 GHz 802.11b/g/n (connected at 2412 MHz, RSSI −62, 13–58 Mbps) | 2.4 GHz only. Keep downloads resumable and small (1280-pixel renditions). |
| Bluetooth | **None.** Only a WLAN rfkill switch exists, the device tree has no Bluetooth node, `ro.rk.bt_enable=false`, and `com.android.bluetooth` isn't installed. The kernel's Bluetooth stack and the `rtkbt` configs are board-template leftovers, as is the `android.hardware.bluetooth` feature flag. | No Bluetooth onboarding or phone-presence detection. Wi-Fi setup goes through the touchscreen, a QR handoff or adb provisioning. |
| Storage | 16 GB eMMC, 12.8 GB `/data` (12.3 GB free). Unencrypted. | Room for roughly 30k frame-sized photos. |
| Removable | vold config has an SD card slot (`sdcard1`) and two USB host ports (vfat). USB-C is OTG/host (`usb.host`). | Import and backup to microSD or USB stick is possible. |
| Power | **Mains only, no battery.** `/sys/class/power_supply` is empty and healthd logs "No charger supplies found / No battery devices found". The dumpsys battery values (AC, 100%, 0 V, 42.4 °C) are placeholders. The device tree's RK818 PMIC/charger node is a board-template leftover that never probes. | Pulling the plug is a hard power cut: atomic writes (temp file + rename) and a WAL-mode SQLite index are required. Power-on boots straight into kinframe as the home app. No battery logic. |
| Root | **`/system/xbin/su` gives root to the adb shell user** (uid 0). `ro.secure=1`, `ro.debuggable=0`. `/system` is mounted read-only straight from its block device (no dm-verity device in `mount`), so root can remount it read-write. | No Magisk needed. Provisioning can disable Frameo and the OTA apps, set kinframe as home and grant permissions over adb. |
| Updaters | `com.adups.fota` and `com.adups.fota.sysoper` (Adups firmware updates). Frameo app v1.32.16 (versionCode 80320) under `/data/app`, self-updated 2026-10-21. | Disable both Adups packages and Frameo during provisioning. |
| Other preinstalled | `com.cghs.stresstest`, `com.DeviceTest` (factory test apps), OpenWnn IME, Pico TTS, WebView | Leave alone or disable. |
| Locale | `nl-NL`, `Europe/Amsterdam` | |

## Boot chain

What the screen shows from power-on until the home app, and where it comes from (read-only probes, 2026-10-25):

1. **Bootloader** (Rockchip U-Boot, `uboot` partition) loads the **resource partition** and shows `logo.bmp` from it.
2. **Kernel** (`kernel` partition, with the ramdisk in `boot`) starts with the device tree `rk-kernel.dtb` from the
   same resource partition and shows `logo_kernel.bmp` (the stock firmware has the same "DENVER" image twice).
3. **Android** shows `/system/media/bootanimation.zip` until `sys.boot_completed`; there is no `/oem` copy. The stock
   one is the photo frame app's (870×432 frames on white, four parts).
4. The **home app** starts (the stock app, or kinframe after converting).

**The resource partition** is a Rockchip "RSCE" image: a 512-byte header (`RSCE`, header size 1 block, table at
block 1, 1 block per entry, 3 entries), one block per entry (`ENTR`, a 256-byte name, content offset in blocks,
size in bytes; no per-entry hashes on this frame) and the contents on block boundaries, zero-filled to 16 MiB:

| Entry | Block | Size | Format |
|---|---|---|---|
| `rk-kernel.dtb` | 4 | 105 148 bytes | Flattened device tree v17. **The kernel can't start without it.** |
| `logo.bmp` | 210 | 30 276 bytes | Windows BMP, **800×1280** (the panel's native portrait scan-out), 8 bits per pixel, 256-colour palette, **BI_RLE8** with encoded runs only, an end-of-line marker after every row, bottom-up |
| `logo_kernel.bmp` | 270 | 30 276 bytes | the same |

The panel is mounted rotated (`ro.sf.hwrotation=270`): Android draws 1280×800 landscape, but the bootloader and
kernel draw the native 800×1280 picture, so a logo that should look upright in landscape is stored rotated 90°
counter-clockwise. kinframe's logo (`tools/brand/boot/build.py`) follows exactly this format, and the convert tool
refuses to write a logo whose size, depth or compression differs from the frame's own.

**Partitions** (`/dev/block/platform/1021c000.rksdmmc/by-name/`, eMMC `mmcblk0`, 512-byte sectors as Linux sees them):

| Name | Device | Start sector | Size |
|---|---|---|---|
| uboot | mmcblk0p1 | 16384 | 4 MiB |
| misc | mmcblk0p2 | 24576 | 4 MiB |
| resource | mmcblk0p3 | 32768 | 16 MiB |
| kernel | mmcblk0p4 | 65536 | 12 MiB |
| boot | mmcblk0p5 | 90112 | 12 MiB |
| recovery | mmcblk0p6 | 114688 | 32 MiB |
| backup | mmcblk0p7 | 180224 | 64 MiB |
| cache, metadata, kpanic, system, radical_update, userdata | mmcblk0p8–p13 | | |

The device nodes belong to `radio:radio` with mode 660, so reading them needs root. The frame has `dd`, `sync`,
`blockdev`, `md5sum` and `sha1sum` (toolbox/toybox) but no `sha256sum`: the convert tool compares sha256 on the
computer after pulling the read-back.

## Recovering a damaged resource partition

The convert tool backs the partition up as `resource.img` before writing it, verifies the write and writes the
backup back itself on a mismatch. If a write was still interrupted (power cut, cable pulled) the frame may not boot:
without the device tree the kernel can't start. The bootloader lives in its own partition and is not touched.

1. **The frame still runs Android** (nothing restarted yet): put the backup back over adb.
   `sh tools/convert/kinframe-convert.sh --revert --restore-resource kinframe-backup-…/resource.img` does it with the
   same verify-and-roll-back steps, or by hand:
   ```sh
   adb push resource.img /data/local/tmp/resource.img
   adb shell su 0 dd if=/data/local/tmp/resource.img of=/dev/block/platform/1021c000.rksdmmc/by-name/resource bs=1048576
   adb shell su 0 sync
   ```
   then read it back (`su 0 dd if=… of=/data/local/tmp/check.img`, `su 0 chmod 644 …`, `adb pull`) and compare the
   sha256 with the backup's before restarting.
2. **The frame no longer boots**: use Rockchip's USB flashing modes with
   [`rkdeveloptool`](https://github.com/rockchip-linux/rkdeveloptool) (or Rockchip's `upgrade_tool` / RKDevTool on
   Windows). *Not yet tried on this frame*; the outline is the usual one for RK312x:
   - **Loader mode** (the bootloader's USB download mode): hold the volume-up key while connecting the USB cable
     (or `adb reboot loader` while it still runs). `rkdeveloptool ld` lists the device as `Loader`.
   - **Maskrom mode**, if the bootloader itself doesn't respond: the boot ROM's mode, reached by shorting the eMMC
     clock (or a maskrom test point) to ground while connecting USB. Then `rkdeveloptool db <rk312x loader .bin>`
     first (Rockchip's `rk312x_loader_*.bin` from the rkbin repository).
   - Write the backup by partition name: `rkdeveloptool wlx resource resource.img` (needs a build with `wlx`), or
     look the partition's start up with `rkdeveloptool ppt` and use `rkdeveloptool wl <start> resource.img`. Note
     that the start sectors Linux reports (32768 for resource) can differ from the loader's numbering on Rockchip's
     older "parameter" layouts, so use the loader's table, not the table above. Then `rkdeveloptool rd`.
3. The convert script's `--full-backup` copies every partition except user data (`mmcblk0p3.img` is the resource
   partition), which is the last resort for the other boot partitions as well.

## Presence detection

The frame **cannot detect presence directly**. It has no PIR sensor, camera, proximity sensor or light sensor. Its feature
flags claim more, but they come from a generic Rockchip template.

Options we can offer instead:

1. **Touch and buttons**: any interaction wakes the frame and resets an idle timer.
2. **Accelerometer**: detects the frame being bumped or picked up, not a person walking past.
3. **External presence through the backend**: Home Assistant, phone-on-Wi-Fi presence or a cheap Zigbee or ESPHome PIR
   sends wake/sleep commands over the frame's websocket. This is a likely "and more" feature.

## Microphone: none fitted (confirmed)

Tested on 2026-10-22 with `tools/mictest`. It records 3 s of ambient sound, then 3 s while the frame's own speaker plays
a 1 kHz tone. A person in the room confirmed hearing the tone. Across four runs, two of them with the mixer's
`Capture MIC Path` forced to `Main Mic` and `Hands Free Mic` as root:

- The input reads only a slow DC drift, as the codec's DC filter settles. There is no energy above about 3 Hz, and
  the audio-band level (RMS of sample-to-sample change) is about 1.3 LSB both with and without the tone.
- There is no 1 kHz component. Readings are nearly identical from run to run, which real acoustic input never is.

The codec has a mic input, but nothing is connected to it. **Privacy:** with no camera and no mic, even a compromised
frame cannot watch or listen to the room.

## Raw identifiers

- `ro.product.brand=denver`, `ro.product.manufacturer=Harmony`, `ro.product.device=PFF_1024`, `ro.board.platform=rk312x`
- Fingerprint `rockchip/rk312x/rk312x:6.0.1/MXC89K/user.wanghy.20260520.085907:user/release-keys`
- USB VID:PID `2207:0006` (Rockchip). OpenGL ES version property `131072` (= 2.0).
