pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}
rootProject.name = "SketchSolve"
include(":app")

// Google Play packs holding the model files. Each is included once
// `./gradlew preparePlayPacks -Pmodels=<folder>` has filled it.
for (p in listOf("e4b_1", "e4b_2", "e4b_3", "e2b_1", "e2b_2", "whisper_small", "whisper_base")) {
    if (file("playpacks/$p/src/main/assets").isDirectory) {
        include(":$p")
        project(":$p").projectDir = file("playpacks/$p")
    }
}
