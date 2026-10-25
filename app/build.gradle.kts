import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

/**
 * Release signing is read from a properties file outside the repository (storeFile, storePassword,
 * keyAlias, keyPassword). Its path comes from `baptv.signing` in local.properties or the
 * BAPTV_SIGNING environment variable. Without it, release builds are unsigned.
 */
val signingProps: Properties? = run {
    val local = Properties().apply {
        rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
    }
    val path = local.getProperty("baptv.signing") ?: System.getenv("BAPTV_SIGNING")
    path?.let { file(it) }?.takeIf { it.exists() }?.let { f -> Properties().apply { f.inputStream().use { load(it) } } }
}

android {
    namespace = "com.bonsaponsi.baptv"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.bonsaponsi.baptv"
        minSdk = 26
        // The target device runs Android TV 11 (API 30). targetSdk 29: on Android 11 only then does
        // requestLegacyExternalStorage work, which is needed to read dtvinput recordings (HbbTV experiment).
        targetSdk = 29
        versionCode = 57
        versionName = "1.3.1"
        // Build time in /status (debug server): the deploy script uses it to check that the set-top box runs the
        // version just sent. Release builds keep 0 so that the same source always gives the same APK
        // (reproducible builds, F-Droid).
        buildConfigField("long", "BUILD_TIME", "0L")
        // Report service (server/reports): receives diagnostics reports sent with the user's consent.
        buildConfigField("String", "REPORTS_URL", "\"https://reports.bonsaponsi.com/v1/report\"")
        buildConfigField("String", "CONTACT_EMAIL", "\"bap@bonsaponsi.com\"")
        buildConfigField("String", "PROJECT_URL", "\"https://github.com/depablo/bapTV\"")
    }

    signingConfigs {
        signingProps?.let { props ->
            create("release") {
                storeFile = file(props.getProperty("storeFile"))
                storePassword = props.getProperty("storePassword")
                keyAlias = props.getProperty("keyAlias")
                keyPassword = props.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            versionNameSuffix = "-debug"
            buildConfigField("long", "BUILD_TIME", "${System.currentTimeMillis()}L")
        }
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release")
            // Reproducible output: no git state baked into the APK.
            vcsInfo.include = false
        }
    }

    // No Google-encrypted dependency metadata block in the APK (F-Droid rejects it; the app has no dependencies anyway).
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }

    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    testOptions {
        // DebugLog calls android.util.Log – in JVM tests it returns default values.
        unitTests.isReturnDefaultValues = true
    }
    lint {
        // ExpiredTargetSdkVersion only matters for Google Play; the app targets Android TV 11.
        disable += "ExpiredTargetSdkVersion"
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}
