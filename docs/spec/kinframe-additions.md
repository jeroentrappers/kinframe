# kinframe additions ("and more")

Improvements over Frameo that come from hands-on testing, the hardware analysis and user pain points. These feed into
the product spec.

## Power and storage safety

**Observed problem:** Frameo warns not to unplug the frame while it is on, but its power-off flow never says when it is
safe to unplug. Frameo frames have no battery (see `docs/hardware/denver-pff-1024.md`). After Android shuts down, the
kernel halts with nothing left running to show a message. Users pull the plug at an unknown moment. Public reports link
unclean power loss to file-system corruption, lost license keys, flicker and dead touch (`frameo-public-features.md`,
pain point 11).

**kinframe behaviour:**

1. **Explicit "Safe to unplug" state.** Power off shows "Shutting down…", stops playback, finishes or cancels downloads
   and checkpoints the local database. It then flushes and remounts all writable file systems read-only (root is
   available on Frameo hardware). Finally it shows a calm full-screen "It's now safe to unplug" message with a dimmed
   backlight. The message stays until power is removed. Pressing the power button instead restarts the frame.
2. **Unplug-anytime robustness.** All writes are crash-safe: downloads go to a temp file, are fsynced, then renamed
   into place, and the media index is SQLite in WAL mode. Unplugging without shutting down should never corrupt the
   library. The warning becomes advice, not a risk.
3. **Clean-shutdown tracking.** On boot the frame records whether the last shutdown was clean and runs a quick
   integrity check of the media cache after an unclean one. It reports this to the owner in the app instead of failing
   silently.
