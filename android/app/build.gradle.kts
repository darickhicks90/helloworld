plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "app.foxbox"
    compileSdk = 35

    defaultConfig {
        applicationId = "app.foxbox"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
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

    // The web app (index.html, css, js, fonts in the repository root) is
    // copied into the APK's assets at build time, so there is one copy of it.
    sourceSets["main"].assets.srcDir(layout.buildDirectory.dir("generated/web"))
}

val copyWebApp by tasks.registering(Sync::class) {
    from(rootDir.parentFile) {
        include("index.html", "css/**", "js/**", "fonts/**")
    }
    into(layout.buildDirectory.dir("generated/web/www"))
}
tasks.named("preBuild") { dependsOn(copyWebApp) }

dependencies {
    implementation("androidx.webkit:webkit:1.12.1")
}
