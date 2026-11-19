plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Release signing comes from environment variables (or ~/.gradle/gradle.properties),
// never from files in this repository. Without them, release builds are signed
// with the included test key so they can still be installed for testing.
fun secret(name: String): String? = System.getenv(name) ?: (project.findProperty(name) as String?)
val uploadStore = secret("FOXBOX_UPLOAD_STORE_FILE")?.let { file(it) }?.takeIf { it.exists() }

android {
    namespace = "app.foxbox"
    compileSdk = 36

    defaultConfig {
        applicationId = "app.foxbox"
        minSdk = 29
        targetSdk = 36
        versionCode = 17
        versionName = "1.6.0"
        externalNativeBuild { cmake { arguments += listOf("-DANDROID_STL=none") } }
    }

    signingConfigs {
        getByName("debug") {
            storeFile = file("foxbox-debug.keystore")
            storePassword = "foxbox"
            keyAlias = "foxbox"
            keyPassword = "foxbox"
        }
        if (uploadStore != null) {
            create("upload") {
                storeFile = uploadStore
                storePassword = secret("FOXBOX_UPLOAD_STORE_PASSWORD")
                keyAlias = secret("FOXBOX_UPLOAD_KEY_ALIAS")
                keyPassword = secret("FOXBOX_UPLOAD_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("upload") ?: signingConfigs.getByName("debug")
        }
    }

    externalNativeBuild {
        cmake { path = file("src/main/cpp/CMakeLists.txt") }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    packaging {
        jniLibs { useLegacyPackaging = false }
    }
}

dependencies {
    // The sound core (engines, sequencer, flowers) shared with the iOS app.
    implementation(project(":shared"))
    // The notebook: on-device handwriting recognition (the language model downloads once, then works offline).
    implementation("com.google.mlkit:digital-ink-recognition:18.1.0")
    // The radio: streams (Icecast/Shoutcast and HLS) with a tap on the decoded sound for sampling.
    implementation("androidx.media3:media3-exoplayer:1.4.1")
    implementation("androidx.media3:media3-exoplayer-hls:1.4.1")
    implementation("androidx.annotation:annotation-experimental:1.4.1")
}
