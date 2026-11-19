# Denver PFF-1515 (15.6" Frameo frame): hardware profile

Probed over USB adb on 2026-11-18, factory-fresh (never connected to Wi-Fi), then converted with the convert tool. The
frame ships with the stock Frameo app. Status: **supported** in the convert tool (see
[supported-devices.md](supported-devices.md)): the problems the first conversion found are fixed in both tools, and a
revert and a second conversion with the fixes passed on this frame. Compare the [Denver PFF-1024](denver-pff-1024.md), a
10.1" sibling on the same platform.

## Summary

| Area | Finding | Impact on kinframe |
|------|---------|--------------------|
| Model | `ro.product.brand=denver`, `ro.product.device=PFF-1515` (**hyphen**; the PFF-1024 is `PFF_1024` with an underscore), `ro.product.model=Frame`, manufacturer Harmony, PCBA ChipTrip | The convert tool's model key is `denver/PFF-1515`. |
| SoC | Rockchip RK3128 (`ro.rk.soc=rk3128`, platform `rk312x`; the Adups config still says RK3126): 4× Cortex-A7, 32-bit `armeabi-v7a` only | Same as the PFF-1024: armeabi-v7a native libs, keep the app lean. |
| GPU | Mali-400 MP (`r6p0`), OpenGL ES 2.0 | No GLES3 effects. |
| RAM | 1 GB | Decode bitmaps at screen size. |
| OS | Android 6.0.1 (API 23), security patch **2016-07-05**. Firmware `H156_ZhiHeXing_1560_Frameo-V1.0.1202512231056`, built 2025-12-23 (`rockchip/rk312x/rk312x:6.0.1/ZhiHeXing/user.liubc.20251223.105514:user/release-keys`) | minSdk 23 works. The OS gets no security updates. |
| Display | **1920×1080** landscape at 213 dpi (tvdpi). Panel mounted upside down (`ro.sf.hwrotation=180`) | Server renders at a 1920×1080 target. |
| Stock app | Frameo v1.29.10 (versionCode 59940) in **`/system/priv-app/Frameo.apk`** (not updated yet, so `pm path` gives the system copy) | Disabled by the convert, never uninstalled. |
| Updaters | `com.adups.fota` 5.24 (`/system/app/FotaUpdate`) checks the vendor's servers about once a day and downloads updates; `com.adups.fota.sysoper` 5.3.7 (`/system/app/FotaUpdateReboot`) is the only one that can start recovery to install them, and the server can mark an update to be installed without asking. Rockchip's `android.rockchip.update.service` (RKUpdateService 1.8.1, `/system/app/RKUpdateService`) offers `update.img` / `update.zip` files it finds on a card or USB stick after a restart or when storage is mounted. The factory image has an empty `/cache/recovery`. | The convert clears the Adups state and downloads, disables all three, checks after the restart, and blocks the Adups servers in `/system/etc/hosts` ([README](../../tools/convert/README.md#firmware-updaters)). A factory reset re-enables them (the disabled state is in /data); the hosts block stays. |
| Extra apps | Browser, calculator, music, sound recorder, gallery, Rockchip video player and file explorer, screen savers, live wallpapers, print spooler, Pico TTS, the factory test apps `com.cghs.stresstest` and `com.DeviceTest` | Disabled by the convert where installed ([README](../../tools/convert/README.md#extra-packages)); the revert turns them back on. System UI stays as the vendor left it (disabled). |
| System UI | `com.android.systemui` is disabled by the vendor (no status or navigation bar) | kinframe runs full screen anyway; nothing to do. |
| Wi-Fi | The factory `wpa_supplicant.conf` already holds an **open network `MERCURY_7F4D10`** (the factory's test access point) | A frame near an open access point with that name joins it on its own, unencrypted. Worth removing from the saved networks after setup. |
| TLS | **No ISRG Root X1** in `/system/etc/security/cacerts` (157 roots, no `6187b673.0`), so Let's Encrypt certificates don't verify with the system store | kinframe can't reach `kinframe.appmire.be` with the system trust store. Newer apps bring their own roots; the convert tool adds ISRG Root X1 to /system as a fallback ([README](../../tools/convert/README.md#lets-encrypt-root-certificate)). |
| Storage | 16 GB eMMC (`mmcblk0`, 15 758 000 128 bytes), `/data` 13.3 GiB. Unencrypted (`ro.crypto.state=unencrypted`) | Plenty of room. |
| Bluetooth | **None.** No `hci` device, only a WLAN rfkill switch, `ro.rk.bt_enable=false`. The Wi-Fi chip is an iComm/SSV `ssv6x5x` on SDIO (vendor/device `0x3030`), Wi-Fi only. The `android.hardware.bluetooth`/`bluetooth_le` feature flags are board-template leftovers | As on the PFF-1024: no Bluetooth onboarding or presence detection. |
| Wi-Fi | iComm/SSV `ssv6x5x` SDIO, 2.4 GHz (differs from the PFF-1024's RK915) | 2.4 GHz only. |
| Camera | **None.** No `/dev/video*`, no V4L2 class, no camera HAL in use | No video calls or presence detection by camera. |
| Microphone | **No usable microphone.** The RK312x codec exposes capture PCMs, but `tools/mictest` (2026-11-18, three runs, also with `Capture MIC Path` set to Main Mic and Hands Free Mic as root; the audio HAL resets it to `MIC OFF` when recording starts) reads a flat noise floor: 1 kHz energy 0.1–0.2 ambient and a constant 3.5 while the speaker plays the tone (threshold for a microphone: 50 and 20×). Unlike the PFF-1024 there is a small AC signal, most likely crosstalk inside the codec rather than a fitted microphone. Not yet confirmed with a person talking next to the frame | No voice features. |
| Sensors | Real: accelerometer `gs_mir3da` (I²C 0x26, reports gravity). The device tree also lists `gs_stk8baxx`, `gs_sc7660` and `gs_mc3236`, which don't bind. The gyroscope, compass, light, proximity, pressure and temperature sensors in `dumpsys sensorservice`, and the NFC, GPS, barometer and step-counter feature flags, are fake (generic HAL and board template; no matching I²C devices, `ro.factory.hasGPS=false`) | Orientation from the accelerometer only; no ambient-light auto-brightness. |
| Clock | `hym8563` RTC on I²C 0x51 is bound but reads garbage (`2065-25-45 45:85:85`): no backup battery or crystal. The frame starts at the factory date (2021-05-21 02:00) and is correct only after network time, about 50 s after boot | kinframe's clock must refresh on `ACTION_TIME_SET`; don't trust the time before network sync. |
| Input | Touchscreen `gsl3673` (I²C 0x40, multitouch); keypad `rk29-keypad` (Power/Wakeup). The kernel also registers an IR remote driver `rkxx-remotectl` (digits, Enter, Backspace, Home, F1–F9), but the case has no IR window and no receiver is fitted: a board-template leftover. A `gt8xx` node on I²C bus 2 doesn't bind | Touch and the power button only. |
| Other | No HDMI output in use (`ro.rk.hdmi_enable=false`), no battery, mains powered. USB: host controllers only | |
| Root | **`/system/xbin/su`** (with `daemonsu`) gives root to the adb shell (`su 0 id` prints `uid=0(root) … context=u:r:toolbox:s0`). This `su` **re-parses its arguments**: no quotes, pipes or redirects behind it. `ro.secure=1`, `ro.debuggable=0`, `ro.adb.secure=1`, SELinux permissive (`androidboot.selinux=permissive`). `/system` mounts read-only straight from its block device (no dm-verity) | Root for `pm disable-user`, the boot logo, the boot animation and the root certificate. |
| Tools | toybox and toolbox, no busybox. **`dd` needs numeric sizes**: `bs=4M` fails with "illegal number" and writes nothing (`bs=4194304` works). **toybox `tar` can't store names longer than 100 bytes** (`tar: '…' > 100 bytes`) and then leaves an empty archive; Frameo's own files hit this (a Firebase file whose name alone is ~90 bytes). `cp -a`, `chmod`, `chown`, `sync`, `sleep`, `sha1sum` and `md5sum` exist; no `sha256sum` | The convert tool uses byte sizes, never archives on the frame (it pulls a copy of the app data and archives it on the computer, [README](../../tools/convert/README.md#the-data-backup)), and compares sha256 on the computer. |
| Defaults | `ro.frameo.default.language=en`, `ro.frameo.default.timezone=Europe/Amsterdam`, brightness 204 | |

## What the first conversion found (2026-11-18)

Converted with `kinframe-convert.sh --force`, kinframe v0.6.5. Install, permissions, the home app (Android 6: the only
home left) and provisioning worked. Problems, each fixed in both tools since:

1. **Data backup failed**: `su 0 tar -czf … -C /data/data net.frameo.frame` stopped at
   `net.frameo.frame/files/.crashlytics.v3/net.frameo.frame/open-sessions/<32 hex>/` (over 100 bytes) and left an
   empty archive. After Frameo updated itself to v1.32.18 (`/data/app/net.frameo.frame-1`), even a pruned copy with
   short relative names failed on `./files/PersistedInstallation.W0RFRkFVTFRd+MTo2NDA1….json`. Now: no tar on the
   frame; a pruned copy is pulled as a directory and archived on the computer, and a failure is a warning.
2. **Boot logo refused**: kinframe only had an 800×1280 logo. Now there is a 1920×1080, rotation 180 variant, and a
   1920×1080 boot animation.
3. **`com.adups.fota.sysoper` was enabled again after the restart**, while Frameo and `com.adups.fota` stayed off: the
   restart came right after the last `pm disable-user`, before PackageManager saved its package-restrictions file
   (it writes about 10 s after a change). Now: the tools check `pm list packages -d`, wait 15 s and sync before the
   restart, and check again afterwards (re-applying and warning when a package came back).
4. **No Let's Encrypt root** (see TLS above). Now added to /system as a fallback.

## Boot chain

The same Rockchip chain as the [PFF-1024](denver-pff-1024.md#boot-chain): U-Boot shows `logo.bmp` from the resource
partition, the kernel starts with `rk-kernel.dtb` from it and shows `logo_kernel.bmp`, then Android shows
`/system/media/bootanimation.zip` until `sys.boot_completed` (no `/oem` copy).

**The resource partition** (`/dev/block/platform/1021c000.rksdmmc/by-name/resource`, 16 MiB) is an RSCE image:
header size 1 block, table at block 1, 1 block per entry, 3 entries, no per-entry hashes, zero-filled to 16 MiB.

| Entry | Block | Size | Format |
|---|---|---|---|
| `rk-kernel.dtb` | 4 | 78 614 bytes | Flattened device tree. **The kernel can't start without it.** |
| `logo.bmp` | 158 | 59 540 bytes | Windows BMP, **1920×1080**, 8 bits per pixel, 256-colour palette, **BI_RLE8** with encoded runs only, an end-of-line marker after every row, bottom-up. The stock "denver" logo, stored **upside down** |
| `logo_kernel.bmp` | 275 | 59 540 bytes | the same |

The panel is mounted upside down (`ro.sf.hwrotation=180`): Android turns its picture, the bootloader and kernel don't,
so an upright logo is stored rotated 180°. kinframe's `logo-1920x1080-r180.bmp` (`tools/brand/boot/build.py`) follows
this; the convert tool picks it because both the format and the rotation match. Checked offline on a copy of this
frame's partition: `kinframe-convert.sh --check-resource` accepts it, and `--patch-resource` with the kinframe logo
keeps the header and the device tree byte for byte (the same bytes as `rsce.ts`).

The stock boot animation is 870×439 at 15 fps, four parts on white; Android centres it on the 1920×1080 screen.
kinframe's `bootanimation-1920x1080.zip` has a 972×454 frame, sized so the wordmark matches the logo.

**Partitions** (eMMC `mmcblk0`, 512-byte sectors as Linux sees them; names from the kernel command line's `mtdparts`,
whose offsets are 8192 sectors lower; sizes from `/proc/partitions`):

| Name | Device | Start sector | Size |
|---|---|---|---|
| (loader area, IDB at sector 64) | — | 0 | 8 MiB |
| uboot | mmcblk0p1 | 16384 | 4 MiB |
| misc | mmcblk0p2 | 24576 | 4 MiB |
| resource | mmcblk0p3 | 32768 | 16 MiB |
| kernel | mmcblk0p4 | 65536 | 12 MiB |
| boot | mmcblk0p5 | 90112 | 12 MiB |
| recovery | mmcblk0p6 | 114688 | 32 MiB |
| backup | mmcblk0p7 | 180224 | 64 MiB |
| cache | mmcblk0p8 | 311296 | 128 MiB |
| metadata | mmcblk0p9 | 573440 | 16 MiB |
| kpanic | mmcblk0p10 | 606208 | 4 MiB |
| system | mmcblk0p11 | 614400 | 1 GiB |
| radical_update | mmcblk0p12 | 2711552 | 64 MiB |
| userdata | mmcblk0p13 | 2842624 | 13 967 360 KiB (13.3 GiB) |

The device nodes need root to read. Recovering a damaged resource partition works as on the
[PFF-1024](denver-pff-1024.md#recovering-a-damaged-resource-partition), with two differences: loader and maskrom mode
need an **RK3128** loader (`rk3128_loader_*.bin` from Rockchip's rkbin), and `dd` needs `bs=1048576`, not `bs=1M`.

## Raw identifiers

- `ro.product.brand=denver`, `ro.product.manufacturer=Harmony`, `ro.product.device=PFF-1515`, `ro.product.name=PFF-1515`, `ro.board.platform=rk312x`, `ro.rk.soc=rk3128`
- `ro.build.display.id=H156_ZhiHeXing_1560_Frameo-V1.0.1202512231056.release-keys`, `ro.build.version.security_patch=2016-07-05`
- `ro.sf.hwrotation=180`, `ro.sf.lcd_density=213`, `ro.opengles.version=131072` (= 2.0)
