plugins {
    id("com.android.application") version "8.7.3" apply false
    id("org.jetbrains.kotlin.android") version "2.0.21" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.21" apply false
}

// Sign every app with the same fixed key, on any machine and in CI. Android
// only installs an update over an existing app if both have the same signature;
// the default debug key is random per machine, so updates would fail with
// "App not installed".
//
// This key lives in the (public) repo, so it only stops accidental mismatches,
// not someone deliberately signing a fake update. It's fine for sideloading your
// own watch apps; use a private key (kept out of git) before publishing anywhere.
subprojects {
    plugins.withId("com.android.application") {
        extensions.configure<com.android.build.api.dsl.ApplicationExtension> {
            signingConfigs.getByName("debug") {
                storeFile = rootProject.file("signing/sideload.keystore")
                storePassword = "androidwatch"
                keyAlias = "sideload"
                keyPassword = "androidwatch"
            }
        }
    }
}
