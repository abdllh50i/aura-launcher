import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Release signing: signing/keystore.properties (never committed). Without it, release builds are unsigned.
val keystoreProps = Properties().apply {
    val f = rootProject.file("signing/keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

val updateRepo: String = (project.findProperty("updateRepo") as String?) ?: "abdllh50i/aura-launcher"

/**
 * versionCode keeps the order of the version name, pre-releases included (Android only compares the code):
 *   (MAJOR*10000 + MINOR*100 + PATCH) * 100 + (99 for a stable release | N for "-beta.N", 1..98)
 *   1.3.0-beta.1 -> 130001   1.3.0-beta.2 -> 130002   1.3.0 -> 130099   1.3.1-beta.1 -> 130101
 * MINOR and PATCH must stay below 100. Use one numbered series per version ("-beta.1", "-beta.2", ...).
 */
fun versionCodeFor(name: String): Int {
    val m = Regex("""^v?(\d+)\.(\d+)\.(\d+)(?:-([0-9A-Za-z.-]+))?$""").matchEntire(name.trim())
        ?: throw GradleException("versionName must look like 1.2.3 or 1.2.3-beta.4, got '$name'")
    val major = m.groupValues[1].toInt()
    val minor = m.groupValues[2].toInt()
    val patch = m.groupValues[3].toInt()
    if (major > 99 || minor > 99 || patch > 99) throw GradleException("MAJOR, MINOR and PATCH must be below 100: $name")
    val pre = m.groupValues[4]
    val tail = if (pre.isEmpty()) 99 else (Regex("""\d+""").findAll(pre).lastOrNull()?.value?.toInt() ?: 1).coerceIn(1, 98)
    return (major * 10000 + minor * 100 + patch) * 100 + tail
}

val appVersionName: String = (project.findProperty("appVersionName") as String?) ?: "1.0.0"

android {
    namespace = "com.abdllh.aura"
    compileSdk = 34
    buildToolsVersion = "34.0.0"

    defaultConfig {
        applicationId = "com.abdllh.aura"
        minSdk = 26
        targetSdk = 29
        // derived from the version name (see versionCodeFor); the updater requires every update to carry a higher code
        versionCode = versionCodeFor(appVersionName)
        versionName = appVersionName
        buildConfigField("String", "UPDATE_REPO", "\"$updateRepo\"")
        // HTTP is only allowed in debug builds (used to test the updater against a local mock server)
        manifestPlaceholders["cleartext"] = "false"
    }

    signingConfigs {
        if (keystoreProps.containsKey("storeFile")) {
            create("release") {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release")
        }
        debug {
            // Same package + same key as release, so debug and release builds can replace each other.
            signingConfig = signingConfigs.findByName("release")
            manifestPlaceholders["cleartext"] = "true"
        }
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

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}
