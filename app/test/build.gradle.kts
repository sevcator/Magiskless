plugins {
    id("com.android.application")
}

android {
    namespace = "com.topjohnwu.magisk.test"

    defaultConfig {
        applicationId = "io.sevcator.reisenless.test"
        versionCode = 1
        versionName = "1.0"
        resourceConfigurations += listOf(
            "en", "b+en+Latn+US+lower", "ru", "zh-rCN", "zh-rTW"
        )
        proguardFile("proguard-rules.pro")
    }

    buildTypes {
        release {
            isMinifyEnabled = true
        }
    }
}

setupTestApk()

dependencies {
    implementation(libs.test.runner)
    implementation(libs.test.rules)
    implementation(libs.test.junit)
    implementation(libs.test.uiautomator)
}
