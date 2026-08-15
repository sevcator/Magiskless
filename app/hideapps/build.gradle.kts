plugins {
    id("com.android.library")
}

setupCommon()

android {
    namespace = "com.topjohnwu.magisk.hideapps"

}

dependencies {
    implementation(project(":shared"))
}
