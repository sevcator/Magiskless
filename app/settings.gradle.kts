@Suppress("UnstableApiUsage")
dependencyResolutionManagement {
    repositoriesMode = RepositoriesMode.FAIL_ON_PROJECT_REPOS
    repositories {
        google()
        mavenCentral()
        maven("https://jitpack.io")
    }
}
rootProject.name = "Reisenless"
include(":apk", ":apk-ng", ":core", ":hideapps", ":shared", ":stub", ":stub-res")
