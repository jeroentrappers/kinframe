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

## TV modes: show, mirror, or be a frame

**Problem:** "Show on TV" played a frame's library at the TV's own pace. That is nice for a slideshow evening, but people
also asked for two other things: "let grandma's living-room TV show what her frame shows right now", and "we have a TV
(or an old laptop) and no frame: can that be one?".

**kinframe behaviour:** the TV dialog offers three choices.

1. **Show photos** (as before): a TV link plays this library, an album or a selection at its own pace.
2. **Mirror this frame.** The TV shows exactly what the physical frame shows, live: the same photo, the same moment of
   the cross-fade, the same Ken Burns motion (it is computed from the photo and its focal point, so frame and TV agree
   without streaming anything), and a video at the same position. Swipes, remote "next", "show this photo" and the
   new-photo moment all reach the TV within a fraction of a second. The frame only says what it shows; the server
   remembers that in memory and sends the TV signed links for those photos. When the frame sleeps or goes offline,
   the TV shows a calm notice and picks up again on its own. Next/previous in the app move the frame, and the TV
   follows. Because a mirror shows everything the frame shows, only members who may see the whole library can start
   one; it uses a revocable TV link like the other TV ways and stops at once when the link is turned off.
3. **Use this screen as a frame.** A TV browser, a Chromecast/Google TV browser or a laptop becomes a kinframe of its
   own (a "browser" frame). It enrolls with the same device protocol as the Android frame, shows a code, and is claimed
   like a frame on the wall, or in one click from the dialog when it is the browser the owner is using. It gets its own
   library, people and settings (interval, transitions, Ken Burns, captions, clock, date, weather, fill or whole photo,
   sleep schedule), shows new photos with a small celebration, and sends reactions from the remote (OK, then arrows).
   The family sees it with a "Browser" badge and removes it like any frame; removing it sends the screen back to its
   code. It never installs updates (it is the web app) and has no Wi-Fi or storage health.

Casting from Chrome uses the Presentation API (the TV page itself) or, for a mirror, the Default Media Receiver fed with
each photo the frame shows. A browser frame on a Chromecast needs a TV browser (Google TV) or a presenting laptop, as
the Default Media Receiver cannot run a page.

