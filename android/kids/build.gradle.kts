plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Little Eye Yeti: a first synth, drum machine and sampler for 5 to 10 year olds, built on 5 Eye Yeti's sound core.
fun secret(name: String): String? = System.getenv(name) ?: (project.findProperty(name) as String?)
val uploadStore = secret("FOXBOX_UPLOAD_STORE_FILE")?.let { file(it) }?.takeIf { it.exists() }

android {
    namespace = "app.fiveiy.kids"
    compileSdk = 36

    defaultConfig {
        applicationId = "app.fiveiy.kids"
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
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("upload") ?: signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation(project(":shared"))
    implementation(project(":kit"))
}
