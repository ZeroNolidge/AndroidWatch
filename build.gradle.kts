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
// Every build gets a higher version number than the last (the number of commits),
// so the Inspector's Updates card always notices new builds. Needs full git
// history (CI checks out with fetch-depth: 0); falls back to each module's own number.
val commitCount: Int? = runCatching {
    providers.exec { commandLine("git", "rev-list", "--count", "HEAD") }
        .standardOutput.asText.get().trim().toInt()
}.getOrNull()

subprojects {
    plugins.withId("com.android.application") {
        extensions.configure<com.android.build.api.variant.ApplicationAndroidComponentsExtension> {
            onVariants { variant ->
                commitCount?.let { count -> variant.outputs.forEach { it.versionCode.set(100 + count) } }
            }
        }
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
