import java.util.Properties
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "app.sketchsolve"
    compileSdk = 36

    defaultConfig {
        applicationId = "app.sketchsolve"
        minSdk = 26
        targetSdk = 36
        versionCode = 2
        versionName = "1.1"
        ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
        externalNativeBuild { cmake { arguments += listOf("-DANDROID_STL=c++_shared") } }
    }
    externalNativeBuild { cmake { path = file("src/main/cpp/CMakeLists.txt"); version = "4.1.2" } }
    ndkVersion = "29.0.14206865"

    // The Play upload key, if keystore.properties is present (never commit either file):
    //   storeFile=/path/to/upload.jks  storePassword=…  keyAlias=upload  keyPassword=…
    val keys = rootProject.file("keystore.properties").takeIf { it.exists() }?.let { f -> Properties().apply { f.inputStream().use { load(it) } } }
    signingConfigs {
        if (keys != null) create("upload") {
            storeFile = file(keys.getProperty("storeFile"))
            storePassword = keys.getProperty("storePassword")
            keyAlias = keys.getProperty("keyAlias")
            keyPassword = keys.getProperty("keyPassword")
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            // Without an upload key, signed with the debug key so the release APK installs directly.
            signingConfig = signingConfigs.findByName("upload") ?: signingConfigs.getByName("debug")
        }
    }

    // Model files delivered by Google Play (see preparePlayPacks in the root build file).
    assetPacks += listOf("e4b_1", "e4b_2", "e4b_3", "e2b_1", "e2b_2", "whisper_small", "whisper_base")
        .filter { rootProject.findProject(":$it") != null }.map { ":$it" }
    bundle {
        deviceTargetingConfig = file("device_targeting_config.xml")
        deviceGroup {
            enableSplit = true
            defaultGroup = "standard"
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    testOptions { unitTests.isIncludeAndroidResources = true }
}

kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }

dependencies {
    implementation("com.google.ai.edge.litertlm:litertlm-android:0.19.1")
    // Google Play delivery of the model files (Play for On-device AI)
    implementation("com.google.android.play:ai-delivery:0.2.0-beta01")
    implementation("androidx.activity:activity:1.9.3") // for Play's "download over mobile data?" prompt
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation("androidx.test:core:1.6.1")
}

// Robolectric's Android runtime, fetched by Gradle and used offline by the tests.
val robolectricRuntime: Configuration by configurations.creating
dependencies {
    robolectricRuntime("org.robolectric:android-all-instrumented:14-robolectric-10818077-i7")
    robolectricRuntime("org.robolectric:android-all-instrumented:15-robolectric-12650502-i7")
}
val robolectricDir = layout.buildDirectory.dir("robolectric-deps")
val copyRobolectricRuntime by tasks.registering(Copy::class) {
    from(robolectricRuntime)
    into(robolectricDir)
}
tasks.withType<Test>().configureEach {
    dependsOn(copyRobolectricRuntime)
    systemProperty("robolectric.offline", "true")
    systemProperty("robolectric.dependency.dir", robolectricDir.get().asFile.absolutePath)
    maxHeapSize = "3g"
    // the word-problem scorer reads files Gradle doesn't track, so always run tests
    outputs.upToDateWhen { false }
    System.getenv("WHISPER_FILE")?.let { systemProperty("whisperFile", it) }
}
