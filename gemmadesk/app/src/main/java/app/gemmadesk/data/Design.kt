package app.gemmadesk.data

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * A page design: a frame (the screen size) and the items on it, each a
 * rectangle in CSS pixels. The design screen edits exactly this, and the
 * HTML is generated from exactly this, so what you lay out is what the
 * browser shows.
 */
data class Item(
    val id: String = newId(),
    val type: String,          // see TYPES
    val x: Int, val y: Int, val w: Int, val h: Int,
    val text: String = "",
    val fontSize: Int = 16,
    val bold: Boolean = false,
    val color: String = "#1F2430",
    val background: String = "",   // "" = transparent
    val radius: Int = 0,
    val align: String = "left",    // left | center | right
    val link: String = "",
) {
    fun toJson() = JSONObject().put("id", id).put("type", type).put("x", x).put("y", y).put("w", w).put("h", h)
        .put("text", text).put("fontSize", fontSize).put("bold", bold).put("color", color).put("background", background)
        .put("radius", radius).put("align", align).put("link", link)
    fun right() = x + w
    fun bottom() = y + h

    companion object {
        /** What can go on a page. */
        val TYPES = listOf("heading", "text", "button", "image", "box", "input", "link", "divider")
        val LABELS = mapOf("heading" to "Heading", "text" to "Text", "button" to "Button", "image" to "Image", "box" to "Box",
            "input" to "Text field", "link" to "Link", "divider" to "Line")

        fun fromJson(o: JSONObject) = Item(o.optString("id").ifEmpty { newId() }, o.optString("type", "box"),
            o.optInt("x"), o.optInt("y"), o.optInt("w", 100), o.optInt("h", 40), o.optString("text"), o.optInt("fontSize", 16),
            o.optBoolean("bold"), o.optString("color", "#1F2430"), o.optString("background"), o.optInt("radius"),
            o.optString("align", "left"), o.optString("link"))

        /** A sensible new item of a type, placed at (x, y). */
        fun create(type: String, x: Int, y: Int, frameW: Int): Item = when (type) {
            "heading" -> Item(type = type, x = x, y = y, w = minOf(320, frameW - x - 16), h = 44, text = "Heading", fontSize = 28, bold = true)
            "text" -> Item(type = type, x = x, y = y, w = minOf(320, frameW - x - 16), h = 72, text = "Write something here. Tap to edit the words.", fontSize = 16)
            "button" -> Item(type = type, x = x, y = y, w = 160, h = 48, text = "Button", fontSize = 16, bold = true, color = "#FFFFFF", background = "#3D5AFE", radius = 10, align = "center")
            "image" -> Item(type = type, x = x, y = y, w = minOf(320, frameW - x - 16), h = 180, text = "Image", background = "#DDE3EE", radius = 8, align = "center", color = "#6B7489")
            "box" -> Item(type = type, x = x, y = y, w = minOf(320, frameW - x - 16), h = 120, background = "#F2F4F8", radius = 12)
            "input" -> Item(type = type, x = x, y = y, w = minOf(280, frameW - x - 16), h = 44, text = "Your email", fontSize = 15, color = "#6B7489", background = "#FFFFFF", radius = 8)
            "link" -> Item(type = type, x = x, y = y, w = 120, h = 24, text = "Learn more", fontSize = 16, color = "#3D5AFE", link = "#")
            "divider" -> Item(type = type, x = x, y = y, w = minOf(320, frameW - x - 16), h = 2, background = "#D5DAE3")
            else -> Item(type = "box", x = x, y = y, w = 120, h = 80, background = "#F2F4F8")
        }
    }
}

data class Frame(val name: String, val w: Int, val h: Int) {
    companion object {
        val PHONE = Frame("Phone", 390, 844)
        val TABLET = Frame("Tablet", 820, 1180)
        val DESKTOP = Frame("Desktop", 1280, 800)
        val ALL = listOf(PHONE, TABLET, DESKTOP)
    }
}

data class Page(
    val id: String = newId(),
    val name: String,
    val frame: Frame = Frame.PHONE,
    val background: String = "#FFFFFF",
    val items: List<Item> = emptyList(),
    /** The page Gemma built from the approved mockup (step 3), or "" if not built yet. */
    val built: String = "",
    /** Which version of the mockup [built] was made from. */
    val builtFor: Int = 0,
) {
    /** Changes whenever the mockup changes, so an old build can be spotted. */
    fun mockupKey(): Int = (items.map { it.copy(id = "") }.hashCode() * 31 + background.hashCode()) * 31 + frame.w
    fun builtIsCurrent() = built.isNotEmpty() && builtFor == mockupKey()

    /** The page grows to fit its lowest item. */
    fun height() = maxOf(frame.h, (items.maxOfOrNull { it.bottom() } ?: 0) + 24)

    fun toJson() = JSONObject().put("id", id).put("name", name).put("frame", JSONObject().put("name", frame.name).put("w", frame.w).put("h", frame.h))
        .put("background", background).put("items", JSONArray().apply { items.forEach { put(it.toJson()) } })
        .put("built", built).put("builtFor", builtFor)

    companion object {
        fun fromJson(o: JSONObject): Page {
            val f = o.optJSONObject("frame")
            val frame = if (f == null) Frame.PHONE else Frame(f.optString("name", "Custom"), f.optInt("w", 390), f.optInt("h", 844))
            val a = o.optJSONArray("items") ?: JSONArray()
            return Page(o.optString("id").ifEmpty { newId() }, o.optString("name", "Page"), frame, o.optString("background", "#FFFFFF"),
                (0 until a.length()).mapNotNull { runCatching { Item.fromJson(a.getJSONObject(it)) }.getOrNull() },
                o.optString("built"), o.optInt("builtFor"))
        }
    }
}

/** A website or app: a few pages. */
data class Project(val id: String = newId(), val name: String, val pages: List<Page>) {
    fun toJson() = JSONObject().put("id", id).put("name", name).put("pages", JSONArray().apply { pages.forEach { put(it.toJson()) } })
    companion object {
        fun fromJson(o: JSONObject): Project {
            val a = o.optJSONArray("pages") ?: JSONArray()
            return Project(o.optString("id").ifEmpty { newId() }, o.optString("name", "Untitled"), (0 until a.length()).map { Page.fromJson(a.getJSONObject(it)) })
        }
    }
}

class Projects(private val dir: File) {
    init { dir.mkdirs() }
    fun all(): List<Project> = (dir.listFiles { f -> f.name.endsWith(".json") } ?: emptyArray())
        .sortedByDescending { it.lastModified() }
        .mapNotNull { f -> runCatching { Project.fromJson(JSONObject(f.readText())) }.getOrNull() }
    fun save(p: Project) = File(dir, "${p.id}.json").writeAtomically(p.toJson().toString(1))
    fun delete(p: Project) { File(dir, "${p.id}.json").delete() }
}

/* ======================= HTML ======================= */

object Html {
    private val COLOR = Regex("^#([0-9a-fA-F]{3}|[0-9a-fA-F]{6}|[0-9a-fA-F]{8})$")
    fun safeColor(c: String, fallback: String = "transparent") = if (COLOR.matches(c.trim())) c.trim() else fallback
    fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
    private fun safeLink(s: String): String {
        val t = s.trim()
        return if (t.isEmpty()) "#" else if (t.startsWith("http://") || t.startsWith("https://") || t.startsWith("#") || t.startsWith("mailto:") || t.endsWith(".html")) esc(t) else "#"
    }
    fun pageFile(p: Page) = p.name.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').ifEmpty { "page" } + ".html"

    /**
     * The page as a self-contained HTML file. Items are placed at the exact
     * positions and sizes from the design (CSS pixels), inside a page as wide
     * as the frame; on a narrower screen the whole page scales down to fit,
     * so it keeps the layout you made.
     */
    fun page(p: Page, project: Project? = null): String {
        val sb = StringBuilder()
        sb.append("<!doctype html>\n<html lang=\"en\">\n<head>\n<meta charset=\"utf-8\">\n")
        sb.append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">\n")
        sb.append("<title>${esc(project?.let { "${p.name} – ${it.name}" } ?: p.name)}</title>\n<style>\n")
        sb.append("html,body{margin:0;padding:0;background:${safeColor(p.background, "#FFFFFF")};}\n")
        sb.append("body{font-family:Roboto,'Segoe UI',Helvetica,Arial,sans-serif;-webkit-text-size-adjust:100%;}\n")
        sb.append(".page{position:relative;width:${p.frame.w}px;height:${p.height()}px;margin:0 auto;transform-origin:top left;}\n")
        sb.append(".it{position:absolute;box-sizing:border-box;margin:0;overflow-wrap:break-word;line-height:1.3;}\n")
        sb.append(".btn{display:flex;align-items:center;justify-content:center;border:none;cursor:pointer;font-family:inherit;text-decoration:none;}\n")
        sb.append(".img{display:flex;align-items:center;justify-content:center;}\n")
        sb.append(".in{border:1px solid #C9D0DC;padding:0 12px;font:inherit;}\n")
        sb.append("</style>\n</head>\n<body>\n<div class=\"page\" id=\"page\">\n")
        for (it in p.items) sb.append("  ").append(item(it)).append('\n')
        sb.append("</div>\n<script>\n")
        // keep the layout on screens narrower than the design: scale the page to fit
        sb.append("(function(){var p=document.getElementById('page'),w=${p.frame.w},h=${p.height()};function fit(){var s=Math.min(1,document.documentElement.clientWidth/w);")
        sb.append("p.style.transform=s<1?'scale('+s+')':'';p.style.marginLeft=s<1?'0':'';document.body.style.height=s<1?(h*s)+'px':'';}window.addEventListener('resize',fit);fit();})();\n")
        sb.append("</script>\n</body>\n</html>\n")
        return sb.toString()
    }

    private fun item(i: Item): String {
        val st = StringBuilder("left:${i.x}px;top:${i.y}px;width:${i.w}px;height:${i.h}px;")
        if (i.type != "divider") st.append("font-size:${i.fontSize}px;color:${safeColor(i.color, "#1F2430")};text-align:${if (i.align in setOf("left", "center", "right")) i.align else "left"};")
        if (i.bold) st.append("font-weight:700;")
        if (i.background.isNotBlank()) st.append("background:${safeColor(i.background)};")
        if (i.radius > 0) st.append("border-radius:${i.radius}px;")
        val text = esc(i.text).replace("\n", "<br>")
        val s = "style=\"$st\""
        return when (i.type) {
            "heading" -> "<h2 class=\"it\" $s>$text</h2>"
            "text" -> "<p class=\"it\" $s>$text</p>"
            // a button with a link is a link that looks like a button (no script needed)
            "button" -> if (i.link.isNotBlank()) "<a class=\"it btn\" href=\"${safeLink(i.link)}\" $s>$text</a>" else "<button class=\"it btn\" $s>$text</button>"
            "image" -> "<div class=\"it img\" role=\"img\" aria-label=\"$text\" $s>$text</div>"
            "input" -> "<input class=\"it in\" placeholder=\"$text\" $s>"
            "link" -> "<a class=\"it\" href=\"${safeLink(i.link)}\" $s>$text</a>"
            "divider" -> "<hr class=\"it\" style=\"left:${i.x}px;top:${i.y}px;width:${i.w}px;height:${i.h}px;border:none;background:${safeColor(i.background, "#D5DAE3")};\">"
            else -> "<div class=\"it\" $s>$text</div>"
        }
    }
}
