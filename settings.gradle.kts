pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "AndroidWatch"

// One module per watch app. Add new apps here as siblings of :inspector.
include(":inspector")
include(":launcher")
include(":phonelink") // watch side of phone sync
include(":companion") // phone side of phone sync (install on the phone)
