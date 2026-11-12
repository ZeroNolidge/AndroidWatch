# AndroidWatch

Apps for the **M99** and **M99 Mini** 4G Android smartwatches.

These watches run regular (phone) Android, not Wear OS, so ordinary Android
apps work on them. Every app here is built with `minSdk 21`, so it installs
whatever Android version the watch is really running.

## Apps

| Module | What it does |
| --- | --- |
| `inspector` | **Watch Inspector**: shows the watch's *real* Android version (catches faked "Android 13" claims), chipset, screen, RAM, battery, Google services, hardware features, and every sensor with live readings. |

## Getting an APK

- **From GitHub:** every push runs the *Build APKs* workflow. Open the run under
  the Actions tab and download the `watch-apks` artifact.
- **Locally:** install Android Studio (or the SDK), then run `./gradlew assembleDebug`.
  The APK ends up in `inspector/build/outputs/apk/debug/inspector-debug.apk`.

## Installing on the watch

1. On the watch, open **Settings → About** and tap **Build number** 7 times.
2. In **Developer options**, turn on **USB debugging**.
3. Connect the watch by USB and run:
   ```
   adb install -r inspector-debug.apk
   ```
   Or copy the APK to the watch and open it from a file manager, allowing
   "install unknown apps" when asked.

## Getting the report off the watch

Tap **Save report** in the app, then:

```
adb pull /sdcard/Android/data/com.androidwatch.inspector/files/watch-inspector-report.txt
```

The same report is also written to the log: `adb logcat -d -s WatchInspector`.

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
