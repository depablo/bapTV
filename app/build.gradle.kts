import java.util.Collections
import java.util.Properties
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

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
    compileSdk = 35

    defaultConfig {
        applicationId = "com.bonsaponsi.baptv"
        minSdk = 26
        versionCode = 79
        versionName = "1.16.3"
        // Build time in /status (debug server): the deploy script uses it to check that the set-top box runs the
        // version just sent. Release builds keep 0 so that the same source always gives the same APK
        // (reproducible builds, F-Droid).
        buildConfigField("long", "BUILD_TIME", "0L")
        // The debug server (DebugServerService) runs only in debug builds; release builds never have it.
        buildConfigField("boolean", "DEV_SERVER", "false")
        // Report service (server/reports): receives diagnostics reports sent with the user's consent.
        buildConfigField("String", "REPORTS_URL", "\"https://reports.bonsaponsi.com/v1/report\"")
        buildConfigField("String", "CONTACT_EMAIL", "\"bap@bonsaponsi.com\"")
        buildConfigField("String", "PROJECT_URL", "\"https://github.com/depablo/bapTV\"")
        // Phone remote (PhoneRemoteServer). A debug build installed next to the release one uses its own port.
        buildConfigField("int", "PHONE_REMOTE_PORT", "8090")
        // ColourKeysService only receives events of this package (res/xml/colour_keys_service.xml).
        resValue("string", "colour_keys_package", "com.bonsaponsi.baptv")
    }

    /**
     * Two APKs under the same package and signing key, one per device kind:
     * - tv – the full app (tuner, recordings, HbbTV, the remote's server, accessibility service for colour keys).
     *   targetSdk 29: on Android TV 11 only then does requestLegacyExternalStorage work, which is needed to read and
     *   delete dtvinput recordings (stream analysis, recordings screen). With a newer target the files are read-only.
     * - phone – internet channels and the set-top box remote only. A current targetSdk and no TV components, so
     *   phones show no "built for an older version of Android" warning.
     * Each build's updater fetches its own file from the GitHub release (UpdateChecker).
     *
     * Distribution (first dimension, so its values win over the device flavor's):
     * - github – GitHub releases: self-updater, recordings, the debug server, accessibility colour keys.
     * - play – Google Play: no self-updater, no recordings, no debug server, no colour-keys service;
     *   targetSdk 35 for the TV build (Play requires it). Identification still reads a short stream
     *   recording written by the tuner input – probe recordings cannot be deleted at that target (see
     *   DOCUMENTATION.md, "Stream analysis"). Builds: githubTvDebug, playTvRelease, githubPhoneDebug…
     * phone + play is not built: the phone app is not on Play. The Play TV build is paired from phones' browsers;
     * the pilot app (a separate package) is GitHub only.
     */
    flavorDimensions += listOf("distribution", "device")
    productFlavors {
        create("github") {
            dimension = "distribution"
            buildConfigField("boolean", "SELF_UPDATE", "true")
            buildConfigField("boolean", "RECORDING", "true")
            buildConfigField("boolean", "ACCESSIBILITY_KEYS", "true")
            buildConfigField("String", "DISTRIBUTION", "\"github\"")
        }
        create("play") {
            dimension = "distribution"
            targetSdk = 35
            buildConfigField("boolean", "SELF_UPDATE", "false")
            buildConfigField("boolean", "RECORDING", "false")
            buildConfigField("boolean", "ACCESSIBILITY_KEYS", "false")
            buildConfigField("String", "DISTRIBUTION", "\"play\"")
        }
        create("tv") {
            dimension = "device"
            targetSdk = 29
            buildConfigField("boolean", "TV", "true")
        }
        create("phone") {
            dimension = "device"
            targetSdk = 35
            buildConfigField("boolean", "TV", "false")
        }
        // The pilot: a separate app (the phone remote only), GitHub distribution only. Same code, its own manifest in
        // src/pilot. The Google Play build uses the browser remote of the TV app instead (QR code and link).
        create("pilot") {
            dimension = "device"
            applicationId = "com.bonsaponsi.baptv.pilot"
            targetSdk = 35
            buildConfigField("boolean", "TV", "false")
        }
    }

    androidComponents {
        beforeVariants { variant ->
            if (variant.name.startsWith("playPhone")) variant.enable = false
            if (variant.name.startsWith("playPilot")) variant.enable = false
        }
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
            buildConfigField("boolean", "DEV_SERVER", "true")
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
        // ExpiredTargetSdkVersion only matters for Google Play; the tv build targets Android TV 11.
        disable += "ExpiredTargetSdkVersion"
    }
}

// Names from before the distribution flavors – scripts and docs still use them; they build the github variants.
tasks.register("assembleTvDebug") { dependsOn("assembleGithubTvDebug") }
tasks.register("assemblePhoneDebug") { dependsOn("assembleGithubPhoneDebug") }

dependencies {
    // Internet channels (IptvInputService, phone player): HLS, DASH and progressive streams.
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

/**
 * Guard for the Google Play TV release: the debug server (its manifest entry and its classes) and the accessibility
 * install helper must not be in the APK at all. Run after the release build:
 * ./gradlew verifyPlayTvReleaseHasNoDevServer. Strings are searched as UTF-8 and UTF-16LE (binary manifests).
 */
val verifyPlayTvReleaseHasNoDevServer by tasks.registering {
    dependsOn("assemblePlayTvRelease")
    doLast {
        val apk = layout.buildDirectory.file("outputs/apk/playTv/release/app-play-tv-release.apk").get().asFile
        // Class names of the server and its helpers. Route names are not checked: ServerAccess keeps the access rules
        // of both servers (the phone remote is in every build), and those strings are data, not server code.
        val forbidden = listOf("DebugServerService", "DebugHttpServer", "DebugTvTools", "InstallClickService")
        val found = mutableListOf<String>()
        ZipFile(apk).use { zip ->
            Collections.list(zip.entries())
                .filter { entry -> entry.name.endsWith(".dex") || entry.name == "AndroidManifest.xml" }
                .forEach { entry: ZipEntry ->
                    val data: ByteArray = zip.getInputStream(entry).use { input -> input.readBytes() }
                    for (word in forbidden) {
                        for (form in listOf(word.toByteArray(Charsets.UTF_8), word.toByteArray(Charsets.UTF_16LE))) {
                            if (data.indexOfBytes(form) >= 0) found += "${entry.name}: $word"
                        }
                    }
                }
        }
        if (found.isNotEmpty()) {
            throw GradleException("The Google Play TV release contains debug-server code:\n" + found.distinct().joinToString("\n"))
        }
        logger.lifecycle("OK: ${apk.name} contains no debug server")
    }
}

fun ByteArray.indexOfBytes(needle: ByteArray): Int {
    if (needle.isEmpty() || needle.size > size) return -1
    outer@ for (i in 0..size - needle.size) {
        for (j in needle.indices) if (this[i + j] != needle[j]) continue@outer
        return i
    }
    return -1
}
