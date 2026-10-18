// Test TV tuner (TvInputService) for the Android TV emulator: channels in TvContract with a TV guide
// and a drawn picture with a clock, supporting pause/seek. Lets you test the app without a set-top box.
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.bonsaponsi.baptv.testtuner"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.bonsaponsi.baptv.testtuner"
        minSdk = 26
        targetSdk = 29
        versionCode = 1
        versionName = "1.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    lint {
        disable += "ExpiredTargetSdkVersion"
    }
}
