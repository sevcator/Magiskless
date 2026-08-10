plugins {
    id("com.android.application")
}

setupCommon()

android {
    namespace = "com.topjohnwu.magisk"
    enableKotlin = false

    buildTypes {
        release {
            isShrinkResources = false
        }
    }
}
