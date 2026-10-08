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

android {
    namespace = "com.abdllh.aura"
    compileSdk = 34
    buildToolsVersion = "34.0.0"

    defaultConfig {
        applicationId = "com.abdllh.aura"
        minSdk = 26
        targetSdk = 29
        // versionCode = MAJOR*10000 + MINOR*100 + PATCH  (keep in sync with versionName; the updater relies on it)
        versionCode = (project.findProperty("appVersionCode") as String?)?.toInt() ?: 10000
        versionName = (project.findProperty("appVersionName") as String?) ?: "1.0.0"
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
