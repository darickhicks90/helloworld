package app.gemmadesk.data

import app.gemmadesk.assist.Builder
import org.json.JSONObject

/**
 * Mockups in a form Claude (or any developer) can work from directly, and
 * back again: a picture of the page, a plain outline, and the exact layout as
 * JSON ("gemma-desk-mockup" format, version 1). A file in that format, for
 * example one Claude writes, can be imported as a new project.
 */
object Handoff {
    const val FORMAT = "gemma-desk-mockup"

    fun json(pr: Project, p: Page): JSONObject = JSONObject().put("format", FORMAT).put("version", 1)
        .put("project", pr.name)
        .put("page", p.copy(built = "", builtFor = 0).toJson().apply { remove("built"); remove("builtFor") })

    /** A Markdown brief: what to build, the outline, and the exact layout. Pair it with the picture. */
    fun brief(pr: Project, p: Page, request: String): String = buildString {
        append("# Mockup: ${pr.name} — ${p.name}\n\n")
        append("Made in Gemma Desk. Screen: ${p.frame.name}, ${p.frame.w} px wide. The picture shows the approved mockup; ")
        append("the JSON below gives every item's exact position (x, y), size (w, h) in CSS pixels, words and colours.\n\n")
        append("## Request\n\n${request.trim()}\n\n")
        append("## Outline (top to bottom)\n\n```\n${Builder.outline(p).trim()}\n```\n\n")
        append("## Exact layout ($FORMAT, version 1)\n\n```json\n${json(pr, p).toString(1)}\n```\n\n")
        append("Item types: heading, text, button, image (placeholder; text describes the picture), box (panel drawn behind items), ")
        append("input (text = placeholder), link, divider. Later items are drawn on top. To send a design back, reply with JSON in this same format; ")
        append("Gemma Desk can import it (Make → + New → Import a mockup).\n")
    }

    fun defaultRequest(p: Page) = "Please build this ${if (p.frame.w <= 430) "app screen" else "page"} as a responsive web page " +
        "(HTML and CSS) that matches the mockup: same text, order, colours and sizes. Stack side-by-side items on narrow screens."

    /**
     * Read a mockup from text: a brief with a ```json block, a bare
     * "gemma-desk-mockup" JSON object, or a whole Gemma Desk project.
     */
    fun parse(text: String): Project? {
        val candidates = ArrayList<String>()
        Regex("```(?:json)?\\s*\\n([\\s\\S]*?)```").findAll(text).forEach { candidates += it.groupValues[1] }
        candidates += text
        for (c in candidates) {
            val s = c.indexOf('{'); val e = c.lastIndexOf('}')
            if (s < 0 || e <= s) continue
            val o = runCatching { JSONObject(c.substring(s, e + 1)) }.getOrNull() ?: continue
            when {
                o.has("page") -> return Project(name = o.optString("project", "Imported"), pages = listOf(fresh(Page.fromJson(o.getJSONObject("page")))))
                o.has("pages") -> return Project.fromJson(o).let { pr -> pr.copy(id = newId(), pages = pr.pages.map { fresh(it) }) }
                o.has("items") -> return Project(name = "Imported", pages = listOf(fresh(Page.fromJson(o))))
            }
        }
        return null
    }

    /** New ids, sizes kept inside the frame. */
    private fun fresh(p: Page): Page {
        val w = p.frame.w
        return p.copy(id = newId(), name = p.name.ifBlank { "Home" }, built = "", builtFor = 0, items = p.items.filter { it.type in Item.TYPES }.map {
            val x = it.x.coerceIn(0, w - 8); it.copy(id = newId(), x = x, w = it.w.coerceIn(8, w - x), y = it.y.coerceAtLeast(0), h = it.h.coerceAtLeast(1))
        })
    }
}
