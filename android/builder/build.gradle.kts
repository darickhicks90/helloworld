plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// 5IY Builder: lay out worlds (rooms, tiles, props, characters, kiosks, items, sound, music)
// from your own sprites and sounds, kept in a folder you choose.
android {
    namespace = "app.fiveiy.builder"
    compileSdk = 36

    defaultConfig {
        applicationId = "app.fiveiy.builder"
        minSdk = 29
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
    }

    signingConfigs {
        getByName("debug") {
            storeFile = file("../app/foxbox-debug.keystore")
            storePassword = "foxbox"
            keyAlias = "foxbox"
            keyPassword = "foxbox"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}
