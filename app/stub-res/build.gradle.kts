plugins {
    id("com.android.application")
}

setupCommon()

android {
    namespace = "com.topjohnwu.magisk"
    enableKotlin = false

    defaultConfig {
        resourceConfigurations += listOf(
            "en", "b+en+Latn+US+lower", "ru", "zh-rCN", "zh-rTW"
        )
    }

    buildTypes {
        release {
            isShrinkResources = false
        }
    }
}
