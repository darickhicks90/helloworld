package app.fiveiy.builder

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.hypot

/**
 * One room, drawn at the hall's resolution (240 x 176) and scaled up crisply. In Build mode you
 * paint tiles and place, drag, select and erase objects; in Play mode you walk about it
 * (drag anywhere to walk, tap to talk or use), through to the rooms next door.
 */
@SuppressLint("ViewConstructor")
class RoomView(ctx: Context, private val lib: Library) : View(ctx) {
    enum class Tool { PLACE, SELECT, ERASE }
    var world: World? = null
    var roomKey = "0,0"
    val room get() = world?.rooms?.get(roomKey)
    var tool = Tool.PLACE
    /** The palette pick ("props/pillar"), or null. */
    var brush: String? = null
    var snap = 4
    var grid = true
    var selected: World.Obj? = null
    var play = false; private set

    /** Called as an edit starts (keep an undo snapshot), when it's done, when the selection or room changes. */
    var onBegin: (() -> Unit)? = null
    var onCommit: (() -> Unit)? = null
    var onSelect: (() -> Unit)? = null
    var onRoom: (() -> Unit)? = null

    private val p = Paint().apply { isFilterBitmap = false; isAntiAlias = false }
    private val smooth = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD); color = 0xFF181820.toInt() }
    private val src = Rect(); private val dst = RectF()
    private var s = 1f; private var ox = 0f; private var oy = 0f
    private val t0 = System.nanoTime()
    private var lastNs = System.nanoTime()
    private val time get() = (System.nanoTime() - t0) / 1e9f

    // =====================================================================================
    // geometry
    // =====================================================================================
    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        s = minOf(w / World.W.toFloat(), h / World.H.toFloat())
        if (s >= 2f) s = kotlin.math.floor(s)
        ox = (w - World.W * s) / 2; oy = (h - World.H * s) / 2
    }
    private fun lx(x: Float) = (x - ox) / s
    private fun ly(y: Float) = (y - oy) / s
    private fun snapped(v: Float) = if (snap <= 1) v else (Math.round(v / snap) * snap).toFloat()

    /** Where it's drawn (its sprite's box) at this spot. */
    fun bounds(o: World.Obj, x: Float = o.x, y: Float = o.y, out: RectF = RectF()): RectF {
        val a = lib.get(o.sprite)
        val w = (a?.frameW ?: 12) * o.scale; val h = (a?.frameH ?: 12) * o.scale
        out.set(x - w / 2, y - h, x + w / 2, y); return out
    }
    /** The part of it that blocks the way: the bottom of it, where it meets the floor. */
    fun foot(o: World.Obj, x: Float = o.x, y: Float = o.y, out: RectF = RectF()): RectF {
        val a = lib.get(o.sprite)
        val w = (a?.frameW ?: 12) * o.scale; val h = (a?.frameH ?: 12) * o.scale
        val fw = if (o.cat == "characters") minOf(w * 0.6f, 10f * o.scale) else w * 0.85f
        val fh = (h * 0.3f).coerceIn(3f, 10f * o.scale)
        out.set(x - fw / 2, y - fh, x + fw / 2, y); return out
    }

    // =====================================================================================
    // drawing
    // =====================================================================================
    override fun onDraw(c: Canvas) {
        val now = System.nanoTime(); val dt = ((now - lastNs) / 1e9f).coerceIn(0f, 0.05f); lastNs = now
        if (play) step(dt)
        c.drawColor(0xFF1C1C26.toInt())
        val r = room
        if (r != null) {
            c.save(); c.translate(ox, oy); c.scale(s, s); c.clipRect(0f, 0f, World.W.toFloat(), World.H.toFloat())
            drawTiles(c, r)
            val objs = r.objs.filter { !(play && it.id in ps.taken) }
            for (o in objs.filter { it.layer == 0 }.sortedBy { it.y }) drawObj(c, o, posX(o), posY(o))
            val stand = objs.filter { it.layer == 1 }.sortedBy { posY(it) }
            var heroDrawn = !play
            for (o in stand) {
                if (!heroDrawn && posY(o) > ps.hy) { drawHero(c); heroDrawn = true }
                drawObj(c, o, posX(o), posY(o))
            }
            if (!heroDrawn) drawHero(c)
            for (o in objs.filter { it.layer == 2 }.sortedBy { it.y }) drawObj(c, o, posX(o), posY(o))
            if (!play) drawBuildOverlay(c, r) else drawPlayOverlay(c)
            c.restore()
        }
        postInvalidateOnAnimation()
    }

    private fun drawTiles(c: Canvas, r: World.Room) {
        for (cy in 0 until World.ROWS) for (cx in 0 until World.COLS) {
            val l = cx * 16f; val t = cy * 16f
            val k = r.tiles[cy * World.COLS + cx]
            if (k == null) {
                fill.color = if ((cx + cy) % 2 == 0) 0xFF2A2A36.toInt() else 0xFF24242E.toInt(); c.drawRect(l, t, l + 16, t + 16, fill); continue
            }
            val a = lib.get(k); val b = a?.bmp
            if (b == null) { missing(c, l, t, 16f, 16f); continue }
            val f = if (a.frames > 1) (time * 6).toInt() % a.frames else 0
            src.set(f * a.srcW, 0, f * a.srcW + a.srcW, a.srcH); dst.set(l, t, l + 16, t + 16)
            c.drawBitmap(b, src, dst, if (a.density > 1) smooth else p)
        }
    }

    private fun missing(c: Canvas, l: Float, t: Float, w: Float, h: Float) {
        fill.color = 0xFFE040A0.toInt(); c.drawRect(l, t, l + w, t + h, fill)
        fill.color = 0xFF401830.toInt(); c.drawRect(l + 1, t + 1, l + w - 1, t + h - 1, fill)
        text.color = 0xFFFFFFFF.toInt(); text.textSize = minOf(w, h) * 0.7f; text.textAlign = Paint.Align.CENTER
        c.drawText("?", l + w / 2, t + h * 0.75f, text); text.textAlign = Paint.Align.LEFT
    }

    private fun drawObj(c: Canvas, o: World.Obj, x: Float, y: Float, moving: Boolean = isMoving(o), flip: Boolean = o.flip && (lib.get(o.sprite)?.dirs ?: 1) == 1, dir: Int = facing(o)) {
        val a = lib.get(o.sprite); val b = a?.bmp
        bounds(o, x, y, dst)
        if (b == null) { missing(c, dst.left, dst.top, dst.width().coerceAtLeast(8f), dst.height().coerceAtLeast(8f)); return }
        if (o.layer == 1 && o.cat != "decor") {
            fill.color = 0x30000000; val w = dst.width() * 0.42f
            c.drawOval(x - w, y - 2f, x + w, y + 1.5f, fill)
        }
        val f = when {
            a.frames <= 1 -> 0
            o.cat == "characters" && !moving -> 0
            o.cat == "characters" && a.frames == 3 -> WALK3[(time * 7).toInt() % 4]   // stand, step, stand, other step
            else -> (time * 6).toInt() % a.frames
        }
        val row = if (a.dirs == 4) dir.coerceIn(0, 3) else 0
        val fh = a.srcH
        src.set(f * a.srcW, row * fh, f * a.srcW + a.srcW, row * fh + fh)
        // detail sprites are drawn smoothly; plain pixel art stays crisp
        val pp = if (a.density > 1) smooth else p
        if (flip) { c.save(); c.scale(-1f, 1f, x, 0f); c.drawBitmap(b, src, dst, pp); c.restore() } else c.drawBitmap(b, src, dst, pp)
    }

    private val selBox = RectF(); private val footBox = RectF()
    private fun drawBuildOverlay(c: Canvas, r: World.Room) {
        if (grid) {
            line.strokeWidth = 1f / s; line.color = 0x22FFFFFF
            for (x in 0..World.COLS) c.drawLine(x * 16f, 0f, x * 16f, World.H.toFloat(), line)
            for (y in 0..World.ROWS) c.drawLine(0f, y * 16f, World.W.toFloat(), y * 16f, line)
        }
        val o = selected
        if (o != null && o in r.objs) {
            bounds(o, out = selBox)
            if (o.solid) { foot(o, out = footBox); fill.color = 0x55E04848; c.drawRect(footBox, fill) }
            line.strokeWidth = 2f / s; line.color = if ((time * 3).toInt() % 2 == 0) 0xFFF0CC50.toInt() else 0xFFFFFFFF.toInt()
            c.drawRect(selBox.left - 1, selBox.top - 1, selBox.right + 1, selBox.bottom + 1, line)
            fill.color = 0xFFF0CC50.toInt(); c.drawCircle(o.x, o.y, 1.5f, fill)
        }
        if (paintCell >= 0) {
            line.strokeWidth = 1.5f / s; line.color = 0xFFF0CC50.toInt()
            val cx = paintCell % World.COLS; val cy = paintCell / World.COLS
            c.drawRect(cx * 16f, cy * 16f, cx * 16f + 16, cy * 16f + 16, line)
        }
        // doorways: which edges lead to another room
        val w = world ?: return
        fill.color = 0x6648C058
        if (w.rooms.containsKey(World.key(r.gx - 1, r.gy))) c.drawRect(0f, 0f, 2f, World.H.toFloat(), fill)
        if (w.rooms.containsKey(World.key(r.gx + 1, r.gy))) c.drawRect(World.W - 2f, 0f, World.W.toFloat(), World.H.toFloat(), fill)
        if (w.rooms.containsKey(World.key(r.gx, r.gy - 1))) c.drawRect(0f, 0f, World.W.toFloat(), 2f, fill)
        if (w.rooms.containsKey(World.key(r.gx, r.gy + 1))) c.drawRect(0f, World.H - 2f, World.W.toFloat(), World.H.toFloat(), fill)
    }

    // =====================================================================================
    // Build: touch
    // =====================================================================================
    private var dragging: World.Obj? = null
    private var dragDX = 0f; private var dragDY = 0f
    private var changed = false
    private var paintCell = -1

    private fun hit(x: Float, y: Float): World.Obj? {
        val r = room ?: return null
        val order = r.objs.filter { it.layer == 0 }.sortedBy { it.y } + r.objs.filter { it.layer == 1 }.sortedBy { it.y } + r.objs.filter { it.layer == 2 }.sortedBy { it.y }
        val b = RectF()
        return order.lastOrNull { bounds(it, out = b); b.left - 2 <= x && x <= b.right + 2 && b.top - 2 <= y && y <= b.bottom + 2 }
    }
    private val brushCat get() = brush?.substringBefore('/')
    private val tileBrush get() = brushCat == "tiles" || brushCat == "walls"
    private val objBrush get() = brushCat in Library.SPRITES && !tileBrush

    private fun paintAt(x: Float, y: Float, k: String?) {
        val r = room ?: return
        val cx = (x / 16).toInt(); val cy = (y / 16).toInt()
        if (cx !in 0 until World.COLS || cy !in 0 until World.ROWS) return
        val i = cy * World.COLS + cx; paintCell = i
        if (r.tiles[i] != k) { r.tiles[i] = k; changed = true }
    }

    private fun buildTouch(e: MotionEvent) {
        val r = room ?: return
        val x = lx(e.x); val y = ly(e.y)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                changed = false; dragging = null; paintCell = -1
                onBegin?.invoke()
                when {
                    tool == Tool.ERASE -> eraseAt(x, y)
                    tool == Tool.PLACE && tileBrush -> paintAt(x, y, brush)
                    tool == Tool.PLACE && objBrush -> {
                        val w = world ?: return
                        val o = w.newObj(brush!!, snapped(x).coerceIn(0f, World.W.toFloat()), snapped(y).coerceIn(4f, World.H.toFloat()))
                        if (o.cat == "characters" && lib.get("sounds/talk_blip") != null) o.sound = "sounds/talk_blip"
                        if (o.cat == "kiosks" && o.kiosk == "shop") { o.price = 3; o.item = lib.list("items").firstOrNull { "coin" !in it.name }?.key }
                        r.objs += o; selected = o; dragging = o; dragDX = 0f; dragDY = 0f; changed = true
                        onSelect?.invoke()
                    }
                    else -> {
                        val h = hit(x, y)
                        selected = h; onSelect?.invoke()
                        if (h != null) { dragging = h; dragDX = h.x - x; dragDY = h.y - y }
                    }
                }
            }
            MotionEvent.ACTION_MOVE -> when {
                tool == Tool.ERASE -> eraseAt(x, y)
                tool == Tool.PLACE && tileBrush -> paintAt(x, y, brush)
                else -> dragging?.let { o ->
                    val nx = snapped(x + dragDX).coerceIn(0f, World.W.toFloat()); val ny = snapped(y + dragDY).coerceIn(2f, World.H.toFloat())
                    if (nx != o.x || ny != o.y) { o.x = nx; o.y = ny; changed = true }
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                dragging = null; paintCell = -1
                if (changed) onCommit?.invoke()
                changed = false
            }
        }
    }

    private fun eraseAt(x: Float, y: Float) {
        val r = room ?: return
        if (tileBrush) { paintAt(x, y, null); return }
        val h = hit(x, y) ?: return
        r.objs.remove(h); if (selected === h) { selected = null; onSelect?.invoke() }
        changed = true
    }

    // =====================================================================================
    // Play
    // =====================================================================================
    class PlayState {
        var hx = 120f; var hy = 150f; var face = 1; var dir = 0; var moving = false
        var coins = 0
        val bag = LinkedHashMap<String, Int>()
        val taken = HashSet<Int>(); val opened = HashSet<Int>()
        val npc = HashMap<Int, FloatArray>()   // id → offset x, y, target x, y, timer
        var dialog: List<String>? = null; var dialogLine = 0
        var msg = ""; var msgT = 0f
        var stepT = 0f
        var respawnRoom = ""; var respawnX = 0f; var respawnY = 0f
    }
    var ps = PlayState(); private set
    private var joy = false; private var jx0 = 0f; private var jy0 = 0f; private var jx = 0f; private var jy = 0f
    private var downT = 0L

    fun startPlay() {
        val w = world ?: return
        play = true; selected = null; ps = PlayState()
        if (roomKey == w.startRoom) { ps.hx = w.startX; ps.hy = w.startY } else { ps.hx = 120f; ps.hy = 150f }
        if (blocked(ps.hx, ps.hy)) freeSpot()
        ps.respawnRoom = roomKey; ps.respawnX = ps.hx; ps.respawnY = ps.hy
        lib.music(room?.music)
        say("DRAG TO WALK. TAP TO TALK OR USE.")
        onSelect?.invoke()
    }
    fun stopPlay() { play = false; joy = false; lib.music(null) }

    private fun say(m: String) { ps.msg = m; ps.msgT = 2.4f }

    /** Drop something from the palette just in front of you (it's added to the room). */
    fun spawnHere(key: String): World.Obj? {
        val w = world ?: return null; val r = room ?: return null
        val o = w.newObj(key, (ps.hx + ps.face * 18f).coerceIn(4f, World.W - 4f), ps.hy.coerceIn(8f, World.H.toFloat()))
        if (o.cat == "characters" && lib.get("sounds/talk_blip") != null) o.sound = "sounds/talk_blip"
        r.objs += o; say("+ " + label(key)); return o
    }

    /** Characters face where they walk (in play), or the way you set them. */
    private val walkDir = HashMap<Int, Int>()
    private fun facing(o: World.Obj): Int {
        if (!play) return o.dir
        val st = ps.npc[o.id]
        if (st != null && isMoving(o)) return walkDir[o.id] ?: o.dir
        // someone talking to you turns to face you
        if (ps.dialog != null && talkingTo == o.id) { val dx = ps.hx - posX(o); val dy = ps.hy - posY(o); return if (abs(dx) > abs(dy)) (if (dx < 0) 1 else 2) else (if (dy < 0) 3 else 0) }
        return o.dir
    }
    private var talkingTo = -1
    private fun posX(o: World.Obj) = if (play) o.x + (ps.npc[o.id]?.get(0) ?: 0f) else o.x
    private fun posY(o: World.Obj) = if (play) o.y + (ps.npc[o.id]?.get(1) ?: 0f) else o.y
    private fun isMoving(o: World.Obj) = play && o.wander && (ps.npc[o.id]?.let { abs(it[0] - it[2]) + abs(it[1] - it[3]) > 0.5f } ?: false)

    private val heroKey get() = listOf("characters/player_4dir_f3", "characters/player_4dir_f2", "characters/player_4dir", "characters/player_f2", "characters/player").firstOrNull { lib.get(it) != null }
        ?: lib.list("characters").firstOrNull { it.name.startsWith("player") }?.key
    private val heroObj = World.Obj(-1, "characters/player", 0f, 0f)
    private fun drawHero(c: Canvas) {
        heroObj.sprite = heroKey ?: "characters/player"
        val four = (lib.get(heroObj.sprite)?.dirs ?: 1) == 4
        drawObj(c, heroObj, ps.hx, ps.hy, ps.moving, !four && ps.face < 0, ps.dir)
    }

    private val tmp = RectF()
    private fun blocked(x: Float, y: Float, self: World.Obj? = null): Boolean {
        val r = room ?: return true
        val l = x - 4; val rr = x + 4; val t = y - 3; val b = y
        val w = world
        if (l < 0 && !(w?.rooms?.containsKey(World.key(r.gx - 1, r.gy)) ?: false)) return true
        if (rr > World.W && !(w?.rooms?.containsKey(World.key(r.gx + 1, r.gy)) ?: false)) return true
        if (t < 0 && !(w?.rooms?.containsKey(World.key(r.gx, r.gy - 1)) ?: false)) return true
        if (b > World.H && !(w?.rooms?.containsKey(World.key(r.gx, r.gy + 1)) ?: false)) return true
        for (cy in (t / 16).toInt()..(b / 16).toInt()) for (cx in (l / 16).toInt()..(rr / 16).toInt()) {
            if (cx !in 0 until World.COLS || cy !in 0 until World.ROWS) continue
            val k = r.tiles[cy * World.COLS + cx]
            if (k == null || k.startsWith("walls/")) return true
        }
        for (o in r.objs) {
            if (!o.solid || o === self || o.id in ps.taken || o.cat == "items") continue
            foot(o, posX(o), posY(o), tmp)
            if (rr > tmp.left && l < tmp.right && b > tmp.top && t < tmp.bottom) return true
        }
        return false
    }
    private fun freeSpot() {
        for (rad in 0..120 step 6) for (k in 0 until 16) {
            val a = k * Math.PI / 8; val x = (120 + Math.cos(a) * rad).toFloat(); val y = (120 + Math.sin(a) * rad * 0.6).toFloat()
            if (x in 6f..234f && y in 8f..172f && !blocked(x, y)) { ps.hx = x; ps.hy = y; return }
        }
    }

    private fun step(dt: Float) {
        val w = world ?: return; val r = room ?: return
        ps.msgT -= dt
        // walking
        var mx = 0f; var my = 0f
        if (joy && ps.dialog == null) {
            val dx = jx - jx0; val dy = jy - jy0; val d = hypot(dx, dy); val dead = 8f * resources.displayMetrics.density
            if (d > dead) { val k = minOf(1f, (d - dead) / (40f * resources.displayMetrics.density)); mx = dx / d * k; my = dy / d * k }
        }
        ps.moving = mx != 0f || my != 0f
        if (ps.moving) {
            val sp = 62f * dt
            val nx = ps.hx + mx * sp; val ny = ps.hy + my * sp
            if (!blocked(nx, ps.hy)) ps.hx = nx
            if (!blocked(ps.hx, ny)) ps.hy = ny
            if (abs(mx) > 0.2f) ps.face = if (mx > 0) 1 else -1
            ps.dir = if (abs(mx) > abs(my)) (if (mx < 0) 1 else 2) else (if (my < 0) 3 else 0)
            ps.stepT -= dt; if (ps.stepT <= 0f) { ps.stepT = 0.32f; lib.play("sounds/step", 0.25f) }
        }
        // through an edge into the room next door
        when {
            ps.hx < 0 -> goRoom(World.key(r.gx - 1, r.gy), World.W - 6f, ps.hy)
            ps.hx > World.W -> goRoom(World.key(r.gx + 1, r.gy), 6f, ps.hy)
            ps.hy < 2 -> goRoom(World.key(r.gx, r.gy - 1), ps.hx, World.H - 4f)
            ps.hy > World.H + 2 -> goRoom(World.key(r.gx, r.gy + 1), ps.hx, 14f)
        }
        // picking things up
        for (o in room?.objs ?: emptyList()) {
            if (o.cat != "items" || o.id in ps.taken) continue
            if (abs(o.x - ps.hx) < 8f + 3f * o.scale && abs((o.y - 3) - (ps.hy - 3)) < 7f + 3f * o.scale) {
                ps.taken += o.id
                val coin = "coin" in o.sprite
                if (coin) ps.coins++ else ps.bag[o.sprite] = (ps.bag[o.sprite] ?: 0) + 1
                lib.play(o.sound ?: if (coin && lib.get("sounds/coin") != null) "sounds/coin" else "sounds/pickup")
                say(if (coin) "+1 COIN" else "GOT " + label(o.sprite))
            }
        }
        // characters wandering about their spot
        for (o in room?.objs ?: emptyList()) {
            if (!o.wander || o.cat != "characters") continue
            val st = ps.npc.getOrPut(o.id) { floatArrayOf(0f, 0f, 0f, 0f, 1f) }
            st[4] -= dt
            if (st[4] <= 0f) { st[4] = 1.5f + Math.random().toFloat() * 2.5f; st[2] = (Math.random().toFloat() - 0.5f) * 44f; st[3] = (Math.random().toFloat() - 0.5f) * 26f }
            if (ps.dialog != null) continue
            val dx = st[2] - st[0]; val dy = st[3] - st[1]; val d = hypot(dx, dy)
            if (d > 0.5f) {
                val sp = minOf(d, 16f * dt); val nx = st[0] + dx / d * sp; val ny = st[1] + dy / d * sp
                if (!blocked(o.x + nx, o.y + ny, o) && hypot(o.x + nx - ps.hx, o.y + ny - ps.hy) > 9f) {
                    st[0] = nx; st[1] = ny
                    walkDir[o.id] = if (abs(dx) > abs(dy)) (if (dx < 0) 1 else 2) else (if (dy < 0) 3 else 0)
                } else { st[2] = st[0]; st[3] = st[1] }
            }
        }
        if (w.rooms.isEmpty()) return
    }

    private fun goRoom(key: String, x: Float, y: Float) {
        val w = world ?: return
        if (!w.rooms.containsKey(key)) { ps.hx = ps.hx.coerceIn(4f, World.W - 4f); ps.hy = ps.hy.coerceIn(4f, World.H.toFloat()); return }
        roomKey = key; ps.hx = x; ps.hy = y
        if (blocked(x, y)) freeSpot()
        room?.music?.let { lib.music(it) }
        lib.play("sounds/door", 0.5f)
        onRoom?.invoke()
    }

    /** Tap: next line of talk, or use whatever is in reach. */
    private fun interact() {
        ps.dialog?.let { d -> if (ps.dialogLine < d.size - 1) { ps.dialogLine++; lib.play("sounds/talk_blip", 0.5f) } else ps.dialog = null; return }
        val r = room ?: return
        val o = r.objs.filter { it.id !in ps.taken && it.cat != "items" && (it.cat == "characters" || it.cat == "kiosks" || it.lines.isNotEmpty()) }
            .minByOrNull { hypot(posX(it) - ps.hx, posY(it) - 2 - ps.hy) }
            ?.takeIf { hypot(posX(it) - ps.hx, posY(it) - 2 - ps.hy) < 22f + 6f * it.scale } ?: return
        fun talk(lines: List<String>) { if (lines.isNotEmpty()) { ps.dialog = lines; ps.dialogLine = 0 } }
        if (o.cat != "kiosks") { talkingTo = o.id; lib.play(o.sound ?: "sounds/talk_blip"); talk(o.lines.ifEmpty { listOf("...") }); return }
        when (o.kiosk) {
            "shop" -> {
                val item = o.item
                if (item == null) { say("NOTHING FOR SALE"); return }
                if (ps.coins >= o.price) {
                    ps.coins -= o.price; ps.bag[item] = (ps.bag[item] ?: 0) + 1
                    lib.play(o.sound ?: "sounds/buy"); say("BOUGHT " + label(item) + (if (o.price > 0) " FOR ${o.price}" else ""))
                    talk(o.lines)
                } else { lib.play("sounds/error"); say("${label(item)}: ${o.price} COINS. YOU HAVE ${ps.coins}.") }
            }
            "chest" -> {
                if (o.id in ps.opened) { say("EMPTY"); return }
                ps.opened += o.id
                val item = o.item
                if (item == null || "coin" in item) { val n = maxOf(1, o.price); ps.coins += n; say("FOUND $n COIN" + if (n > 1) "S!" else "!") }
                else { ps.bag[item] = (ps.bag[item] ?: 0) + 1; say("FOUND " + label(item) + "!") }
                lib.play(o.sound ?: "sounds/chest_open"); talk(o.lines)
            }
            "teleport" -> {
                val w = world ?: return; val t = o.target
                if (t == null || !w.rooms.containsKey(t)) { lib.play("sounds/error"); say("IT HUMS, BUT GOES NOWHERE"); return }
                lib.play(o.sound ?: "sounds/teleport")
                roomKey = t
                val pad = room?.objs?.firstOrNull { it.cat == "kiosks" && it.kiosk == "teleport" }
                if (pad != null) { ps.hx = pad.x; ps.hy = pad.y + 10f } else { ps.hx = 120f; ps.hy = 120f }
                if (blocked(ps.hx, ps.hy)) freeSpot()
                room?.music?.let { lib.music(it) }
                say(room?.name?.uppercase() ?: ""); onRoom?.invoke()
            }
            "save" -> { ps.respawnRoom = roomKey; ps.respawnX = ps.hx; ps.respawnY = ps.hy; lib.play(o.sound ?: "sounds/save"); say("SAVED. YOU'LL START HERE."); talk(o.lines) }
            else -> { lib.play(o.sound ?: "sounds/talk_blip", 0.6f); talk(o.lines.ifEmpty { listOf("(IT'S BLANK)") }) }
        }
    }

    private fun drawPlayOverlay(c: Canvas) {
        // coins and the bag
        text.textSize = 7f; text.textAlign = Paint.Align.LEFT
        fill.color = 0xCC181820.toInt(); c.drawRoundRect(3f, 3f, 60f, 14f, 3f, 3f, fill)
        fill.color = 0xFFF0CC50.toInt(); c.drawCircle(9f, 8.5f, 3f, fill)
        text.color = 0xFFFFFFFF.toInt(); c.drawText("${ps.coins}  BAG ${ps.bag.values.sum()}", 15f, 11f, text)
        if (ps.msgT > 0f && ps.msg.isNotEmpty()) {
            text.textSize = 7f; val tw = text.measureText(ps.msg)
            fill.color = 0xE0181820.toInt(); c.drawRoundRect(120 - tw / 2 - 5, 18f, 120 + tw / 2 + 5, 30f, 3f, 3f, fill)
            text.color = 0xFFFFFFFF.toInt(); text.textAlign = Paint.Align.CENTER; c.drawText(ps.msg, 120f, 27f, text); text.textAlign = Paint.Align.LEFT
        }
        val d = ps.dialog
        if (d != null) {
            val top = World.H - 46f
            fill.color = 0xFF181820.toInt(); c.drawRect(4f, top, World.W - 4f, World.H - 4f, fill)
            fill.color = 0xFFF8F8F8.toInt(); c.drawRect(6f, top + 2, World.W - 6f, World.H - 6f, fill)
            text.color = 0xFF181820.toInt(); text.textSize = 7.5f
            wrap(d[ps.dialogLine].uppercase(), World.W - 24f).take(4).forEachIndexed { i, s0 -> c.drawText(s0, 11f, top + 12 + i * 9f, text) }
            if ((time * 2).toInt() % 2 == 0) { fill.color = 0xFF181820.toInt(); c.drawRect(World.W - 16f, World.H - 13f, World.W - 11f, World.H - 11f, fill) }
        }
        if (joy) {
            val ax = lx(jx0); val ay = ly(jy0)
            line.color = 0x66FFFFFF; line.strokeWidth = 1.5f / s; c.drawCircle(ax, ay, 16f, line)
            fill.color = 0x88FFFFFF.toInt()
            val dx = lx(jx) - ax; val dy = ly(jy) - ay; val dd = hypot(dx, dy).coerceAtLeast(0.01f); val k = minOf(dd, 16f) / dd
            c.drawCircle(ax + dx * k, ay + dy * k, 6f, fill)
        }
    }
    private fun wrap(s0: String, max: Float): List<String> {
        val out = ArrayList<String>(); var cur = ""
        for (word in s0.split(' ')) {
            val next = if (cur.isEmpty()) word else "$cur $word"
            if (text.measureText(next) <= max || cur.isEmpty()) cur = next else { out += cur; cur = word }
        }
        if (cur.isNotEmpty()) out += cur
        return out
    }

    private fun playTouch(e: MotionEvent) {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { jx0 = e.x; jy0 = e.y; jx = e.x; jy = e.y; joy = true; downT = System.currentTimeMillis() }
            MotionEvent.ACTION_MOVE -> { jx = e.x; jy = e.y }
            MotionEvent.ACTION_UP -> {
                val tap = hypot(e.x - jx0, e.y - jy0) < 12f * resources.displayMetrics.density && System.currentTimeMillis() - downT < 350
                joy = false
                if (tap) interact()
            }
            MotionEvent.ACTION_CANCEL -> joy = false
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (play) playTouch(e) else buildTouch(e)
        return true
    }

    companion object {
        fun label(key: String) = key.substringAfter('/').replace(Regex("_f\\d+$"), "").replace("_4dir", "").replace('_', ' ').uppercase()
        val WALK3 = intArrayOf(1, 0, 2, 0)
    }
}
