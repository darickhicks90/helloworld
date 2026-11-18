import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.plugin.mpp.apple.XCFramework

plugins {
    id("org.jetbrains.kotlin.multiplatform")
}

// The sound core shared by the Android app (as a JVM library) and the iOS app
// (as the Shared framework). `-PcoreCheck` adds a JavaScript target, used only to
// check on machines without Xcode that the core has no JVM-only code left.
kotlin {
    jvm { compilerOptions { jvmTarget.set(JvmTarget.JVM_17) } }
    val xcf = XCFramework("Shared")
    listOf(iosArm64(), iosSimulatorArm64(), iosX64()).forEach {
        it.binaries.framework {
            baseName = "Shared"
            isStatic = true
            xcf.add(this)
        }
    }
    if (project.hasProperty("coreCheck")) js { nodejs() }

    applyDefaultHierarchyTemplate()
    sourceSets {
        all {
            languageSettings.optIn("kotlin.ExperimentalStdlibApi")
            languageSettings.optIn("kotlinx.cinterop.ExperimentalForeignApi")
            languageSettings.optIn("kotlin.experimental.ExperimentalNativeApi")
        }
        // Android provides org.json at run time; the JVM build only compiles against it.
        jvmMain.dependencies { compileOnly("org.json:json:20240303") }
        // Everything that isn't the JVM (iOS, and the JS check) gets the small org.json stand-in.
        val nonJvmMain by creating { dependsOn(commonMain.get()) }
        iosMain.get().dependsOn(nonJvmMain)
        if (project.hasProperty("coreCheck")) jsMain.get().dependsOn(nonJvmMain)
    }
}
