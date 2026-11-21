package app.fiveiy.builder

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.text.InputType
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.ArrayAdapter
import android.widget.CheckBox
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import java.util.concurrent.Executors

/**
 * 5IY Builder. Pick a folder once; the Builder fills it with a starter kit and a README in
 * every sub-folder, then watches it: drop your own sprites, sounds and music in (even while
 * it's open) and they turn up in the palette. Build rooms, then press Play to walk them.
 */
class MainActivity : Activity() {
    private val prefs get() = getSharedPreferences("builder", Context.MODE_PRIVATE)
    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private var ws: Workspace? = null
    private var lib: Library? = null
    private var world: World? = null
    private var worldFile = ""
    private lateinit var view: RoomView
    private val undo = ArrayDeque<String>()
    private var pending: String? = null
    private var cat = "props"

    private fun dp(v: Float) = v * resources.displayMetrics.density
    private fun dpi(v: Float) = dp(v).toInt()

    companion object {
        const val PICK_FOLDER = 1; const val IMPORT = 2; const val STUDIO_IMAGE = 3
        const val BG = 0xFF14141C.toInt(); const val PANEL = 0xFF22222E.toInt(); const val INK = 0xFF181820.toInt()
        const val GOLD = 0xFFF0CC50.toInt(); const val GREEN = 0xFF48A058.toInt(); const val RED = 0xFFC84848.toInt(); const val BLUE = 0xFF3C6CC8.toInt(); const val PURPLE = 0xFF6048B0.toInt(); const val GREY = 0xFF4A4A5A.toInt()
        val TABS = listOf("tiles" to "Tiles", "walls" to "Walls", "props" to "Props", "decor" to "Decor", "characters" to "Characters", "kiosks" to "Kiosks", "items" to "Items", "sounds" to "Sounds", "music" to "Music")
        val SCALES = floatArrayOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f, 2.5f, 3f, 4f)
    }

    // =====================================================================================
    // start: the folder
    // =====================================================================================
    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        window.statusBarColor = BG; window.navigationBarColor = BG
        val tree = prefs.getString("tree", null)?.let { Uri.parse(it) }
        if (tree != null && contentResolver.persistedUriPermissions.any { it.uri == tree && it.isWritePermission }) openFolder(tree) else welcome()
    }

    private fun padded(v: View): View {
        v.setOnApplyWindowInsetsListener { vv, insets ->
            val i = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.ime())
            vv.setPadding(i.left, i.top, i.right, i.bottom); insets
        }
        return v
    }

    private fun welcome() {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(BG); gravity = Gravity.CENTER; setPadding(dpi(28f), dpi(28f), dpi(28f), dpi(28f)) }
        box.addView(label("5IY BUILDER", 26f, GOLD))
        box.addView(label("Lay out rooms, people, shops and treasure for the hall, with your own art, sounds and music.\n\n" +
            "First, choose a folder for your world (make a new one, e.g. \"Documents/5IY Builder\"). The Builder puts a starter kit in it, " +
            "with a README in every sub-folder saying what to put there. Add your own files to it any time, from the phone or a computer.", 15f, 0xFFE0E0E8.toInt()).apply { setPadding(0, dpi(18f), 0, dpi(24f)) })
        box.addView(button("Choose a folder", GREEN) { pickFolder() })
        setContentView(padded(box))
    }

    private fun pickFolder() {
        runCatching { startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION), PICK_FOLDER) }
            .onFailure { toast("No folder picker on this phone") }
    }

    @Deprecated("Activity result")
    override fun onActivityResult(req: Int, res: Int, data: Intent?) {
        super.onActivityResult(req, res, data)
        val uri = data?.data ?: return
        if (res != RESULT_OK) return
        when (req) {
            PICK_FOLDER -> {
                runCatching { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) }
                prefs.edit().putString("tree", uri.toString()).apply()
                lib?.release(); lib = null
                openFolder(uri)
            }
            IMPORT -> importFile(uri)
            STUDIO_IMAGE -> studio?.importImage(uri)
        }
    }

    private fun openFolder(tree: Uri) {
        setContentView(padded(label("Setting up your folder…", 16f, 0xFFE0E0E8.toInt()).apply { gravity = Gravity.CENTER; setBackgroundColor(BG) }))
        val w = Workspace(this, tree); ws = w
        io.execute {
            val err = runCatching {
                val key = "starter2:$tree"
                if (!prefs.getBoolean(key, false)) { w.installStarter(); prefs.edit().putBoolean(key, true).apply() }
                else w.installStarter(onlyReadmes = true)
                // new art packs arrive once in folders set up before they existed
                val pk = "pack_wildwood1:$tree"
                if (!prefs.getBoolean(pk, false)) { w.installStarter(only = "packs/wildwood"); prefs.edit().putBoolean(pk, true).apply() }
            }.exceptionOrNull()
            main.post {
                if (err != null) { toast("Couldn't use that folder: ${err.message}"); welcome(); return@post }
                val l = Library(this, w); lib = l
                buildUi(l)
                loadWorld(prefs.getString("world", null))
                l.onChanged = { added, removed, first -> onAssets(added, removed, first) }
                l.start()
            }
        }
    }

    // =====================================================================================
    // the screen
    // =====================================================================================
    private lateinit var modeBtn: TextView
    private lateinit var roomBtn: TextView
    private lateinit var inspector: LinearLayout
    private lateinit var inspectorScroll: HorizontalScrollView
    private lateinit var tools: LinearLayout
    private lateinit var tabs: LinearLayout
    private lateinit var palette: LinearLayout
    private val toolBtns = HashMap<RoomView.Tool, TextView>()

    private fun label(s: String, size: Float, col: Int) = TextView(this).apply { text = s; textSize = size; setTextColor(col); typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD) }
    private fun bg(col: Int, stroke: Int = 0) = GradientDrawable().apply { cornerRadius = dp(8f); setColor(col); if (stroke != 0) setStroke(dpi(2f), stroke) }
    private fun button(s: String, col: Int, click: () -> Unit) = TextView(this).apply {
        text = s; textSize = 13f; setTextColor(if (col == GOLD) INK else 0xFFFFFFFF.toInt()); typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        gravity = Gravity.CENTER; background = bg(col); setPadding(dpi(12f), dpi(9f), dpi(12f), dpi(9f))
        setOnClickListener { click() }
    }
    private fun row() = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dpi(4f), dpi(3f), dpi(4f), dpi(3f)) }
    private fun LinearLayout.put(v: View) = addView(v, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { rightMargin = dpi(5f) })
    private fun scroll(inner: LinearLayout) = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(inner) }

    private fun buildUi(l: Library) {
        view = RoomView(this, l)
        view.onBegin = { pending = world?.toJson() }
        view.onCommit = { pending?.let { pushUndo(it) }; pending = null; changed() }
        view.onSelect = { refreshInspector() }
        view.onRoom = { refreshRoomBtn(); refreshPalette() }

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(BG) }
        mainRoot = root
        val top = row()
        modeBtn = button("▶ PLAY", GREEN) { toggleMode() }; top.put(modeBtn)
        roomBtn = button("ROOM", BLUE) { showMap() }; top.put(roomBtn)
        top.put(button("UNDO", GREY) { doUndo() })
        top.put(button("WORLD", PURPLE) { worldMenu() })
        top.put(button("✎ PIXEL", GOLD) { openStudio(null) })
        top.put(button("?", GREY) { help() })
        root.addView(scroll(top))
        root.addView(view, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        inspector = row(); inspectorScroll = scroll(inspector); root.addView(inspectorScroll)
        tools = row()
        for ((t, name) in listOf(RoomView.Tool.PLACE to "PLACE", RoomView.Tool.SELECT to "SELECT", RoomView.Tool.ERASE to "ERASE")) {
            val b = button(name, GREY) { view.tool = t; refreshTools() }; toolBtns[t] = b; tools.put(b)
        }
        tools.put(button("SNAP ${view.snap}", GREY) {}.also { b -> b.setOnClickListener { view.snap = when (view.snap) { 1 -> 4; 4 -> 8; 8 -> 16; else -> 1 }; b.text = "SNAP ${view.snap}" } })
        tools.put(button("GRID", GREY) { view.grid = !view.grid })
        root.addView(scroll(tools))
        tabs = row(); root.addView(scroll(tabs))
        palette = row(); root.addView(scroll(palette), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dpi(84f)))
        setContentView(padded(root))
        refreshTabs(); refreshTools(); refreshInspector()
    }

    // ---- the pixel studio, over the whole screen
    private var mainRoot: View? = null
    private var studio: PixelStudio? = null
    fun toastIt(s: String) = toast(s)
    fun pickImageForStudio() {
        runCatching { startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("image/*"), STUDIO_IMAGE) }
            .onFailure { toast("No picture picker on this phone") }
    }
    private fun openStudio(key: String?) {
        val l = lib ?: return; val w = ws ?: return
        if (view.play) toggleMode()
        val st = studio ?: PixelStudio(this, l, w) { closeStudio() }.also { studio = it }
        val v = st.build(); if (key != null) st.open(key)
        setContentView(padded(v))
    }
    private fun closeStudio() {
        studio = null
        mainRoot?.let { r -> (r.parent as? ViewGroup)?.removeView(r); setContentView(padded(r)) }
        refreshTabs()
    }

    private fun refreshTools() {
        for ((t, b) in toolBtns) b.background = bg(if (view.tool == t) GOLD else GREY).also { b.setTextColor(if (view.tool == t) INK else 0xFFFFFFFF.toInt()) }
        tools.visibility = if (view.play) View.GONE else View.VISIBLE
    }
    private fun refreshRoomBtn() { roomBtn.text = "▦ " + (view.room?.name ?: "ROOM").uppercase() }

    private fun refreshTabs() {
        tabs.removeAllViews()
        for ((c, name) in TABS) {
            val n = lib?.list(c)?.size ?: 0
            tabs.put(button("$name $n", if (c == cat) GOLD else PANEL) { cat = c; refreshTabs(); refreshPalette() })
        }
        refreshPalette()
    }

    /** One palette cell: the sprite's first frame (or a note for sounds), and its name. */
    private inner class Thumb(val key: String?, val title: String, val on: Boolean) : View(this@MainActivity) {
        private val p = Paint().apply { isFilterBitmap = false }
        private val sp = Paint(Paint.FILTER_BITMAP_FLAG)
        private val f = Paint(Paint.ANTI_ALIAS_FLAG)
        private val t = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = dp(10f); color = 0xFFE0E0E8.toInt(); textAlign = Paint.Align.CENTER; typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD) }
        override fun onDraw(c: Canvas) {
            val w = width.toFloat(); val h = height.toFloat()
            f.color = if (on) GOLD else PANEL; c.drawRoundRect(0f, 0f, w, h, dp(8f), dp(8f), f)
            f.color = 0xFF2E2E3C.toInt(); c.drawRoundRect(dp(3f), dp(3f), w - dp(3f), h - dp(3f), dp(6f), dp(6f), f)
            val a = lib?.get(key); val b = a?.bmp
            val box = RectF(dp(8f), dp(6f), w - dp(8f), h - dp(20f))
            if (b != null) {
                val fw = a.frameW.toFloat(); val fh = a.frameH.toFloat()
                val k = minOf(box.width() / fw, box.height() / fh).let { if (it >= 1f && a.density == 1) kotlin.math.floor(it) else it }
                val dw = fw * k; val dh = fh * k
                c.drawBitmap(b, Rect(0, 0, a.srcW, a.srcH), RectF(box.centerX() - dw / 2, box.bottom - dh, box.centerX() + dw / 2, box.bottom), if (a.density > 1) sp else p)
                if (a.frames > 1) { t.textSize = dp(9f); t.color = GOLD; c.drawText("×${a.frames}", w - dp(14f), dp(16f), t); t.color = 0xFFE0E0E8.toInt() }
            } else {
                t.textSize = dp(24f); c.drawText(when { key == null -> title.take(1); key.startsWith("music") -> "♫"; key.startsWith("sounds") -> "♪"; else -> "?" }, box.centerX(), box.centerY() + dp(9f), t)
            }
            t.textSize = dp(10f)
            var s = title; while (s.length > 3 && t.measureText(s) > w - dp(8f)) s = s.dropLast(1)
            c.drawText(if (s != title) s.dropLast(1) + "…" else s, w / 2, h - dp(7f), t)
        }
    }

    private fun refreshPalette() {
        if (!::palette.isInitialized) return
        palette.removeAllViews()
        val l = lib ?: return
        fun cell(key: String?, title: String, on: Boolean, click: () -> Unit) {
            val v = Thumb(key, title, on); v.setOnClickListener { click() }
            palette.addView(v, LinearLayout.LayoutParams(dpi(72f), dpi(76f)).apply { rightMargin = dpi(5f) })
        }
        cell(null, "+ ADD FILE", false) { pickImport() }
        when (cat) {
            "sounds" -> for (a in l.list(cat)) cell(a.key, a.name, view.selected?.sound == a.key) { soundTapped(a.key) }
            "music" -> {
                cell(null, "NONE", view.room?.music == null) { setMusic(null) }
                for (a in l.list(cat)) cell(a.key, a.name, view.room?.music == a.key) { setMusic(a.key) }
            }
            else -> for (a in l.list(cat)) {
                cell(a.key, RoomView.label(a.key).lowercase(), view.brush == a.key) { spriteTapped(a.key) }
                palette.getChildAt(palette.childCount - 1).setOnLongClickListener { openStudio(a.key); true }
            }
        }
        if (l.list(cat).isEmpty()) palette.addView(label("  Nothing here yet: add files to ${Library.folderOf(cat)}/ (see its README).", 12f, 0xFF9090A0.toInt()))
    }

    private fun spriteTapped(key: String) {
        if (view.play) {
            if (cat == "tiles" || cat == "walls") { toast("Tiles are painted in Build mode"); return }
            pushUndo(world!!.toJson()); view.spawnHere(key); changed(); return
        }
        view.brush = key
        if (view.tool != RoomView.Tool.PLACE) { view.tool = RoomView.Tool.PLACE; refreshTools() }
        refreshPalette()
    }
    private fun soundTapped(key: String) {
        lib?.play(key)
        val o = view.selected
        if (!view.play && o != null) { pushUndo(world!!.toJson()); o.sound = key; changed(); toast("${RoomView.label(o.sprite)} now sounds: ${RoomView.label(key)}"); refreshPalette() }
    }
    private fun setMusic(key: String?) {
        val r = view.room ?: return
        if (key != null && r.music == key && lib?.playing == key) { lib?.music(null); return }   // tap again: stop the preview
        if (r.music != key) { pushUndo(world!!.toJson()); r.music = key; changed() }
        lib?.music(key)
        refreshPalette()
    }

    // ---- the inspector: what you can do to the selected object
    private fun refreshInspector() {
        if (!::inspector.isInitialized) return
        inspector.removeAllViews()
        val o = view.selected
        if (o == null || view.play) { inspectorScroll.visibility = View.GONE; return }
        inspectorScroll.visibility = View.VISIBLE
        fun act(f: () -> Unit) { pushUndo(world!!.toJson()); f(); changed(); refreshInspector() }
        inspector.put(label(RoomView.label(o.sprite), 12f, GOLD).apply { setPadding(dpi(4f), 0, dpi(6f), 0) })
        inspector.put(button("−", GREY) { act { o.scale = SCALES.lastOrNull { it < o.scale - 0.01f } ?: o.scale } })
        inspector.put(label("${o.scale}x", 13f, 0xFFFFFFFF.toInt()))
        inspector.put(button("+", GREY) { act { o.scale = SCALES.firstOrNull { it > o.scale + 0.01f } ?: o.scale } })
        if ((lib?.get(o.sprite)?.dirs ?: 1) == 4) inspector.put(button("FACE " + arrayOf("▼", "◀", "▶", "▲")[o.dir], GREY) { act { o.dir = (o.dir + 1) % 4 } })
        else inspector.put(button("FLIP", GREY) { act { o.flip = !o.flip } })
        inspector.put(button(if (o.solid) "SOLID ✓" else "SOLID ✗", if (o.solid) RED else GREY) { act { o.solid = !o.solid } })
        inspector.put(button(arrayOf("FLOOR", "STAND", "ABOVE")[o.layer.coerceIn(0, 2)], GREY) { act { o.layer = (o.layer + 1) % 3 } })
        inspector.put(button("EDIT…", BLUE) { editObj(o) })
        inspector.put(button("COPY", GREY) {
            act { val w = world!!; val c = w.newObj(o.sprite, (o.x + 16).coerceAtMost(World.W - 2f), o.y); o.copyTo(c); view.room?.objs?.add(c); view.selected = c }
        })
        inspector.put(button("DELETE", RED) { act { view.room?.objs?.remove(o); view.selected = null } })
    }

    private fun editObj(o: World.Obj) {
        val l = lib ?: return; val w = world ?: return
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dpi(20f), dpi(8f), dpi(20f), dpi(8f)) }
        fun cap(s: String) = TextView(this).apply { text = s; textSize = 13f; setPadding(0, dpi(10f), 0, dpi(2f)) }
        val isKiosk = o.cat == "kiosks"
        box.addView(cap(when { o.cat == "characters" -> "What they say (one line per box of speech):"; isKiosk -> "Text shown when used (one line per box):"; else -> "Text shown when you use it (leave empty for none):" }))
        val lines = EditText(this).apply { setText(o.lines.joinToString("\n")); minLines = 3; inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES }
        box.addView(lines)
        val wander = CheckBox(this).apply { text = "Wanders about"; isChecked = o.wander }
        if (o.cat == "characters") box.addView(wander)
        val group = RadioGroup(this)
        val items = listOf("(none)") + l.list("items").map { it.key }
        val itemSpin = Spinner(this).apply { adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, items.map { if (it == "(none)") it else RoomView.label(it) }); setSelection(items.indexOf(o.item ?: "(none)").coerceAtLeast(0)) }
        val price = EditText(this).apply { setText(o.price.toString()); inputType = InputType.TYPE_CLASS_NUMBER }
        val rooms = w.rooms.values.toList()
        val roomSpin = Spinner(this).apply { adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, listOf("(none)") + rooms.map { "${it.name} (${it.key})" }); setSelection(rooms.indexOfFirst { it.key == o.target } + 1) }
        if (isKiosk) {
            box.addView(cap("What it does:"))
            val kinds = listOf("sign" to "SIGN: shows its text", "shop" to "SHOP: sells the item below for the price", "chest" to "CHEST: gives the item once (coins: the price is how many)", "teleport" to "TELEPORT: sends you to the room below", "save" to "SAVE: your restart point")
            kinds.forEachIndexed { i, (k, s) -> group.addView(RadioButton(this).apply { id = 1000 + i; text = s; isChecked = o.kiosk == k }) }
            box.addView(group)
            box.addView(cap("Item:")); box.addView(itemSpin)
            box.addView(cap("Price / number of coins:")); box.addView(price)
            box.addView(cap("Teleport to room:")); box.addView(roomSpin)
        }
        val sounds = listOf("(default)") + l.list("sounds").map { it.key }
        val soundSpin = Spinner(this).apply { adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, sounds.map { if (it == "(default)") it else RoomView.label(it) }); setSelection(sounds.indexOf(o.sound ?: "(default)").coerceAtLeast(0)) }
        box.addView(cap(if (o.cat == "characters") "Voice (sound when you talk to them):" else "Sound when used or picked up:")); box.addView(soundSpin)
        AlertDialog.Builder(this).setTitle(RoomView.label(o.sprite)).setView(ScrollView(this).apply { addView(box) })
            .setPositiveButton("OK") { _, _ ->
                pushUndo(w.toJson())
                o.lines.clear(); lines.text.toString().split('\n').map { it.trim() }.filter { it.isNotEmpty() }.forEach { o.lines += it }
                o.wander = wander.isChecked
                if (isKiosk) {
                    val k = group.checkedRadioButtonId - 1000; if (k in 0 until World.KIOSKS.size) o.kiosk = World.KIOSKS[k]
                    o.item = items.getOrNull(itemSpin.selectedItemPosition)?.takeIf { it != "(none)" }
                    o.price = price.text.toString().toIntOrNull()?.coerceIn(0, 9999) ?: 0
                    o.target = rooms.getOrNull(roomSpin.selectedItemPosition - 1)?.key
                }
                o.sound = sounds.getOrNull(soundSpin.selectedItemPosition)?.takeIf { it != "(default)" }
                changed(); refreshInspector()
            }.setNegativeButton("Cancel", null).show()
    }

    // =====================================================================================
    // modes, rooms, worlds
    // =====================================================================================
    private fun toggleMode() {
        if (view.play) { view.stopPlay(); modeBtn.text = "▶ PLAY"; modeBtn.background = bg(GREEN) }
        else { view.startPlay(); modeBtn.text = "✎ BUILD"; modeBtn.background = bg(RED); toast("Playing. Tap a palette item to drop it in front of you.") }
        refreshTools(); refreshInspector(); refreshRoomBtn()
    }

    private fun showMap() {
        val w = world ?: return
        val mv = MapView(this, w, view.roomKey)
        val d = AlertDialog.Builder(this).setTitle("Map: tap a room to go there, + to add one").setView(mv)
            .setNeutralButton("Rename room") { _, _ -> renameRoom() }
            .setNegativeButton("Delete room") { _, _ -> deleteRoom() }
            .setPositiveButton("Close", null).create()
        mv.onPick = { gx, gy ->
            val k = World.key(gx, gy)
            if (!w.rooms.containsKey(k)) { pushUndo(w.toJson()); w.addRoom(gx, gy, "Room ${w.rooms.size + 1}"); changed() }
            goRoom(k); d.dismiss()
        }
        d.show()
    }
    private fun goRoom(k: String) {
        if (view.play) toggleMode()
        view.roomKey = k; view.selected = null
        refreshRoomBtn(); refreshInspector(); refreshPalette()
    }
    private fun renameRoom() {
        val r = view.room ?: return
        ask("Room name", r.name) { s -> pushUndo(world!!.toJson()); r.name = s; changed(); refreshRoomBtn() }
    }
    private fun deleteRoom() {
        val w = world ?: return; val r = view.room ?: return
        if (w.rooms.size <= 1) { toast("A world needs at least one room"); return }
        AlertDialog.Builder(this).setMessage("Delete \"${r.name}\" and everything in it? (UNDO brings it back.)")
            .setPositiveButton("Delete") { _, _ ->
                pushUndo(w.toJson()); w.rooms.remove(r.key)
                if (w.startRoom == r.key) w.startRoom = w.rooms.keys.first()
                changed(); goRoom(w.rooms.keys.first())
            }.setNegativeButton("Cancel", null).show()
    }

    private fun worldMenu() {
        val l = lib ?: return
        val opts = arrayOf("New world", "Open a world…", "Rename this world", "Start the game in this room", "Problems (${l.problems.size})", "Put back missing starter files", "Use a different folder", "Help")
        AlertDialog.Builder(this).setTitle(world?.name ?: "World").setItems(opts) { _, i ->
            when (i) {
                0 -> ask("Name of the new world", "World ${listWorlds().size + 1}") { s -> saveNow(); val w = World(s).starter(); setWorld(w, fileFor(s)); saveNow() }
                1 -> { val names = listWorlds(); if (names.isEmpty()) toast("No saved worlds") else AlertDialog.Builder(this).setItems(names.toTypedArray()) { _, k -> saveNow(); loadWorld(names[k]) }.show() }
                2 -> ask("World name", world?.name ?: "") { s -> renameWorld(s) }
                3 -> { val w = world ?: return@setItems; pushUndo(w.toJson()); w.startRoom = view.roomKey; changed(); toast("The game starts in ${view.room?.name}") }
                4 -> AlertDialog.Builder(this).setTitle("Problems").setMessage(if (l.problems.isEmpty()) "None: every file in the folder can be used." else l.problems.entries.joinToString("\n\n") { "${it.key}\n  ${it.value}" }).setPositiveButton("OK", null).show()
                5 -> io.execute { val n = runCatching { ws?.installStarter() ?: 0 }.getOrDefault(0); main.post { toast(if (n == 0) "Nothing was missing" else "Put back $n files"); lib?.rescan() } }
                6 -> pickFolder()
                7 -> help()
            }
        }.show()
    }

    private fun help() {
        AlertDialog.Builder(this).setTitle("How it works").setMessage(
            "BUILD\n• Pick a tab, then a picture in the palette.\n• PLACE: tap the room to put it down (drag to move it). Tiles and Walls are painted with your finger.\n" +
            "• Long-press a palette picture to open it in the pixel studio (✎ PIXEL).\n" +
            "• SELECT: tap something to change it below the room: size, flip, solid, layer, text, sound. Drag to move.\n• ERASE: tap things to remove them (on the Tiles or Walls tab it clears tiles).\n" +
            "• ROOM opens the map: + makes a room next door. Walk off that edge to reach it.\n• Sounds: tap one to hear it; with something selected, it becomes that thing's sound.\n• Music: tap a track to make it this room's music.\n\n" +
            "PLAY\n• Drag anywhere to walk; tap to talk, read, shop or use.\n• Tap a palette picture to drop it in front of you.\n\n" +
            "PIXEL STUDIO\n• NEW makes a character (4 directions, 24×24, a 3-frame walk), a monster, prop, tile or item at any size.\n" +
            "• One finger draws; two fingers pinch to zoom and drag to pan. MIRROR paints both halves; ONION shows the frame before.\n" +
            "• DETAIL gives a sprite 2-4x the pixels at the same size in the world, smoothed, to paint finer detail with SOFT brushes, " +
            "opacity, SMOOTH (blends edges) and soft fills; SMOOTH EDGES rounds off jaggies. Saved as name@4x.png.\n" +
            "• IMPORT IMAGE turns any picture into a sprite: size, detail, colours, background removal, punch, outline.\n" +
            "• ▶ TO ◀ makes the left-facing row by mirroring the right. OUTLINE rims everything in the current colour.\n• SAVE puts it in your folder; the palette picks it up.\n\n" +
            "YOUR FILES\n• Put PNGs, sounds and music in the matching folders (each has a README). They show up within two seconds, even while you're here.\n" +
            "• Save over a file to update it everywhere. Files that can't be used are listed under WORLD → Problems.\n\nEverything saves by itself, and UNDO steps back.")
            .setPositiveButton("OK", null).show()
    }

    // ---- undo and saving
    private fun pushUndo(s: String) { undo.addLast(s); while (undo.size > 40) undo.removeFirst() }
    private fun doUndo() {
        val s = undo.removeLastOrNull() ?: run { toast("Nothing to undo"); return }
        val w = runCatching { World.fromJson(s) }.getOrNull() ?: return
        world = w; view.world = w; view.selected = null
        if (!w.rooms.containsKey(view.roomKey)) view.roomKey = w.rooms.keys.first()
        refreshRoomBtn(); refreshInspector(); refreshPalette(); scheduleSave()
    }
    private fun changed() { scheduleSave(); refreshPalette() }
    private val saver = Runnable { saveNow() }
    private fun scheduleSave() { main.removeCallbacks(saver); main.postDelayed(saver, 1200) }
    private fun saveNow() {
        main.removeCallbacks(saver)
        val w = world ?: return; val s = w.toJson(); val file = worldFile; val wsp = ws ?: return
        io.execute { runCatching { wsp.dir("worlds")?.let { wsp.write(it, file, "application/json", s.toByteArray()) } }.onFailure { main.post { toast("Couldn't save: ${it.message}") } } }
    }
    private fun fileFor(name: String) = name.trim().replace(Regex("[^A-Za-z0-9 _-]"), "_").ifEmpty { "world" } + ".json"
    private fun listWorlds(): List<String> = runCatching { ws?.dir("worlds")?.let { id -> ws!!.list(id).filter { it.name.endsWith(".json") }.map { it.name.removeSuffix(".json") }.sorted() } }.getOrNull() ?: emptyList()

    private fun setWorld(w: World, file: String) {
        world = w; worldFile = file; undo.clear()
        prefs.edit().putString("world", file.removeSuffix(".json")).apply()
        view.world = w; view.roomKey = w.startRoom; view.selected = null
        if (view.play) toggleMode()
        refreshRoomBtn(); refreshInspector(); refreshPalette()
    }

    private fun loadWorld(name: String?) {
        val wsp = ws ?: return
        io.execute {
            val names = runCatching { wsp.dir("worlds")?.let { id -> wsp.list(id).filter { it.name.endsWith(".json") } } }.getOrNull() ?: emptyList()
            val entry = names.firstOrNull { it.name == "$name.json" } ?: names.firstOrNull()
            var problem: String? = null
            val w = entry?.let { e -> runCatching { World.fromJson(String(wsp.read(e.id, 8L shl 20) ?: ByteArray(0))) }.onFailure { problem = "${e.name} couldn't be read (${it.message}); it was left as it is." }.getOrNull() }
            main.post {
                if (w != null && entry != null) setWorld(w, entry.name)
                else {
                    val fresh = World(if (entry == null) "My World" else "My World ${names.size + 1}").starter()
                    setWorld(fresh, fileFor(fresh.name)); saveNow()
                }
                problem?.let { lib?.problems?.put("worlds", it); toast(it) }
            }
        }
    }

    private fun renameWorld(s: String) {
        val w = world ?: return; val old = worldFile; val wsp = ws ?: return
        w.name = s; worldFile = fileFor(s)
        prefs.edit().putString("world", worldFile.removeSuffix(".json")).apply()
        saveNow()
        if (old != worldFile) io.execute { runCatching { wsp.dir("worlds")?.let { id -> wsp.list(id).firstOrNull { it.name == old }?.let { wsp.delete(it.id) } } } }
    }

    // ---- files from elsewhere on the phone, copied into the right folder
    private fun pickImport() {
        val types = when (cat) { "sounds" -> arrayOf("audio/*"); "music" -> arrayOf("audio/*"); else -> arrayOf("image/png") }
        runCatching { startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType(types[0]).putExtra(Intent.EXTRA_MIME_TYPES, types), IMPORT) }
            .onFailure { toast("No file picker on this phone") }
    }
    private fun importFile(uri: Uri) {
        val wsp = ws ?: return; val c = cat
        io.execute {
            val err = runCatching {
                var name = contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { if (it.moveToFirst()) it.getString(0) else null } ?: "file"
                val ext = name.substringAfterLast('.', "").lowercase()
                if (ext !in Library.extsOf(c)) throw IllegalStateException("${Library.folderOf(c)} takes ${Library.extsOf(c).joinToString(", ").uppercase()} files")
                name = name.substringBeforeLast('.').lowercase().replace(Regex("[^a-z0-9_]+"), "_").trim('_').ifEmpty { "file" } + "." + ext
                val limit = when (c) { "music" -> Library.MAX_MUSIC; "sounds" -> Library.MAX_SND; else -> Library.MAX_IMG }
                val bytes = contentResolver.openInputStream(uri)?.use { s -> s.readBytes() } ?: throw IllegalStateException("couldn't read it")
                if (bytes.size > limit) throw IllegalStateException("it's too big for ${Library.folderOf(c)}")
                val dir = wsp.dir(Library.folderOf(c)) ?: throw IllegalStateException("no folder")
                wsp.write(dir, name, Workspace.mimeOf(name), bytes)
                name
            }
            main.post { err.onSuccess { toast("Added ${Library.folderOf(c)}/$it") }.onFailure { toast("Not added: ${it.message}") }; lib?.rescan() }
        }
    }

    private fun onAssets(added: List<String>, removed: List<String>, first: Boolean) {
        if (!first && added.isNotEmpty()) toast("New: " + added.joinToString(", ") { it })
        if (!first && removed.isNotEmpty()) toast("Gone: " + removed.joinToString(", "))
        if (::tabs.isInitialized) refreshTabs()
        view.invalidate()
    }

    private fun ask(title: String, value: String, ok: (String) -> Unit) {
        val e = EditText(this).apply { setText(value); setSelection(value.length); inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS }
        AlertDialog.Builder(this).setTitle(title).setView(LinearLayout(this).apply { setPadding(dpi(20f), dpi(6f), dpi(20f), 0); addView(e, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)) })
            .setPositiveButton("OK") { _, _ -> e.text.toString().trim().takeIf { it.isNotEmpty() }?.let(ok) }.setNegativeButton("Cancel", null).show()
    }
    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    // ---- lifecycle
    override fun onResume() { super.onResume(); lib?.start() }
    override fun onPause() {
        super.onPause()
        lib?.stop(); saveNow()
        if (::view.isInitialized && view.play) toggleMode()
    }
    override fun onDestroy() { super.onDestroy(); lib?.release(); io.shutdown() }
    @Deprecated("Back")
    override fun onBackPressed() {
        if (studio != null) { closeStudio(); return }
        if (::view.isInitialized && view.play) { toggleMode(); return }
        @Suppress("DEPRECATION") super.onBackPressed()
    }

    /** The world map: rooms as boxes on their grid; + next to any room makes a new one there. */
    private class MapView(ctx: Context, val w: World, val here: String) : View(ctx) {
        var onPick: ((Int, Int) -> Unit)? = null
        private val f = Paint(Paint.ANTI_ALIAS_FLAG)
        private val t = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD) }
        private val minX = w.rooms.values.minOf { it.gx } - 1; private val maxX = w.rooms.values.maxOf { it.gx } + 1
        private val minY = w.rooms.values.minOf { it.gy } - 1; private val maxY = w.rooms.values.maxOf { it.gy } + 1
        private var cell = 1f; private var x0 = 0f; private var y0 = 0f
        override fun onMeasure(ws: Int, hs: Int) { val wd = MeasureSpec.getSize(ws); setMeasuredDimension(wd, (wd * 0.8f).toInt()) }
        private fun canAdd(gx: Int, gy: Int) = !w.rooms.containsKey(World.key(gx, gy)) && listOf(gx - 1 to gy, gx + 1 to gy, gx to gy - 1, gx to gy + 1).any { w.rooms.containsKey(World.key(it.first, it.second)) }
        override fun onDraw(c: Canvas) {
            val cols = maxX - minX + 1; val rows = maxY - minY + 1
            cell = minOf(width / cols.toFloat(), height / rows.toFloat()); x0 = (width - cols * cell) / 2; y0 = (height - rows * cell) / 2
            t.textSize = cell * 0.18f
            for (gy in minY..maxY) for (gx in minX..maxX) {
                val l = x0 + (gx - minX) * cell; val tp = y0 + (gy - minY) * cell
                val r = w.rooms[World.key(gx, gy)]
                if (r != null) {
                    f.color = if (r.key == here) GOLD else 0xFF5A5A70.toInt(); c.drawRoundRect(l + 4, tp + 4, l + cell - 4, tp + cell - 4, 10f, 10f, f)
                    t.color = if (r.key == here) INK else 0xFFFFFFFF.toInt()
                    c.drawText(r.name.take(10), l + cell / 2, tp + cell / 2, t)
                    if (r.key == w.startRoom) c.drawText("START", l + cell / 2, tp + cell / 2 + t.textSize * 1.3f, t)
                } else if (canAdd(gx, gy)) {
                    f.color = 0x3348C058; c.drawRoundRect(l + 8, tp + 8, l + cell - 8, tp + cell - 8, 10f, 10f, f)
                    t.color = GREEN; t.textSize = cell * 0.4f; c.drawText("+", l + cell / 2, tp + cell * 0.65f, t); t.textSize = cell * 0.18f
                }
            }
        }
        @android.annotation.SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(e: MotionEvent): Boolean {
            if (e.actionMasked == MotionEvent.ACTION_UP) {
                val gx = minX + ((e.x - x0) / cell).toInt(); val gy = minY + ((e.y - y0) / cell).toInt()
                if (gx in minX..maxX && gy in minY..maxY && (w.rooms.containsKey(World.key(gx, gy)) || canAdd(gx, gy))) onPick?.invoke(gx, gy)
            }
            return true
        }
    }
}
