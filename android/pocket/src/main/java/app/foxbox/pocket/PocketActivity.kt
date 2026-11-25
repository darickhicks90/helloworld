package app.foxbox.pocket

import android.app.Activity
import android.content.pm.ActivityInfo
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.widget.FrameLayout
import app.foxbox.notes.Notes
import app.foxbox.notes.NotebookView
import android.graphics.Bitmap
import app.foxbox.radio.Radio
import app.foxbox.radio.RadioHost
import app.foxbox.radio.RadioPanel
import app.foxbox.ui.Px
import app.foxbox.ui.TopKeys

/**
 * 5IY Pocket: the notebook and the radio from 5 Eye Yeti, together and on their own. It opens on the
 * memo pad, held upright; the radio key in the corner brings up the portable, which keeps playing
 * while you write. A sketch turns with the phone, as in the big app.
 */
class PocketActivity : Activity(), RadioHost {
    private lateinit var root: FrameLayout
    private lateinit var keys: TopKeys
    private var notebook: NotebookView? = null
    private var board: app.foxbox.pocket.art.ArtBoard? = null
    private var boardSketch: Notes.Sketch? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        volumeControlStream = android.media.AudioManager.STREAM_MUSIC
        Px.init(this)
        app.foxbox.kit.Export.init(this); app.foxbox.kit.Export.root = "5IY Pocket"
        Notes.init(this)
        Radio.init(this)
        if (Build.VERSION.SDK_INT >= 33) {
            onBackInvokedDispatcher.registerOnBackInvokedCallback(android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT) { goBack() }
        }
        requestedOrientation = upright
        root = FrameLayout(this)
        setContentView(root)
        openNotebook()
        keys = TopKeys(this, { RadioPanel.show(this, this) }) { if (board != null) board?.back() else notebook?.refresh() }
        keys.notebookOpen = true
        keys.radioOn = Radio.playing; keys.radioRec = Radio.recording
        Radio.onChange = { runOnUiThread { keys.radioOn = Radio.playing; keys.radioRec = Radio.recording } }
        root.addView(keys, FrameLayout.LayoutParams(TopKeys.WIDTH.toInt(), TopKeys.HEIGHT.toInt(), Gravity.TOP or Gravity.END).apply { topMargin = Px.dp(4); rightMargin = Px.dp(4) })
    }

    /** Held upright on a phone; a tablet can stay however it's held (no letterboxing). */
    private val upright get() = if (resources.configuration.smallestScreenWidthDp >= 600) ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED else ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT

    private fun openNotebook() {
        // "Close" on the pad tucks the app away (the radio keeps playing)
        val nb = NotebookView(this, { Notes.save(); moveTaskToBack(true) }) { s -> openSketch(s) }
        notebook = nb
        // pictures and drawing boards, from the memo-book menu
        nb.extraMenu = { d ->
            d.item("Insert a picture on this page…", app.foxbox.ui.Snes.GREEN) { insertPicture() }
            d.item("New drawing board…", app.foxbox.ui.Snes.YELLOW) { newBoard() }
        }
        root.addView(nb, 0, FrameLayout.LayoutParams(-1, -1))
    }

    // =====================================================================================
    // The drawing board
    // =====================================================================================

    private fun artDir(id: String) = java.io.File(filesDir, "art/$id")

    /** Opens a sketch window on the drawing board; `setup` shapes a brand-new drawing, `then` runs once it's open. */
    private fun openSketch(s: Notes.Sketch, setup: ((app.foxbox.pocket.art.ArtDoc) -> Unit)? = null, then: ((app.foxbox.pocket.art.ArtBoard) -> Unit)? = null) {
        if (board != null) return
        val doc = app.foxbox.pocket.art.ArtDoc(artDir(s.id))
        if (!doc.load()) {
            // a sketch from the notebook's first drawing board comes across with its strokes
            Notes.sketchData(s.id)?.let { if (!it.optBoolean("art")) doc.importOldSketch(it) }
            if (doc.layers.isEmpty()) { doc.w = if (s.square) 1600 else 2000; doc.h = if (s.square) 1600 else 1500 }
            setup?.invoke(doc)
        }
        val v = app.foxbox.pocket.art.ArtBoard(this, doc, object : app.foxbox.pocket.art.ArtBoard.Host {
            override fun pickImage(then: (Bitmap) -> Unit) = this@PocketActivity.pickImage(then)
            override fun closed() = closeBoard()
        })
        board = v; boardSketch = s
        // the drawing board turns with the phone, whichever way suits the picture
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_FULL_USER
        root.addView(v, root.childCount - 1, FrameLayout.LayoutParams(-1, -1))
        then?.invoke(v)
    }

    private fun closeBoard() {
        val v = board ?: return
        val s = boardSketch ?: return
        savePicture(v, s)
        v.release()
        root.removeView(v); board = null; boardSketch = null
        requestedOrientation = upright
        notebook?.refresh()
    }

    /** Saves the drawing, and the picture the page shows (fitted into the window's shape). */
    private fun savePicture(v: app.foxbox.pocket.art.ArtBoard, s: Notes.Sketch) {
        val pic = runCatching { v.saveNow() }.getOrNull() ?: return
        val ww = 1200; val wh = if (s.square) 1200 else 900
        val out = Bitmap.createBitmap(ww, wh, Bitmap.Config.ARGB_8888)
        val c = android.graphics.Canvas(out); c.drawColor(android.graphics.Color.WHITE)
        val k = minOf(ww / pic.width.toFloat(), wh / pic.height.toFloat())
        val dw = pic.width * k; val dh = pic.height * k
        c.drawBitmap(pic, null, android.graphics.RectF((ww - dw) / 2, (wh - dh) / 2, (ww + dw) / 2, (wh + dh) / 2), android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG))
        pic.recycle()
        Notes.saveSketch(s.id, org.json.JSONObject().put("art", true), out)
    }

    /** A picture from the phone, onto a new window on the page, ready to size and place. */
    private fun insertPicture() = pickImage { bmp ->
        val nb = notebook ?: return@pickImage
        val s = nb.placeWindow(square = bmp.width < bmp.height * 1.15f)
        openSketch(s, setup = { doc ->
            // a canvas the picture's shape, up to 2400 px across
            val k = minOf(1f, 2400f / maxOf(bmp.width, bmp.height))
            doc.w = maxOf(16, (bmp.width * k).toInt()); doc.h = maxOf(16, (bmp.height * k).toInt()); doc.title = "Picture"
        }) { it.beginPlace(bmp) }
    }

    private fun newBoard() {
        val kinds = listOf("Sketch", "4K animation (3840 × 2160, frames)", "4K asset (see-through, sized for a 4K project)")
        app.foxbox.ui.SnesDialog.choose(this, "New drawing board", kinds) { k ->
            val presets: List<Pair<String, Pair<Int, Int>>> = when (k) {
                0 -> listOf("Landscape 4:3 (2000 × 1500)" to (2000 to 1500), "Portrait 3:4 (1500 × 2000)" to (1500 to 2000), "Square (1600 × 1600)" to (1600 to 1600),
                    "Wide 16:9 (1920 × 1080)" to (1920 to 1080), "Tall 9:16 (1080 × 1920)" to (1080 to 1920), "Photo 3:2 (2400 × 1600)" to (2400 to 1600))
                1 -> listOf("4K frame (3840 × 2160)" to (3840 to 2160))
                else -> listOf("Character, full height (1080 × 2160)" to (1080 to 2160), "Character, half height (540 × 1080)" to (540 to 1080),
                    "Prop (1024 × 1024)" to (1024 to 1024), "Wide prop (2048 × 1024)" to (2048 to 1024), "Icon or UI piece (512 × 512)" to (512 to 512),
                    "Full 4K background (3840 × 2160)" to (3840 to 2160))
            }
            fun make(i: Int) {
                val (w, h) = presets[i].second
                val nb = notebook ?: return
                val s = nb.placeWindow(square = w < h * 1.15f)
                openSketch(s, setup = { doc ->
                    doc.w = w; doc.h = h
                    doc.kind = when (k) { 0 -> app.foxbox.pocket.art.ArtDoc.KIND_SKETCH; 1 -> app.foxbox.pocket.art.ArtDoc.KIND_4K_FRAME; else -> app.foxbox.pocket.art.ArtDoc.KIND_4K_ASSET }
                    doc.bg = if (k == 2) 0 else android.graphics.Color.WHITE
                    doc.title = when (k) { 0 -> "Sketch"; 1 -> "4K animation"; else -> "4K asset" }
                })
            }
            if (presets.size == 1) make(0) else app.foxbox.ui.SnesDialog.choose(this, "Size", presets.map { it.first }) { make(it) }
        }
    }

    // ---- choosing a picture from the phone
    private var picked: ((Bitmap) -> Unit)? = null
    private fun pickImage(then: (Bitmap) -> Unit) {
        picked = then
        @Suppress("DEPRECATION")
        startActivityForResult(android.content.Intent(android.content.Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(android.content.Intent.CATEGORY_OPENABLE); type = "image/*"
        }, REQ_IMAGE)
    }

    @Deprecated("Activity result API")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: android.content.Intent?) {
        @Suppress("DEPRECATION") super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_IMAGE) return
        val then = picked; picked = null
        val uri = data?.data
        if (resultCode != RESULT_OK || uri == null || then == null) return
        Thread {
            // decoded upright (camera photos included), at most 4096 px across, in plain 8-bit colour
            val bmp = runCatching {
                val src = android.graphics.ImageDecoder.createSource(contentResolver, uri)
                val b = android.graphics.ImageDecoder.decodeBitmap(src) { dec, info, _ ->
                    dec.allocator = android.graphics.ImageDecoder.ALLOCATOR_SOFTWARE
                    val w = info.size.width; val h = info.size.height
                    val k = minOf(1f, 4096f / maxOf(w, h))
                    if (k < 1f) dec.setTargetSize(maxOf(1, (w * k).toInt()), maxOf(1, (h * k).toInt()))
                }
                if (b.config == Bitmap.Config.ARGB_8888) b else b.copy(Bitmap.Config.ARGB_8888, false)
            }.getOrNull()
            runOnUiThread {
                if (bmp == null) android.widget.Toast.makeText(this, "Couldn't open that picture", android.widget.Toast.LENGTH_SHORT).show()
                else then(bmp)
            }
        }.start()
    }

    private fun goBack() {
        board?.let { it.back(); return }
        Notes.save(); moveTaskToBack(true)
    }

    @Deprecated("Back handling for Android 12 and older")
    override fun onBackPressed() = goBack()

    override fun onStop() {
        Notes.save()
        // keep the drawing safe if the app is put away mid-drawing
        board?.let { v -> boardSketch?.let { savePicture(v, it) } }
        super.onStop()
    }
    override fun onStart() { super.onStart(); immersive() }
    override fun onWindowFocusChanged(hasFocus: Boolean) { super.onWindowFocusChanged(hasFocus); if (hasFocus) immersive() }

    private fun immersive() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.insetsController?.let {
                it.hide(WindowInsets.Type.systemBars())
                it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        }
    }

    // recordings off the air are saved as WAV files (there are no samplers here)
    override fun sampleTargets(): List<Pair<String, String>> = emptyList()
    override fun deliverSample(target: String, data: FloatArray, rate: Int, name: String) {}

    private companion object { const val REQ_IMAGE = 41 }
}
