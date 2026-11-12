# AndroidWatch

Apps for the **M99** and **M99 Mini** 4G Android smartwatches.

These watches run regular (phone) Android, not Wear OS, so ordinary Android
apps work on them. Every app here is built with `minSdk 21`, so it installs
whatever Android version the watch is really running.

## Apps

| Module | What it does |
| --- | --- |
| `inspector` | **Watch Inspector**: shows the watch's *real* Android version (catches faked "Android 13" claims), chipset, screen, RAM, battery, Google services, hardware features, and every sensor with live readings. |

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
The APK ends up in `inspector/build/outputs/apk/debug/inspector-debug.apk`.

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
