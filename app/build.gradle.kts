import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val localProps = Properties().apply {
    rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
}

/**
 * Release signing is read from a properties file outside the repository (storeFile, storePassword,
 * keyAlias, keyPassword). Its path comes from `baptv.signing` in local.properties or the
 * BAPTV_SIGNING environment variable. Without it, release builds are unsigned.
 */
val signingProps: Properties? = run {
    val path = localProps.getProperty("baptv.signing") ?: System.getenv("BAPTV_SIGNING")
    path?.let { file(it) }?.takeIf { it.exists() }?.let { f -> Properties().apply { f.inputStream().use { load(it) } } }
}

/**
 * Debug builds are installed as com.bonsaponsi.baptv.debug, next to the release version. `baptv.debugSuffix=false`
 * in local.properties keeps the release package name – for a set-top box that already runs an older debug build
 * under that name (its channels and settings stay; a different package would start from scratch).
 */
val debugSuffix = localProps.getProperty("baptv.debugSuffix")?.toBoolean() ?: true

android {
    namespace = "com.bonsaponsi.baptv"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.bonsaponsi.baptv"
        minSdk = 26
        // The target device runs Android TV 11 (API 30). targetSdk 29: on Android 11 only then does
        // requestLegacyExternalStorage work, which is needed to read dtvinput recordings (HbbTV experiment).
        targetSdk = 29
        versionCode = 66
        versionName = "1.10.0"
        // Build time in /status (debug server): the deploy script uses it to check that the set-top box runs the
        // version just sent. Release builds keep 0 so that the same source always gives the same APK
        // (reproducible builds, F-Droid).
        buildConfigField("long", "BUILD_TIME", "0L")
        // Report service (server/reports): receives diagnostics reports sent with the user's consent.
        buildConfigField("String", "REPORTS_URL", "\"https://reports.bonsaponsi.com/v1/report\"")
        buildConfigField("String", "CONTACT_EMAIL", "\"bap@bonsaponsi.com\"")
        buildConfigField("String", "PROJECT_URL", "\"https://github.com/depablo/bapTV\"")
        // Phone remote (PhoneRemoteServer). A debug build installed next to the release one uses its own port.
        buildConfigField("int", "PHONE_REMOTE_PORT", "8090")
        // ColourKeysService only receives events of this package (res/xml/colour_keys_service.xml).
        resValue("string", "colour_keys_package", "com.bonsaponsi.baptv")
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
            if (debugSuffix) {
                applicationIdSuffix = ".debug"
                buildConfigField("int", "PHONE_REMOTE_PORT", "8091")
                resValue("string", "colour_keys_package", "com.bonsaponsi.baptv.debug")
            }
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

    // No Google-encrypted dependency metadata block in the APK (F-Droid rejects it).
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
    // Internet channels (IptvInputService): HLS, DASH and progressive streams. Media3 1.4 is the last line
    // that builds with compileSdk 34.
    val media3 = "1.4.1"
    implementation("androidx.media3:media3-exoplayer:$media3")
    implementation("androidx.media3:media3-exoplayer-hls:$media3")
    implementation("androidx.media3:media3-exoplayer-dash:$media3")
    implementation("androidx.media3:media3-ui:$media3")
    // Already in the APK through media3-ui; named here because the phone screens use it directly.
    implementation("androidx.recyclerview:recyclerview:1.3.0")
    // Phone remote: scans the pairing QR code on the TV (ZXing, Apache 2.0 – no Google services, so it fits F-Droid).
    implementation("com.journeyapps:zxing-android-embedded:4.3.0")
    testImplementation("junit:junit:4.13.2")
}
