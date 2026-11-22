plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

// What the 5IY apps share: the pixel and Super Nintendo-style interface, the fonts, the radio and the notebook.
android {
    namespace = "app.foxbox.kit"
    compileSdk = 36
    defaultConfig { minSdk = 29 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    // The notebook: on-device handwriting recognition (the language model downloads once, then works offline).
    api("com.google.mlkit:digital-ink-recognition:18.1.0")
    // The radio: streams (Icecast/Shoutcast and HLS) with a tap on the decoded sound for sampling.
    api("androidx.media3:media3-exoplayer:1.4.1")
    api("androidx.media3:media3-exoplayer-hls:1.4.1")
    api("androidx.annotation:annotation-experimental:1.4.1")
}
