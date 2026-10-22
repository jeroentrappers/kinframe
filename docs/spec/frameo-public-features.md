# Frameo: Public Feature Inventory and UX Description

Clean-room reference for **kinframe**. Compiled 2026-10-22 from public sources only: frameo.com, the Frameo help centre, the official user manual PDF, release notes, App Store and Google Play listings, the privacy policy, manufacturer manuals and public reviews. Everything is paraphrased. Short quotes are used only for exact numbers. Each item cites its source with a reference link (the full URL list is at the bottom).

Method note: the full help centre (101 English articles, all of them current as of Oct 2026) was read through Zendesk's public Help Center API (`support.frameo.com/api/v2/help_center/en-us/articles.json`). The 2026 official user manual ([manual]) is the most complete source for frame settings. It was cross-checked against an older OEM manual ([manual-old]).

Status labels used below:
- **Free**: available without a subscription.
- **F+**: needs Frameo+ ($1.99/mo or $16.99/yr).
- **Prem**: needs Frameo Premium ($7.99/mo or $69.99/yr).

---

## 0. Product and ecosystem model

- **Software only, not hardware.** Frameo A/S (Aarhus, Denmark) makes the frame software and the phone app. It licenses the frame software to many OEM brands, which own the hardware, warranty and returns. Frameo sells no frames and no spare parts. ([hc-return], [hc-1002], [privacy], [hc-secupdates])
- **Licensed frame app.** The frame app runs on Android (Android 6 to 8 and 9+ are mentioned). The software license key is stored on the device; if it is lost, the frame shows a "Waiting for license key" screen and must be replaced. ([hc-license], [hc-secupdates])
- **Closed ecosystem.** The phone app works only with official Frameo frames. ([appstore], [play])
- **Scale claims:** more than 20M downloads (Nov 2025), 2B photos shared (Dec 2025) and 500k Frameo+ subscribers (May 2026). The Frameo+ page claims 28M downloads and 2.5B photos; the Play listing says "over 25 million users". ([roadmap], [plus], [play])
- **Security update guarantee:** Frameo software security updates are guaranteed until the end of 2028 for frames on Android 6, 7 and 8, and until the end of 2030 for Android 9+. ([hc-secupdates]) One OEM reseller separately promises software support until at least 4 March 2028, citing Australian smart-device rules. ([bf-commit])
- **Certifications and claims:** Intertek "Cyber Assured" certificate (Aug 2022). Store listings claim compliance with GDPR, CCPA and the UK PSTI Act. ([roadmap], [appstore])
- **Frame sizes** referenced in the docs: 7", 10" and 15". Typical resolution is 1280x800. ([hc-media], [shopsavvy])

---

## 1. FRAME (device-side app)

### 1.1 First boot and onboarding
- **Quick-setup wizard order** ([manual]):
  1. Choose the language.
  2. Connect to Wi-Fi.
  3. Check the date and time.
  4. Install an offered software update. The manual recommends doing this before continuing.
  5. Enter **your name** and the **frame location** (for example "Living room" or "Kitchen").
  6. Set the time zone.
- **Restore during setup.** The setup screen offers "Set up as new" or "Restore from backup" (external storage or cloud). ([hc-restore], [hc-cloud])
- **Replayable guide.** An intro guide runs on first boot and can be replayed later from Settings → Help → Guide. ([hc-gift])
- **Gifting:** you can set the frame up, load photos and power it off. Everything persists, and the recipient only needs to join their own Wi-Fi. The frame also works with no Wi-Fi at all. ([hc-gift], [hc-nowifi])
- **Wi-Fi updates on new frames.** Brand-new Android 10+ frames sometimes need an update before they can see WPA2/WPA3 networks. The workaround is to update over a phone hotspot first. ([hc-cantfind])

### 1.2 Wi-Fi and connectivity
- **Wi-Fi settings:** pick a network, enter the password (with a show-password toggle), and watch the connection status, which reads Connected, Unknown or No internet. There is also an ⓘ Wi-Fi details screen and a "Reset Wi-Fi" option that wipes Wi-Fi config and reboots without touching photos. ([hc-wifi], [hc-online], [hc-autherr], [manual])
- **Network requirements:**
  - 2.4 GHz on most hardware. The software supports 5 GHz, but most frames lack the radio.
  - IPv4/DHCPv4 only.
  - No hidden SSIDs.
  - No WPA3-only and no Enterprise networks.
  - 2.4 GHz channels 1 to 11 only.

  ([hc-wifireq], [hc-wifiproblems], [hc-cantfind])
- **Captive portals** are supported, but only on open (unsecured) networks. Tapping the connection status opens a login browser. ([hc-captive])
- **Phone hotspots** are a documented workaround. For iPhone, "Maximize Compatibility" must be on. ([hc-hotspot])
- **Mesh networks:** frames can hop between nodes. After repeated hops a frame may stay disconnected for up to 48 hours. ([hc-wifiproblems])
- **Offline use:** the frame works fully offline. Wi-Fi is needed only to receive photos from the app and to update. An offline banner can be dismissed or turned off. ([hc-nowifi])
- **Sending abroad** works the same as locally, as long as both ends are online. ([hc-intl])

### 1.3 Friends, pairing and roles
- **Friend code:**
  - Generated on the frame with "Add friend" in the quick menu.
  - Valid for **12 hours**, with the exact expiry time shown under the code.
  - It can be entered as digits or scanned as a **QR code**. QR pairing arrived in Nov 2023.
  - Once used, the connection is permanent.
  - A new code can be generated at any time.

  ([hc-friendcode], [hc-connect], [roadmap]) Public docs do not state the code length or format.
- **Sharing the code:** shown suggestions are SMS, email, a phone call or a photo of the screen. ([hc-connect])
- **Code errors:** a bad, expired or offline-frame code produces one generic error. The suggested fixes are a reboot, a new code, or turning off the phone's VPN. ([hc-codefail])
- **No formal owner role.** The help centre says the frame has no official owner and ownership cannot be transferred. The "owner" is simply whoever physically has the frame, and every phone is a "friend". ([hc-owner]) Many other articles still say "owner" for the person who approves requests on the frame.
- **Per-friend permissions** (Settings → My friends → gear icon) ([manual], [hc-sharecode]):
  - **Share code.** Lets the friend generate and share a 12-hour friend code from their own app. This effectively makes them an admin who can invite others.
  - **See frame photos.** Lets the friend browse the frame's library remotely (F+ on the friend's side).
  - **Manage frame photos.** Lets the friend display, hide or delete frame photos remotely (F+ on the friend's side).
- **Deleting a friend** revokes their permission to send. The frame then asks whether to also delete all photos received from that friend. ([manual])
- **Approve-on-frame pattern:** every remote capability requires someone to tap **Allow** on the frame. This covers see/manage photos, cloud backup, calendar linking, Premium-access unlocks and incoming events. ([hc-see], [hc-manage], [hc-cloud], [hc-calstart], [hc-sendevent])
- **New phone:** sign in to a Frameo account, or re-pair with a new code. ([bf-newphone])
- **Friend list visibility:** each friend appears on the frame with their name and profile picture. The app can remove the profile photo (Android 1.33). The frame (v1.24) can hide the profile photo while keeping the caption. ([releases])

### 1.4 Main UI: slideshow and quick menu
- **Gestures:** swipe left or right to change photo, tap once for the quick menu, double-tap to open React, and pinch to zoom (Nov 2023). ([manual], [roadmap])
- **Quick menu contents** ([hc-quickmenu], [manual]):
  - Wi-Fi status icon
  - Album selector
  - Power menu (power off, restart, sleep)
  - My calendar
  - My photos (gallery)
  - Add friend
  - Settings
  - Per-photo actions: Add to album, Adjust, React, Hide, Delete
- **Adjust photo:** switch between Fill frame and Fit to frame, reposition the crop, **rotate**, and edit the caption. Caption editing on the frame works only for imported media; app-sent captions can only be changed by the sender. ([hc-quickmenu], [hc-editcaption])
- **Video controls:** play/pause and a volume slider with mute. ([manual])
- **New-photo notification:** a "You have received new photos" notice appears top-left. Tapping it opens a mini-slideshow of the new items where you can react, adjust or add to an album. A "Manage new photos" step at the end jumps to the gallery with those items preselected. ([hc-newphotos], [releases] v1.26)
- **External storage menu:** inserting an SD card or USB stick automatically offers Play, Import or Backup. ([manual])

### 1.5 Slideshow settings (Settings → Slideshow) ([manual], [manual-old])
| Setting | Behaviour / options | Source |
|---|---|---|
| Timer | Presets plus a custom slider from **1 second to 24 hours**. The default value is not documented publicly. | [hc-timer] |
| Fill frame (default ON) | ON: crop to fill, centred on the "important part". OFF: letterbox. Changing it asks whether to apply to all photos. | [manual], [hc-crop] |
| Frame background | Several styles for the bars shown when a photo does not fill the screen. | [manual] |
| Photo display order | Date received, date taken or shuffle, plus newest-first or oldest-first. Older builds had a separate "Reverse order" toggle. "Date taken" also switches the caption date to the taken date. | [hc-sort], [manual-old] |
| Show caption | Toggle. | [manual] |
| Show clock / Show weather / Show date | Separate widget toggles, top-right. | [manual], [hc-weather] |
| Hybrid view | Calendar banner beside or below the photos. | [hc-hybrid] |
| Show countdowns in slideshow | Prem. | [hc-countdown] |
| Collage mode + Maximum photos in collage | Automatic grouping of **2 to 6** photos per screen. Tapping a photo in a collage gives hide and delete actions. | [manual], [hc-collage] |
| Autoplay (videos) | Toggle. Manual play always works. | [manual] |
| Video playback | Loop until the timer ends, play once and hold the thumbnail, or play once then continue. | [manual] |
| Auto mute | Mutes video sound after a period without interaction. | [manual] |
| Video volume | Slider, separate from notification volume. | [hc-volume] |

- **Transitions:** no transition-effect setting appears in any manual or help article.
- **Album playback:** the slideshow can play "All photos" or a single album. ([hc-albums])
- **Retail mode** (Nov 2019): a store-demo mode that plays a promo video and locks some settings. ([roadmap])

### 1.6 Gallery ("My photos") and albums
- **Detail view actions:** Display now, Add to album, Adjust, Hide/Show, Delete. The detail view also shows:
  - storage location (local or external)
  - transfer source
  - date taken
  - date received
  - file size

  ([hc-myphotos], [manual])
- **Multi-select:** use the button, or long-press and drag. Bulk actions are add to album, hide/show and delete, plus select-all. ([hc-myphotos], [hc-delete])
- **Sort the gallery** by date received, date taken or file size, ascending or descending. Gallery sorting does not change slideshow order. ([hc-myphotos])
- **Filters:**
  - shown or hidden
  - media type (photo, video, greeting)
  - source (sent from app, transferred from PC, imported from external storage)
  - per friend

  ([hc-myphotos])
- **Hide vs delete.** Hidden items leave the slideshow and the "All photos" album, and can be found again with the Hidden filter. Delete is permanent, with no trash bin documented. ([hc-hide], [hc-delete])
- **Albums** (May 2025):
  - create, rename and delete albums
  - "Play in slideshow"
  - deleting an album keeps its photos
  - photos can be added from the slideshow, from the gallery, or while reviewing new photos
  - albums exist **only on the frame**; one reviewer wants app-side albums to carry over ([hc-albums], [manual], [asreviews])
- **Storage overview:** Manage photos shows the photo count, space used and space free. A "Storage space" notification warns when storage runs low. No auto-delete-oldest behaviour is documented. ([manual])
- **Unsupported files:** a "Show unsupported files" list lets you remove files the frame cannot display. ([hc-usb])

### 1.7 Getting media onto the frame without the app
- **Import from SD or USB:**
  - select files (with select-all and sort before import) and import them to internal storage
  - most frames need a **microSD of 32 GB or less, formatted FAT32**
  - photos: JPG, JPEG, PNG, WEBP, plus HEIC on Android 10+ frames

  ([hc-import], [hc-media], [hc-format])
- **Play from external storage:** shows the card's media alongside internal media without copying it. ([hc-extplay])
- **USB cable from a PC** (Aug 2023):
  - enable it in Settings → Manage photos → Transfer from computer
  - the frame appears as a drive; copy files into the `DCIM` folder
  - **Windows and Linux only, not macOS**

  ([hc-usb])
- **USB flash drives:** only on some OEM models. Most frames have micro-USB; some have mini-USB or USB-A. ([hc-usbport])
- **Export:** copies all photos and profile pictures to an `export` folder on the card. ([hc-export], [manual])
- **No desktop or web app.** Mac users are told to go through the phone or an SD card. ([hc-pc])
- **Video import limits:**
  - MP4 or 3GP
  - frames below 1080p: up to 720p at 10 Mbps
  - 1080p and above: up to 1080p at 100 Mbps
  - recommended length is 4 s to 4:59 min, at 1 to 125 MB, 30 fps, 2 to 5 Mbps (2 to 10 Mbps on 15" frames) and 96 kbps audio

  ([hc-videoprep], [hc-media])

### 1.8 Display, power and sensors
- **Brightness:** manual slider. Frames with a light sensor also get **adaptive brightness** (frame v1.27). ([manual], [releases])
- **Sleep mode:**
  - the screen turns off but the frame still receives photos
  - default is **off at 23:00, on at 07:00, every day**
  - **multiple schedules**, each with its own days of the week (Jan 2023)
  - manual sleep from the power menu or the power button; on some frames a double-tap wakes them

  ([manual], [hc-sleep], [roadmap])
- **Deep sleep** for EU ErP energy rules arrived in frame v1.32 (Aug 2026). ([releases])
- **Motion sensor** (Nov 2020, only on supported hardware): choose how long the frame waits without motion before the screen turns off. ([manual], [roadmap])
- **Ambient "Lights"** on some OEM models (v1.27): adaptive (follows the photo's colours), fixed colour or off, with brightness and colour settings. ([manual], [releases])
- **Battery indicator** in the menu for battery-powered models (v1.22). ([releases])
- **Text and display scaling** for low vision (v1.29). ([releases])
- **Orientation:** hybrid view adapts to portrait or landscape. Rotation hardware (gravity sensor) depends on the OEM. ([hc-hybrid], [shopsavvy])
- **Safe shutdown:** always power off from the menu or the button. Pulling the plug can corrupt the file system and cause the screen and touch faults listed in section 6. ([manual], [hc-frozen])

### 1.9 Clock, date, weather
- **Widgets:** clock, date and weather sit top-right, each with its own toggle. ([hc-weather], [manual])
- **Weather menu:** tap the weather widget for 1-day (by time of day) and 4-day forecasts. The location is detected automatically and can be overridden. ([hc-weather], [manual])
- **Units and formats:** °C or °F, 24-hour time, first day of the week, plus manual date, time and time zone (auto when online). ([manual])
- Tapping the date or clock opens the full-screen calendar. ([hc-fullcal])

### 1.10 Calendar (frame v1.30, Apr 2026; Premium in v1.31)
- **Display-only calendar.** The frame shows a linked calendar; events are edited in the source app.
  - Sync runs **every 15 minutes**, plus "Sync now".
  - Full-screen views are **4-day, week and month**.
  - **Hybrid view** puts a banner beside the slideshow.
  - Tapping an event shows its details.

  ([hc-whatcal], [hc-fullcal], [hc-hybrid])
- **Calendar settings:**
  - first day of the week
  - start on today
  - fade past events
  - week numbers
  - per-calendar display name and colour (Premium: 9 pastel palettes)

  ([hc-calsettings], [subs])
- **Linking a calendar:**
  - from a QR code on the frame (Settings → Calendar → Add Calendar), or from the app (Connected frames → frame → Calendar)
  - then the frame must accept
  - providers: Google (OAuth), Outlook (OAuth), iCloud (public link) and any iCal/ICS URL
  - Yahoo is currently broken on Yahoo's side
  - for work or school accounts, use the secret ICS link

  ([hc-calstart], [hc-calprovider], [hc-workcal])
- **Limits:** free users get **1 synced calendar per frame**. Premium is unlimited, on up to 5 frames. ([hc-calstart], [subs])
- **Sync status icons:** syncing, ok, warning, failed, inactive. ([hc-calfail])
- **Calendar timed view** (Prem): scheduled windows show the full-screen calendar. New schedules default to 07:00 to 09:00 and support multiple schedules with per-day selection. ([hc-timed])
- **Countdowns** (Prem): set per event. They show in a bar on the full-screen calendar and optionally as slideshow widgets. ([hc-countdown])
- **Send Event** (Prem on the sender's side):
  - the receiving frame needs no Premium
  - the frame accepts or declines, and the sender sees Accepted, Declined or Waiting in History
  - accepted events go into a local "Send events" calendar marked with a heart icon
  - accepted events cannot be edited or cancelled remotely; resending creates a new event

  ([hc-sendevent])
- **Premium access for frames:**
  - a Premium subscriber can grant "Premium access" to up to 5 frames; this unlocks timed view and countdowns on those frames
  - frames can ask a connected Premium friend for access
  - unlimited sync and Send Event stay tied to the subscriber's app

  ([hc-premium])
- **Privacy claim:** Frameo says it does not access or store calendar details. ([cal])

### 1.11 Reactions and greetings
- **Reactions** (Nov 2021): Like, Laugh, Surprised, Sad. You react from the React button or with a double-tap. The reaction appears in the photo caption on the frame, and the sender gets a push notification ("liked your photo"). ([roadmap], [hc-quickmenu], [play])
- **Greetings** are themed graphic overlays chosen by the sender. On the frame they are a filterable media type. ([hc-greetings], [hc-myphotos])

### 1.12 Notifications on the frame
- **Master switch and volume:** there is a master "Show notifications" toggle and a notification volume. ([manual], [hc-notif])
- **Per-type toggles:**
  - Storage space low
  - Backup failed
  - Network connection
  - Calendar sync
  - New photos
  - New sent event
  - New friends
  - Software update installed
  - Feature news
  - Premium status
  - Date/time incorrect

  ([manual])

### 1.13 Backup, restore, reset
- **External backup:**
  - backs up photos, friends/connections and settings to SD
  - shows the time of the last backup
  - overwrites any earlier backup
  - **automatic backup within 30 hours** of new photos or changes

  ([hc-backup], [manual])
- **Restore** happens only on a new or reset frame, from the setup screen. To keep both an old and a new frame running after a restore, update both first. ([hc-restore], [manual])
- **Cloud backup** (F+): see section 3.
- **Reset frame** (Settings → Backup and restore) wipes photos, friends and settings. A pinhole reset (hold 5 s) exists on many models. ([manual], [hc-frozen])

### 1.14 Help, about, updates, diagnostics
- **Help → Support** shows a support QR code with device details, plus the Android version and screen resolution. ([manual], [hc-videoprep], [hc-secupdates])
- **About:**
  - **Peer ID** (unique frame ID)
  - Frameo version
  - **Check for update** (OTA)
  - **Beta program** toggle (frame reboots into the beta channel)
  - third-party licences
  - **Share anonymous analytics** toggle (opt-out)

  ([manual], [hc-beta])
- **ADB access:** available once Beta is on; Frameo suggests scrcpy for mirroring. ([hc-adb])
- **Crash recovery:** tapping OK ten times on the "Frameo has stopped" dialog opens a recovery screen showing the Peer ID. Error 1002 means repeated crashes; that screen offers backup to SD and reset. ([hc-stopped], [hc-1002])
- **Languages:** the frame language is chosen at setup and can be changed in Settings. The app has 36 locales; the frame added Romanian, Arabic, Hindi, Indonesian and Thai in 2025, with RTL support. ([hc-lang], [appstore], [releases])

---

## 2. PHONE APP (iOS / Android, `net.frameo.app`)

### 2.1 Accounts
- **Account optional.** The app works without an account: pairing is tied to the device, and a new phone needs a new code. An account keeps frame connections across devices. ([hc-account], [bf-newphone])
- **Sign-in options:**
  - Apple, Facebook, Google or email on iOS
  - Facebook, Google or email on Android

  ([hc-account])
- **Account management:**
  - edit profile
  - change or reset password by email link
  - delete the account in the app (My account → Account management)
  - reclaim a subscription after deleting the account (iOS 1.36)

  ([hc-editaccount], [hc-password], [hc-delaccount], [releases])
- **Navigation (2026):** a bottom tab bar with Frames, Calendar, History and Menu. ([hc-premium], [hc-sendevent], [releases])

### 2.2 Sending photos
- **Send flow:** pick photo(s), pick recipient frame(s), add a caption (one shared caption or one per photo), then adjust. ([hc-send])
  - Adjust offers Fill or Fit, dragging to position the important part, and a preview in both **landscape and portrait**.
  - Recent versions merge the caption and adjust steps, and let you add or remove items during sending.

  ([hc-send], [releases])
- **Smart Photo Fit** (Dec 2022): face detection pre-centres the crop. The whole photo is always sent, so the frame can switch to Fit later. ([roadmap], [bf-important])
- **Multiple recipients:** a single send can target several frames. ([hc-send], [site])
- **Batch limits:** **10 photos** per send on the free tier and **100** with F+. Multi-select works by long-press or a button. ([hc-multi], [hc-morephotos])
- **Video:**
  - **15 s** per video free and **2 min** with F+; longer clips are trimmed in the app
  - **one video per send** through the share sheet
  - videos up to 4:59 are possible via SD or USB import

  ([hc-longvid], [hc-share3p], [hc-video], [releases] (trim fix))
- **Supported phone formats:**
  - Android: JPEG, PNG, WEBP, HEIC, MP4, 3GP, MOV, HEVC
  - iOS: JPEG, PNG, HEIC, MP4, M4V, 3GP, MOV

  ([hc-media])
- **Share sheet:** the app accepts shares from Google Photos, iCloud, Amazon Photos and others (up to 10 photos or 1 video). Album sync with third-party services is **not** supported. ([hc-share3p], [hc-appalbums])
- **Gallery:** the in-app gallery browses phone albums and folders, including SD card folders. iOS limited-library access is supported. ([hc-appalbums], [hc-perm])
- **Sent marker** (2025): a paper-plane icon on the thumbnail shows an item was already sent, and to which frames, to help avoid duplicates. ([hc-dupes], [releases])
- **Greetings:** graphic themes overlaid on a photo, picked from a horizontal carousel. Seasonal and local sets ship with app updates. A greeting that encloses the whole photo cannot use Fill or Fit. ([hc-greetings], [releases])
- **Delivery queue:**
  - an "Ongoing deliveries" queue shows a % sent and lets you cancel
  - a stalled transfer times out after about **2 min** on the queue screen
  - **since mid-2026 (app 1.40, frame 1.32)**, items sent to an offline frame are delivered when it comes back online; before that the app refused with "all recipients are offline"

  ([hc-stuck], [hc-offline], [releases])
- **History:**
  - lists sent items
  - lets you **edit a caption after delivery** (Apr 2022), but only from the phone that sent it
  - lets you forward items
  - tracks Send Event responses

  ([hc-editcaption], [roadmap], [hc-sendevent])
- **Deleting a sent photo from the frame** is not possible on the free tier (reviews confirm this). With F+ and the frame's "Manage" permission, a friend can delete or hide anything on the frame. ([hc-delete], [asreviews])
- **Feedback to the sender:** the sender gets push notifications when the recipient reacts. No public doc describes "delivered" or "seen" read receipts beyond delivery progress. ([play], [hc-quickmenu])

### 2.3 Managing frames from the app
- **Connected frames list:** each frame shows its name and location as set on the frame. From here you can:
  - share the friend code (if allowed)
  - request see or manage permission
  - see backup status icons
  - link a calendar
  - grant Premium access

  ([manual], [hc-sharecode], [hc-cloud], [hc-premium])
- **Remote frame settings:** no public doc shows remote control of slideshow, sleep or Wi-Fi from the app. Remote actions are limited to photos (F+), backups (F+) and calendar/Premium. One reseller blog notes "remote settings control is limited". ([manual], [timebox])
- **App beta:** the Play beta is opened from app Settings; iOS uses a TestFlight link. ([hc-appbeta])
- **Accessibility:** VoiceOver, Voice Control, Dynamic Type and Android accessibility improvements were added in 2025 and 2026. ([releases])

---

## 3. FRAMEO+ AND PREMIUM (subscriptions)

| | Free | Frameo+ | Premium |
|---|---|---|---|
| Price (US) | $0 | $1.99/mo or $16.99/yr | $7.99/mo or $69.99/yr |
| Trial | – | 14 days | 14 days |
| Photos per send | 10 | 100 | 100 |
| Video length | 15 s | 2 min | 2 min |
| Connected frames | unlimited | unlimited | unlimited |
| Captions, reactions, greetings, albums, collage, widgets | ✓ | ✓ | ✓ |
| See or manage frame photos remotely | – | ✓ | ✓ |
| Cloud backup | – | up to 5 frames | up to 5 frames |
| Google Cast | – | ✓ | ✓ |
| Synced calendars | 1/frame | 1/frame | unlimited (5 frames) |
| Send Event, countdown, timed view | – | – | ✓ |

Sources: [subs], [hc-sub], [plus], [appstore], [play]

- **Subscription is per account, not per frame.** Frameo+ applies to the subscriber's own app account. Friends without it keep free-tier limits, and the frame must still grant permission. ([hc-plus])
- **Billing:**
  - billing goes through Apple or Google
  - upgrades apply immediately; downgrades apply at the end of the period
  - when a subscription ends, photos and connections stay, but **cloud backups are deleted**

  ([hc-sub])
- **See frame photos** (May 2024): browse, share or download the frame's photos once the frame allows it. ([hc-see])
- **Manage frame photos** (Nov/Dec 2024):
  - delete, hide or unhide, and **"display on frame now"** for photos and videos
  - multi-select
  - permission is granted per friend on the frame

  ([hc-manage])
- **Google Cast** (Jan 2025): casts photos from the frame's library to a Chromecast TV. The phone and TV must be on the same Wi-Fi (on iOS this also needs Local Network permission). ([hc-cast], [hc-castissues])
- **Cloud backup** (May 2024):
  - can be started from the app (frame accepts) or from the frame (pick a friend with F+, who accepts in the app)
  - automatic background backup (can be turned off to save data) plus "Backup now"
  - a **recovery key / encryption password** is shown, to use if both phone and frame are lost
  - status icons cover ok, not backed up, managed by another user, and waiting

  ([hc-cloud], [manual])
  - **Restore:** on a new or reset frame, choose Restore → Cloud backup. Then, in the app, go to My account → Manage backups → pick a backup → "Restore another frame with backup" and enter the pairing code or scan the QR shown on the frame. ([hc-cloud])
  - **Contents:** backups include media, settings, metadata, captions and reactions; calendars were added in 2026. ([hc-privacy], [releases])
  - **Pain point:** restoring needs confirmation on the frame's touchscreen, which blocks recovery when the touchscreen is broken. ([asreviews])
- **Premium** (Jun/Jul 2026): adds the calendar features in section 1.10 and premium calendar data backup. ([hc-premium], [releases])

---

## 4. CLOUD, PRIVACY AND SECURITY MODEL (as publicly described)

- **End-to-end encryption:** photos, videos, captions and reactions travel phone to frame end-to-end encrypted with **"double ratchet"** encryption (Signal-style). Frameo says neither it nor its storage provider can decrypt content. ([privacy], [hc-privacy])
- **Relay, not peer-to-peer:** content may sit temporarily, encrypted, with a cloud storage provider while in transit. Store-and-forward is now explicit because offline frames receive queued items later. The frame sends only its IP address and a unique ID (the "Peer ID") to connect with authorised apps. Retention of in-transit blobs is not stated. ([privacy], [releases], [manual])
- **Storage on the frame:** media stays in the frame's internal storage until deleted or factory-reset. The frame UI has **no PIN or password** (Frameo describes it as a home device that anyone in the home can see). ([privacy], [hc-privacy])
- **Cloud backup encryption:** backups are encrypted on the client. The keys are generated on and kept by the frame, and a recovery password covers total loss. ([hc-privacy])
- **Account data:** name and email (or social login), subscription status and logs (IP address, device IDs, connected frames). Analytics IPs are deleted after **13 months**. Account data is deleted **no later than 2 days** after account deletion. Support data is kept up to 2 years, and support attachments are deleted the next day. ([privacy])
- **Processors:** mostly US-based (cloud, analytics, support, email, paywall testing). Transfers rely on the EU-U.S. DPF or SCCs. Frameo says it does not sell data. ([privacy])
- **Data exports (EU Data Act):** server-side data is available within 30 days in machine-readable form. Local frame data is accessible via ADB or a backup. ([privacy])
- **Store disclosures:**
  - **Play:** no data shared; collects crash, diagnostics and app interactions (optional), plus name, email and user IDs; encrypted in transit; deletion on request ([play-ds])
  - **iOS:** email, name and product interaction are linked to the user, including for marketing ([appstore])
- **Vulnerability disclosure:** security@frameo.com; receipt is acknowledged within 3 business days, with updates every 2 to 3 weeks. ([hc-vuln])

---

## 5. LIMITS AND DEFAULTS (quick reference)

| Item | Value | Source |
|---|---|---|
| Friend/pairing code validity | 12 hours (expiry time shown) | [hc-friendcode] |
| Code from an admin friend's app | also 12 h | [hc-sharecode] |
| Photos per send | 10 free / 100 F+ | [hc-multi], [hc-morephotos] |
| Via share sheet | up to 10 photos or 1 video | [hc-share3p] |
| Video length via app | 15 s free / 2 min F+ | [hc-longvid] |
| Video via SD/USB | recommended 4 s to 4:59, 1 to 125 MB | [hc-media], [hc-video] |
| Import video caps | <1080p frames: 720p at 10 Mbps; ≥1080p: 1080p at 100 Mbps; MP4/3GP | [hc-videoprep] |
| Frame photo formats | JPG, JPEG, PNG, WEBP (+HEIC on Android 10+) | [hc-media] |
| microSD | ≤32 GB, FAT32 | [hc-format] |
| Slideshow timer range | 1 s to 24 h (default not published) | [hc-timer] |
| Collage | 2 to 6 photos | [manual] |
| Sleep default | 23:00 off, 07:00 on, daily | [manual] |
| Timed calendar default | 07:00 to 09:00 | [hc-timed] |
| Calendar sync interval | every 15 min | [hc-whatcal] |
| Free calendars | 1 per frame | [hc-calstart] |
| Premium frames | up to 5 | [hc-premium] |
| Cloud backup frames | up to 5 | [hc-plus] |
| Auto SD backup | within 30 h of change | [hc-backup] |
| Delivery-queue timeout | about 2 min | [hc-stuck] |
| Mesh-hop lockout | up to 48 h disconnected | [hc-wifiproblems] |
| Wi-Fi | 2.4 GHz (mostly), ch 1 to 11, IPv4, no hidden SSID, no WPA3-only/Enterprise | [hc-wifireq] |
| Pinhole reset | hold 5 s | [hc-frozen] |
| Trial | 14 days | [subs] |
| Security updates | to end-2028 (Android 6 to 8) / end-2030 (Android 9+) | [hc-secupdates] |
| App min OS | iOS 16.4 | [appstore] |

**Not found in public sources:**
- default slideshow interval and preset list
- friend-code length and format
- per-frame photo cap
- behaviour when storage is full (only a low-storage notification is documented)
- whether the phone app downscales photos before sending
- in-transit retention period on the relay
- transition effects (none documented)

---

## 6. PAIN POINTS AND OPPORTUNITIES ("and more" for kinframe)

**Monetisation and remote control**
1. **Paywall on basic remote control.** A frequent 1 to 2 star theme is that seeing what is on the frame, or deleting or hiding photos from the phone, needs a subscription after buying the hardware. Constant "Upgrade now" prompts are also disliked, and in one case a non-subscriber could not dismiss the paywall. ([asreviews], [trustpilot], [appstore])
   - *Opportunity:* free remote library view and management for authorised family members, with no upsell.
2. **10-photo, 15-second caps** on the free tier. Bulk sends need F+ or an SD card. ([hc-multi], [asreviews])
3. **No remote settings.** The app cannot change Wi-Fi, sleep, timer and similar settings, so distant relatives must have someone at the frame. Every remote feature also needs a tap on the frame, which fails if the touchscreen is broken or nobody is there. ([manual], [timebox], [asreviews])

**Ingesting photos**

4. **No desktop or web app and no email-to-frame.** USB transfer works only on Windows and Linux, not Mac. ([hc-pc], [hc-usb])
5. **No live album sync** with Google Photos, iCloud shared albums, Immich and similar. The help centre says third-party sync is not possible. ([hc-appalbums], [onesdr])
6. **App-side albums don't carry over** to the frame. ([asreviews])

**Display and slideshow**

7. **Date shown vs date taken.** Users want shuffle together with a "date taken" caption. The docs say date-taken order switches captions to the taken date, but shuffle shows the received date. ([asreviews], [hc-sort], [trustpilot])
8. **Portrait photos on landscape frames** look narrow or awkwardly cropped. Shuffle repeats the same photos, and there are no transition options. ([trustpilot], [shopsavvy])
9. **Calendar overlay intrusive** for photo-only users, and new notification types can't always be silenced individually. ([trustpilot], [asreviews])

**Reliability and connectivity**

10. **Sync failures.** Common reports are "failed to send", photos stuck at 0%, frames "always offline", and constraints such as 2.4 GHz only, no WPA3, no IPv6 and no hidden SSID. Reboots are the usual fix. ([asreviews], [hc-stuck], [hc-wifireq], [shopsavvy])
11. **Fragility.** Unplugging while running can corrupt the file system, causing a lost license key, flicker or dead touch. A dead frame loses every photo unless a backup exists, and free users only have the ≤32 GB FAT32 SD backup. ([hc-license], [hc-flicker], [asreviews])
    - *Opportunity:* power-safe storage, free self-hostable backup, and sync to any S3/WebDAV/NAS.

**Support and lifecycle**

12. **Support split between Frameo and OEMs.** Frameo sends hardware issues to the seller, which leaves gift recipients stuck. ([hc-return], [trustpilot])
13. **Storage opacity.** Reports include an upload ceiling at about 1,700 items despite free space, a growing "system" storage, and heavy phone-app cache. ([trustpilot], [shopsavvy], [appstore])
14. **Account and login friction**, such as password-reset loops and "you are offline" errors during sign-up. ([asreviews])
15. **Closed ecosystem.** The app works only with licensed Frameo frames, and frame support depends on the vendor's license key. ([appstore], [hc-license])
    - *Opportunity:* run on any Android tablet, Raspberry Pi or old device.

**Concepts worth keeping**

16. Code or QR pairing with no account needed.
17. Per-friend permissions.
18. Approve-on-device consent.
19. E2EE with double ratchet.
20. Offline-first frame.
21. Fill/Fit with an "important part" crop and face centring.
22. Reactions back to the sender.
23. Hide vs delete.
24. Albums and collage.
25. Multiple sleep schedules.
26. Motion sensor and adaptive brightness hooks.
27. SD import, export and backup.
28. Calendar hybrid view.

---

## Sources

[site]: https://frameo.com
[subs]: https://frameo.com/subscriptions
[plus]: https://frameo.com/plus
[cal]: https://frameo.com/calendar
[roadmap]: https://frameo.com/roadmap
[releases]: https://frameo.com/releases
[privacy]: https://privacy.frameo.com/
[manual]: https://partner.frameo.net/materials/UserManual.pdf
[manual-old]: https://cdn.shopify.com/s/files/1/0608/6980/4121/files/DPF1170_manual_REV.pdf
[appstore]: https://apps.apple.com/us/app/frameo/id1179744119
[play]: https://play.google.com/store/apps/details?id=net.frameo.app
[play-ds]: https://play.google.com/store/apps/datasafety?id=net.frameo.app
[asreviews]: https://itunes.apple.com/us/rss/customerreviews/id=1179744119/sortBy=mostRecent/json
[trustpilot]: https://www.trustpilot.com/review/frameo.net
[timebox]: https://time-box.store/blogs/news/frameo-vs-aura-reddit-parents-far-away
[shopsavvy]: https://shopsavvy.com/reviews-tldr/frameo-10.1-inch-digital-photo-frame.md
[onesdr]: https://www.onesdr.com/can-frameo-connect-to-google-photos
[bf-important]: https://help.buyframeo.com/hc/en-nz/articles/37886186237977-What-Is-The-Important-Part
[bf-newphone]: https://help.buyframeo.com/hc/en-nz/articles/37912948204057-Connect-a-New-Phone
[bf-commit]: https://help.buyframeo.com/hc/en-nz/articles/61615379673625-Our-Commitment-to-Cyber-Security-Product-Support
[hc-friendcode]: https://support.frameo.com/hc/en-us/articles/4410104016146
[hc-connect]: https://support.frameo.com/hc/en-us/articles/360021691160
[hc-sharecode]: https://support.frameo.com/hc/en-us/articles/4409540396562
[hc-codefail]: https://support.frameo.com/hc/en-us/articles/4402778432146
[hc-owner]: https://support.frameo.com/hc/en-us/articles/8794577509906
[hc-gift]: https://support.frameo.com/hc/en-us/articles/8859409711506
[hc-nowifi]: https://support.frameo.com/hc/en-us/articles/8862934490130
[hc-wifi]: https://support.frameo.com/hc/en-us/articles/4409774293010
[hc-wifireq]: https://support.frameo.com/hc/en-us/articles/360020756500
[hc-wifiproblems]: https://support.frameo.com/hc/en-us/articles/10140527026834
[hc-cantfind]: https://support.frameo.com/hc/en-us/articles/24804330613266
[hc-captive]: https://support.frameo.com/hc/en-us/articles/360020759920
[hc-hotspot]: https://support.frameo.com/hc/en-us/articles/360020790679
[hc-online]: https://support.frameo.com/hc/en-us/articles/360021692220
[hc-autherr]: https://support.frameo.com/hc/en-us/articles/360021721079
[hc-intl]: https://support.frameo.com/hc/en-us/articles/8675064605458
[hc-timer]: https://support.frameo.com/hc/en-us/articles/5740983556370
[hc-extplay]: https://support.frameo.com/hc/en-us/articles/6179369638674
[hc-videoprep]: https://support.frameo.com/hc/en-us/articles/4409052718866
[hc-collage]: https://support.frameo.com/hc/en-us/articles/29706580923666
[hc-hide]: https://support.frameo.com/hc/en-us/articles/5717041571474
[hc-pc]: https://support.frameo.com/hc/en-us/articles/360021233819
[hc-usb]: https://support.frameo.com/hc/en-us/articles/12109654353170
[hc-usbport]: https://support.frameo.com/hc/en-us/articles/4589534910482
[hc-export]: https://support.frameo.com/hc/en-us/articles/360021231619
[hc-import]: https://support.frameo.com/hc/en-us/articles/360021211120
[hc-format]: https://support.frameo.com/hc/en-us/articles/360021212540
[hc-backup]: https://support.frameo.com/hc/en-us/articles/360021672160
[hc-restore]: https://support.frameo.com/hc/en-us/articles/7152170578450
[hc-newphotos]: https://support.frameo.com/hc/en-us/articles/26493239580178
[hc-volume]: https://support.frameo.com/hc/en-us/articles/4410600303506
[hc-notif]: https://support.frameo.com/hc/en-us/articles/7390528243602
[hc-lang]: https://support.frameo.com/hc/en-us/articles/4410606575122
[hc-sort]: https://support.frameo.com/hc/en-us/articles/8314082050066
[hc-crop]: https://support.frameo.com/hc/en-us/articles/8714987838098
[hc-albums]: https://support.frameo.com/hc/en-us/articles/26077810899346
[hc-myphotos]: https://support.frameo.com/hc/en-us/articles/26081605127826
[hc-media]: https://support.frameo.com/hc/en-us/articles/12110057465106
[hc-delete]: https://support.frameo.com/hc/en-us/articles/4410212392466
[hc-adb]: https://support.frameo.com/hc/en-us/articles/6126183308434
[hc-beta]: https://support.frameo.com/hc/en-us/articles/360021700959
[hc-quickmenu]: https://support.frameo.com/hc/en-us/articles/26517961948050
[hc-sleep]: https://support.frameo.com/hc/en-us/articles/4410607033618
[hc-weather]: https://support.frameo.com/hc/en-us/articles/11751799525138
[hc-dupes]: https://support.frameo.com/hc/en-us/articles/28688065768466
[hc-account]: https://support.frameo.com/hc/en-us/articles/13592668228754
[hc-editaccount]: https://support.frameo.com/hc/en-us/articles/14601165259922
[hc-delaccount]: https://support.frameo.com/hc/en-us/articles/14601041314962
[hc-password]: https://support.frameo.com/hc/en-us/articles/14601515761554
[hc-editcaption]: https://support.frameo.com/hc/en-us/articles/5005029264530
[hc-video]: https://support.frameo.com/hc/en-us/articles/4412305468306
[hc-greetings]: https://support.frameo.com/hc/en-us/articles/21278050902162
[hc-appalbums]: https://support.frameo.com/hc/en-us/articles/360021704019
[hc-share3p]: https://support.frameo.com/hc/en-us/articles/5739131014034
[hc-sub]: https://support.frameo.com/hc/en-us/articles/36477737530770
[hc-multi]: https://support.frameo.com/hc/en-us/articles/360021705699
[hc-send]: https://support.frameo.com/hc/en-us/articles/360021704639
[hc-perm]: https://support.frameo.com/hc/en-us/articles/360021676620
[hc-appbeta]: https://support.frameo.com/hc/en-us/articles/360021703939
[hc-stuck]: https://support.frameo.com/hc/en-us/articles/4402779275154
[hc-offline]: https://support.frameo.com/hc/en-us/articles/360021721759
[hc-castissues]: https://support.frameo.com/hc/en-us/articles/24280538666002
[hc-cloud]: https://support.frameo.com/hc/en-us/articles/18074691801234
[hc-plus]: https://support.frameo.com/hc/en-us/articles/24563987505554
[hc-cast]: https://support.frameo.com/hc/en-us/articles/24273879116050
[hc-manage]: https://support.frameo.com/hc/en-us/articles/22349062052754
[hc-see]: https://support.frameo.com/hc/en-us/articles/18074726617362
[hc-longvid]: https://support.frameo.com/hc/en-us/articles/18074670775954
[hc-morephotos]: https://support.frameo.com/hc/en-us/articles/18074376071698
[hc-workcal]: https://support.frameo.com/hc/en-us/articles/34308366038034
[hc-timed]: https://support.frameo.com/hc/en-us/articles/35876174307474
[hc-countdown]: https://support.frameo.com/hc/en-us/articles/35872082236562
[hc-premium]: https://support.frameo.com/hc/en-us/articles/35883362604818
[hc-sendevent]: https://support.frameo.com/hc/en-us/articles/36059664284306
[hc-calsettings]: https://support.frameo.com/hc/en-us/articles/33589170471826
[hc-fullcal]: https://support.frameo.com/hc/en-us/articles/33565703555858
[hc-hybrid]: https://support.frameo.com/hc/en-us/articles/33562048504978
[hc-whatcal]: https://support.frameo.com/hc/en-us/articles/33709576344082
[hc-calprovider]: https://support.frameo.com/hc/en-us/articles/34355320172946
[hc-calstart]: https://support.frameo.com/hc/en-us/articles/33709620478226
[hc-calfail]: https://support.frameo.com/hc/en-us/articles/33593640976786
[hc-secupdates]: https://support.frameo.com/hc/en-us/articles/23433637149842
[hc-privacy]: https://support.frameo.com/hc/en-us/articles/17917005969426
[hc-vuln]: https://support.frameo.com/hc/en-us/articles/20639732145810
[hc-flicker]: https://support.frameo.com/hc/en-us/articles/4402763897874
[hc-stopped]: https://support.frameo.com/hc/en-us/articles/360020792479
[hc-1002]: https://support.frameo.com/hc/en-us/articles/4402816339858
[hc-frozen]: https://support.frameo.com/hc/en-us/articles/8602712406930
[hc-license]: https://support.frameo.com/hc/en-us/articles/8796456840722
[hc-return]: https://support.frameo.com/hc/en-us/articles/10215941267474

Reference keys, in the order their URLs are listed above:
- **Official Frameo:** site, subs, plus, cal, roadmap, releases, privacy, manual
- **OEM manual:** manual-old
- **Stores:** appstore, play, play-ds, asreviews
- **Third-party reviews and blogs:** trustpilot, timebox, shopsavvy, onesdr
- **OEM reseller help centre:** bf-*
- **Official Frameo help centre:** hc-*

Help-centre IDs resolve without slugs. Reddit could not be reached from this environment (blocked), so community pain points come from App Store reviews, Trustpilot and reseller or blog summaries.
