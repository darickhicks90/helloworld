package app.foxbox.data

import android.content.Context
import android.net.Uri
import java.io.File
import java.util.zip.ZipInputStream

/**
 * Add-ons: extra Fox Box Blocks, app looks and sample packs.
 *
 * Purchases are not wired up yet. To sell add-ons on Google Play, add the
 * Play Billing Library, create in-app products with these ids in Play
 * Console, and implement [purchase] / [refreshPurchases] with BillingClient;
 * [owns] then gates content. Sample packs can already be imported from files.
 */
object Store {
    enum class Kind { BLOCK, LOOK, SAMPLE_PACK }
    class Product(val id: String, val title: String, val kind: Kind, val blurb: String)

    /** Planned add-ons (shown as "coming soon" until billing is connected). */
    val catalog = listOf(
        Product("block_lantern", "Lantern (FM synth block)", Kind.BLOCK, "Glassy FM bells and basses"),
        Product("look_winter", "Winter Clibbins look", Kind.LOOK, "Snow, frost and long blue evenings"),
        Product("pack_field", "Field Recordings pack", Kind.SAMPLE_PACK, "Creeks, birds, wind in the reeds"),
    )

    private lateinit var app: Context
    private lateinit var packsDir: File
    fun init(c: Context) { app = c.applicationContext; packsDir = File(app.filesDir, "packs").apply { mkdirs() } }

    private val owned get() = app.getSharedPreferences("store", Context.MODE_PRIVATE)
    /** Built-in content (no product id) is always owned. */
    fun owns(productId: String?): Boolean = productId == null || owned.getBoolean(productId, false)
    /** Hook for Play Billing: start the purchase flow for `p`. */
    fun purchase(p: Product, done: (String) -> Unit) = done("${p.title} is coming soon.")
    fun refreshPurchases() {}

    // ------------------------------------------------------------------ sample packs

    class Pack(val name: String, val dir: File) { val sounds get() = dir.listFiles()?.filter { it.name.endsWith(".wav", true) }?.sortedBy { it.name } ?: emptyList() }

    fun packs(): List<Pack> = packsDir.listFiles()?.filter { it.isDirectory }?.sortedBy { it.name }?.map { Pack(it.name, it) } ?: emptyList()

    /** Import a zip of WAV files as a sample pack. Returns the number of sounds. */
    fun importPack(uri: Uri, name: String): Int {
        val dir = File(packsDir, ProjectStore.safe(name)).apply { mkdirs() }
        var n = 0
        app.contentResolver.openInputStream(uri)?.use { input ->
            ZipInputStream(input).use { z ->
                while (true) {
                    val e = z.nextEntry ?: break
                    if (!e.isDirectory && e.name.endsWith(".wav", true)) {
                        File(dir, ProjectStore.safe(e.name.substringAfterLast('/'))).writeBytes(z.readBytes()); n++
                    }
                }
            }
        }
        if (n == 0) dir.deleteRecursively()
        return n
    }

    fun deletePack(p: Pack) = p.dir.deleteRecursively()
}
