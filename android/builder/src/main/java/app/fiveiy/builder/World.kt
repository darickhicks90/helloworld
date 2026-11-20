package app.fiveiy.builder

import org.json.JSONArray
import org.json.JSONObject

/**
 * A world: rooms on a grid (walk off an edge into the room next door), each 15 x 11 tiles of
 * 16 px (240 x 176, the hall's screen), painted with floor and wall tiles, with objects placed
 * on top. Sprites are named by folder and file name ("props/pillar"), so the world file stays
 * small and your art can change underneath it.
 */
class World(var name: String) {
    companion object {
        const val COLS = 15; const val ROWS = 11; const val TILE = 16
        const val W = COLS * TILE; const val H = ROWS * TILE
        val KIOSKS = listOf("sign", "shop", "chest", "teleport", "save")
        fun key(gx: Int, gy: Int) = "$gx,$gy"

        fun fromJson(s: String): World {
            val o = JSONObject(s)
            val w = World(o.optString("name", "World"))
            w.nextId = o.optInt("nextId", 1)
            w.startRoom = o.optString("startRoom", "0,0"); w.startX = o.optDouble("startX", 120.0).toFloat(); w.startY = o.optDouble("startY", 120.0).toFloat()
            val rooms = o.optJSONArray("rooms") ?: JSONArray()
            for (i in 0 until rooms.length()) {
                val ro = rooms.getJSONObject(i)
                val r = Room(ro.getInt("x"), ro.getInt("y")).apply { name = ro.optString("name", "Room"); music = ro.optString("music", "").ifEmpty { null } }
                val pal = ro.optJSONArray("palette") ?: JSONArray(); val tiles = ro.optJSONArray("tiles") ?: JSONArray()
                for (k in 0 until minOf(tiles.length(), COLS * ROWS)) { val ix = tiles.optInt(k, -1); r.tiles[k] = if (ix in 0 until pal.length()) pal.getString(ix) else null }
                val objs = ro.optJSONArray("objects") ?: JSONArray()
                for (k in 0 until objs.length()) {
                    val oo = objs.getJSONObject(k)
                    val ob = Obj(oo.optInt("id", w.nextId++), oo.getString("sprite"), oo.getDouble("x").toFloat(), oo.getDouble("y").toFloat())
                    ob.scale = oo.optDouble("scale", 1.0).toFloat(); ob.flip = oo.optBoolean("flip", false); ob.dir = oo.optInt("dir", 0).coerceIn(0, 3)
                    ob.solid = oo.optBoolean("solid", ob.solid); ob.layer = oo.optInt("layer", ob.layer)
                    oo.optJSONArray("lines")?.let { a -> for (j in 0 until a.length()) ob.lines += a.getString(j) }
                    ob.sound = oo.optString("sound", "").ifEmpty { null }; ob.wander = oo.optBoolean("wander", false)
                    ob.kiosk = oo.optString("kiosk", ob.kiosk); ob.item = oo.optString("item", "").ifEmpty { null }
                    ob.price = oo.optInt("price", 0); ob.target = oo.optString("target", "").ifEmpty { null }
                    r.objs += ob
                }
                w.rooms[r.key] = r
            }
            if (w.rooms.isEmpty()) w.rooms["0,0"] = Room(0, 0)
            if (w.startRoom !in w.rooms) w.startRoom = w.rooms.keys.first()
            return w
        }
    }

    class Obj(val id: Int, var sprite: String, var x: Float, var y: Float) {
        val cat get() = sprite.substringBefore('/')
        var scale = 1f
        var flip = false
        /** Which way it faces (four-direction sprites): 0 down, 1 left, 2 right, 3 up. */
        var dir = 0
        var solid = cat == "props" || cat == "kiosks" || cat == "characters"
        /** 0 on the floor (under everything), 1 standing (sorted by depth), 2 overhead. */
        var layer = if (cat == "decor") 0 else 1
        /** What it says (characters), or shows (signs, and anything else you use with A). */
        val lines = ArrayList<String>()
        var sound: String? = null
        var wander = false
        var kiosk = guessKiosk(sprite)
        var item: String? = null
        var price = 0
        var target: String? = null
        fun copyTo(o: Obj) { o.dir = dir; o.scale = scale; o.flip = flip; o.solid = solid; o.layer = layer; o.lines += lines; o.sound = sound; o.wander = wander; o.kiosk = kiosk; o.item = item; o.price = price; o.target = target }
        fun toJson() = JSONObject().apply {
            put("id", id); put("sprite", sprite); put("x", x.toDouble()); put("y", y.toDouble())
            if (scale != 1f) put("scale", scale.toDouble()); if (flip) put("flip", true); if (dir != 0) put("dir", dir)
            put("solid", solid); put("layer", layer)
            if (lines.isNotEmpty()) put("lines", JSONArray(lines)); sound?.let { put("sound", it) }; if (wander) put("wander", true)
            if (cat == "kiosks") put("kiosk", kiosk); item?.let { put("item", it) }; if (price != 0) put("price", price); target?.let { put("target", it) }
        }
    }

    class Room(val gx: Int, val gy: Int) {
        val key get() = key(gx, gy)
        var name = "Room"
        var music: String? = null
        val tiles = arrayOfNulls<String>(COLS * ROWS)
        val objs = ArrayList<Obj>()
        fun tile(cx: Int, cy: Int) = if (cx in 0 until COLS && cy in 0 until ROWS) tiles[cy * COLS + cx] else null
        fun toJson() = JSONObject().apply {
            put("x", gx); put("y", gy); put("name", name); music?.let { put("music", it) }
            val pal = tiles.filterNotNull().distinct()
            put("palette", JSONArray(pal)); put("tiles", JSONArray(tiles.map { t -> if (t == null) -1 else pal.indexOf(t) }))
            put("objects", JSONArray(objs.map { it.toJson() }))
        }
    }

    val rooms = LinkedHashMap<String, Room>()
    var startRoom = "0,0"; var startX = 120f; var startY = 120f
    var nextId = 1

    fun toJson(): String = JSONObject().apply {
        put("format", "5iy-world"); put("version", 1); put("name", name); put("nextId", nextId)
        put("startRoom", startRoom); put("startX", startX.toDouble()); put("startY", startY.toDouble())
        put("rooms", JSONArray(rooms.values.map { it.toJson() }))
    }.toString(1)

    fun newObj(sprite: String, x: Float, y: Float) = Obj(nextId++, sprite, x, y)

    /** A fresh room: marble floor (a few veined tiles), a brick wall along the top. */
    fun addRoom(gx: Int, gy: Int, name: String): Room {
        val r = Room(gx, gy); r.name = name
        val rnd = java.util.Random((gx * 31 + gy * 17).toLong())
        for (cy in 0 until ROWS) for (cx in 0 until COLS) r.tiles[cy * COLS + cx] = when (cy) {
            0 -> "walls/brick_wall"; 1 -> "walls/cornice"
            else -> if (rnd.nextInt(5) == 0) "tiles/marble_veined" else "tiles/marble"
        }
        rooms[r.key] = r
        return r
    }

    /** The world you get the first time: one room with a little of everything, to try out. */
    fun starter(): World {
        val r = addRoom(0, 0, "Great Hall")
        fun put(s: String, x: Float, y: Float) = newObj(s, x, y).also { r.objs += it }
        put("props/pillar", 40f, 80f); put("props/pillar", 200f, 80f)
        put("decor/banner", 72f, 30f).layer = 2; put("decor/banner", 168f, 30f).layer = 2
        put("decor/rug", 120f, 150f)
        put("kiosks/signpost", 120f, 76f).apply { kiosk = "sign"; lines += "WELCOME TO YOUR WORLD!"; lines += "BUILD: PICK A TAB BELOW, THEN TAP THE ROOM TO PLACE."; lines += "PLAY: DRAG TO WALK, TAP TO TALK OR USE." }
        put("characters/elder_4dir_f3", 70f, 120f).apply { lines += "AH, A BUILDER."; lines += "WALK OFF AN EDGE INTO A ROOM NEXT DOOR. MAKE ONE ON THE MAP."; wander = true; sound = "sounds/talk_blip" }
        put("kiosks/shop_stall", 190f, 128f).apply { kiosk = "shop"; item = "items/potion"; price = 3; lines += "A POTION, THREE COINS."; sound = "sounds/buy" }
        for (k in 0 until 3) put("items/coin_f4", 100f + k * 20f, 112f)
        put("props/brazier_f2", 24f, 160f); put("props/brazier_f2", 216f, 160f)
        r.music = "music/hall_theme"
        startRoom = r.key; startX = 120f; startY = 150f
        return this
    }
}

private fun guessKiosk(sprite: String): String {
    val n = sprite.substringAfter('/').lowercase()
    return when {
        "shop" in n || "stall" in n || "merchant" in n -> "shop"
        "chest" in n -> "chest"
        "teleport" in n || "portal" in n -> "teleport"
        "save" in n || "shrine" in n -> "save"
        else -> "sign"
    }
}
