plugins {
    id("com.android.application") version "8.13.2" apply false
    id("com.android.ai-pack") version "8.13.2" apply false
    id("org.jetbrains.kotlin.android") version "2.3.0" apply false
}

/*
 * Split the model files into Google Play packs (at most 1.5 GB each).
 *   ./gradlew preparePlayPacks -Pmodels=/path/to/folder/with/the/model/files
 * Then build the Play bundle with ./gradlew bundleRelease.
 * The layout must match app/.../llm/PlayPacks.kt; the device groups are in
 * app/device_targeting_config.xml.
 */
tasks.register("preparePlayPacks") {
    doLast {
        val dir = file(findProperty("models") ?: throw GradleException("Pass -Pmodels=<folder with the model files>"))
        val chunk = 1_400_000_000L
        // file, pack prefix, device group (null = every phone), size, SHA-256
        val models = listOf(
            listOf("gemma-4-E4B-it.litertlm", "e4b", "large", 3_659_530_240L, "0b2a8980ce155fd97673d8e820b4d29d9c7d99b8fa6806f425d969b145bd52e0"),
            listOf("gemma-4-E2B-it.litertlm", "e2b", "standard", 2_588_147_712L, "181938105e0eefd105961417e8da75903eacda102c4fce9ce90f50b97139a63c"),
            listOf("ggml-small.en-q5_1.bin", "whisper_small", null, 190_098_681L, "bfdff4894dcb76bbf647d56263ea2a96645423f1669176f4844a1bf8e478ad30"),
            listOf("ggml-base.en-q5_1.bin", "whisper_base", "standard", 59_721_011L, "4baf70dd0d7c4247ba2b81fafd9c01005ac77c2f9ef064e00dcf195d0e2fdd2f"),
        )
        for ((name, prefix, group, bytes, sha) in models) {
            val src = File(dir, name as String)
            if (!src.exists()) throw GradleException("Missing ${src}")
            if (src.length() != bytes as Long) throw GradleException("$name is the wrong size")
            val md = java.security.MessageDigest.getInstance("SHA-256")
            val n = ((bytes + chunk - 1) / chunk).toInt()
            src.inputStream().buffered(1 shl 20).use { input ->
                for (i in 1..n) {
                    val pack = if (n == 1) prefix as String else "${prefix}_$i"
                    val assets = file("playpacks/$pack/src/main/assets")
                    assets.deleteRecursively()
                    val folder = File(assets, if (group == null) prefix as String else "$prefix#group_$group")
                    folder.mkdirs()
                    // Play needs every targeted pack to have a folder for every group; the
                    // other group gets a tiny placeholder, which the app reads as "not for this phone"
                    if (group != null) for (g in listOf("large", "standard") - group) File(assets, "$prefix#group_$g/not-for-this-device.txt")
                        .apply { parentFile.mkdirs(); writeText("$name is delivered to the $group device group.\n") }
                    File(folder, "$name.part$i").outputStream().buffered(1 shl 20).use { out ->
                        var left = minOf(chunk, bytes - (i - 1) * chunk)
                        val buf = ByteArray(1 shl 16)
                        while (left > 0) {
                            val k = input.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
                            if (k < 0) throw GradleException("$name ended early")
                            out.write(buf, 0, k); md.update(buf, 0, k); left -= k
                        }
                    }
                }
            }
            val got = md.digest().joinToString("") { "%02x".format(it) }
            if (got != sha) throw GradleException("$name is damaged (SHA-256 $got)")
            println("$name -> $n pack(s)")
        }
    }
}
