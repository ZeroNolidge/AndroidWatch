# AndroidWatch

Apps for the **M99** and **M99 Mini** 4G Android smartwatches.

These watches run regular (phone) Android, not Wear OS, so ordinary Android
apps work on them. Every app here is built with `minSdk 21`, so it installs
whatever Android version the watch is really running.

## Apps

| Module | What it does |
| --- | --- |
| `inspector` | **Watch Inspector**: shows the watch's *real* Android version (catches faked "Android 13" claims), chipset, screen, RAM, battery, Google services, hardware features, every sensor with live readings, and a **storage reality test** that detects fake storage capacity. |
| `phonelink` + `companion` | **Phone sync** over Bluetooth. *Watch Link* (on your Android phone) forwards notifications (texts, chats, calls and so on). *Phone Link* (on the watch) shows them with vibration and lets you reply (quick replies or typing), tap their buttons (such as **Decline** on a call), or dismiss them. |
| `launcher` | **Watch Face**: a home-screen replacement with a big clock, date, battery, steps today, local weather, and up to 4 pinned apps. Swipe left (or tap ⋯) for all apps; long-press an app to pin or unpin it. |

Download links (open on the watch):

- Watch Inspector: `github.com/ZeroNolidge/AndroidWatch/releases/download/latest/watch-inspector.apk`
- Watch Face: `github.com/ZeroNolidge/AndroidWatch/releases/download/latest/watch-face.apk`
- Phone Link (watch): `github.com/ZeroNolidge/AndroidWatch/releases/download/latest/phone-link-watch.apk`
- Watch Link (**install on your phone**): `github.com/ZeroNolidge/AndroidWatch/releases/download/latest/watch-link-phone.apk`

### Setting up phone sync

1. **Phone:** install *Watch Link* and open it. Work through its checklist:
   allow Bluetooth, then allow notification access. On Android 13+ a sideloaded
   app's notification access is a "restricted setting": if the switch is greyed
   out, go to **Settings → Apps → Watch Link → ⋮ → Allow restricted settings**,
   then try again.
2. **Pair** the watch and phone in Bluetooth settings (on either device).
3. **Watch:** install *Phone Link*, open it and pick your phone from the list.
   The bar at the top turns green when connected.
4. Tap **Send test notification** on the phone and reply to it from the watch.

Notes:
- Call audio stays on the phone. You can see who's calling and tap Answer or
  Decline on the watch, but you talk on the phone (or its speaker or headset).
- Ongoing notifications such as music players and downloads aren't forwarded.
- The watch reconnects automatically (retrying every 5 to 60 s) and starts on boot.

### Updating apps

All builds are signed with the same key (`signing/sideload.keystore`), so a new
APK installs over the old one and keeps your settings. Builds made **before**
this was set up used random keys: uninstall those once before installing.

### Making Watch Face the home screen

After installing it, press the watch's Home button. Android asks which home app
to use: pick **Watch Face** and choose **Always**. To go back to the stock home
screen, open **Settings → Apps → Default apps → Home app**. The exact menu names
vary by watch.

Weather uses your approximate location and [Open-Meteo](https://open-meteo.com)
(free, no account). Steps come from the watch's built-in step counter and count
from midnight, or from when the face was first opened that day.

## Installing on the watch (no USB needed)

These watches have no data port, but they do have Wi-Fi/4G and a browser.

**Option A: download directly on the watch (easiest)**

1. On the watch, open the browser and go to
   `github.com/ZeroNolidge/AndroidWatch/releases/download/latest/watch-inspector.apk`
   (or open `github.com/ZeroNolidge/AndroidWatch/releases` and tap the APK).
2. Open the downloaded file. When asked, allow the browser/file manager to
   **install unknown apps**, then tap **Install**.

Every push rebuilds the APKs and replaces the "latest" release, so the same link
always gives the newest version.

**Option B: send it from your phone over Bluetooth**

Download the APK on your phone, pair the watch, then use *Share → Bluetooth*
and pick the watch. Accept the file on the watch and open it from the file manager.

**Option C: wireless ADB (for development)**

`adb` normally needs USB once to switch on network mode. Without USB it only works if:
- the watch is Android 11+ and has **Developer options → Wireless debugging**
  (pair with `adb pair <ip>:<port>`, then `adb connect <ip>:<port>`), or
- the vendor ROM has an **ADB over network** toggle in Developer options
  (then `adb connect <watch-ip>:5555`).

To turn on Developer options: **Settings → About → tap Build number 7 times**.

## Getting the report off the watch

In the app, tap **Share** and send it by Bluetooth, email or a messaging app.
If no share option works, tap **Save report**. With wireless ADB you can then run:

```
adb pull /sdcard/Android/data/com.androidwatch.inspector/files/watch-inspector-report.txt
```

The same report is also written to the log: `adb logcat -d -s WatchInspector`.

## Building locally

Install Android Studio (or the SDK), then run `./gradlew assembleDebug`.
APKs end up in `<module>/build/outputs/apk/debug/`.

To preview the watch face at the M99 Mini's exact screen size (412×502 px,
200 dpi) without a watch, run `./gradlew :launcher:recordPaparazziDebug`. The
image appears in `launcher/src/test/snapshots/images/`.

## How the version check works

Vendors can change the version text (`ro.build.version.release`) to say "13",
but the API level (`Build.VERSION.SDK_INT`) is hard to fake, because faking it
crashes apps. The inspector compares the two and also checks which framework
classes actually exist (for example `android.window.OnBackInvokedDispatcher`
only exists on Android 13+). If any of these disagree, the version is flagged as
faked.

## Adding another watch app

Create a sibling module (copy `inspector/`, change `namespace` and
`applicationId`), then add it to `settings.gradle.kts`.
