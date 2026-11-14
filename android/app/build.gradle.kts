plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "app.foxbox"
    compileSdk = 35

    defaultConfig {
        applicationId = "app.foxbox"
        minSdk = 29
        targetSdk = 35
        versionCode = 2
        versionName = "2.0"
    }

    // A fixed key for test builds, so a new APK installs over the old one
    // instead of asking you to uninstall first. Not for the Play Store.
    signingConfigs {
        getByName("debug") {
            storeFile = file("foxbox-debug.keystore")
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
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    // No libraries needed: FoxBox uses only the Android framework.
}
