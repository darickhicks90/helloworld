package app.gemmadesk.assist

import app.gemmadesk.data.Item
import app.gemmadesk.data.Page
import app.gemmadesk.llm.LanguageModel

/**
 * Step 3 of Make: once you've approved the mockup, Gemma writes the real web
 * page from it — responsive HTML and CSS with proper headings, sections and
 * form fields. The app then checks the code against the mockup (every piece
 * of text, every link, the colours), removes anything unsafe, and shows you
 * the result next to the mockup before you accept it.
 */
object Builder {

    /** A section of the page, top to bottom: one item, a row of items side by side, or a panel holding items. */
    data class Block(val items: List<Item>, val panel: Item? = null, val children: List<Block> = emptyList())

    /** Group the mockup into rows and panels, the way a person would describe it. */
    fun blocks(items: List<Item>, frameW: Int): List<Block> {
        val panels = items.filter { it.type == "box" }
        val inside = HashMap<String, Item>()   // item id → the smallest panel containing it
        for (i in items) {
            if (i.type == "box" && i.w >= frameW - 8 && i.h > 400) continue
            panels.filter { p -> p.id != i.id && i.x >= p.x - 2 && i.right() <= p.right() + 2 && i.y >= p.y - 2 && i.bottom() <= p.bottom() + 2 && p.w * p.h > i.w * i.h }
                .minByOrNull { it.w * it.h }?.let { inside[i.id] = it }
        }
        fun level(list: List<Item>, parent: Item?): List<Block> {
            val here = list.filter { inside[it.id]?.id == parent?.id }
            // rows: items whose vertical spans overlap a lot sit side by side
            val rows = ArrayList<MutableList<Item>>()
            for (i in here.sortedBy { it.y }) {
                val r = rows.lastOrNull()
                if (r != null && r.any { o -> overlapY(o, i) > 0.5 * minOf(o.h, i.h) }) r += i else rows += mutableListOf(i)
            }
            return rows.flatMap { r ->
                r.sortBy { it.x }
                if (r.size == 1) {
                    val i = r[0]
                    if (i.type == "box") listOf(Block(emptyList(), i, level(list, i))) else listOf(Block(listOf(i)))
                } else listOf(Block(r.filter { it.type != "box" }, null, r.filter { it.type == "box" }.map { Block(emptyList(), it, level(list, it)) }))
            }
        }
        return level(items, null)
    }

    private fun overlapY(a: Item, b: Item) = minOf(a.bottom(), b.bottom()) - maxOf(a.y, b.y)

    fun describe(i: Item): String {
        val t = i.text.replace("\"", "'").replace("\n", " / ")
        val style = buildList {
            if (i.type != "divider") add("${i.fontSize}px${if (i.bold) " bold" else ""}")
            if (i.type != "divider" && i.color.isNotBlank()) add("text ${i.color}")
            if (i.background.isNotBlank()) add("background ${i.background}")
            if (i.radius > 0) add("corners ${i.radius}px")
            if (i.align != "left" && i.type in setOf("heading", "text", "link")) add("${i.align}-aligned")
        }.joinToString(", ")
        val size = "${i.w}×${i.h}px at x=${i.x}"
        return when (i.type) {
            "heading" -> "heading \"$t\" ($style)"
            "text" -> "paragraph \"$t\" ($style)"
            "button" -> "button \"$t\" ($style${if (i.link.isNotBlank()) ", goes to ${i.link}" else ""}, $size)"
            "link" -> "link \"$t\" to ${i.link.ifBlank { "#" }} ($style)"
            "image" -> "image placeholder showing \"$t\" ($size, $style)"
            "input" -> "text field with placeholder \"$t\" ($size, $style)"
            "divider" -> "thin line ($style)"
            else -> "panel ($style, $size)"
        }
    }

    /** The approved mockup as a plain outline for Gemma (and for people). */
    fun outline(p: Page): String {
        val sb = StringBuilder("Page \"${p.name}\", designed ${p.frame.w}px wide (${p.frame.name.lowercase()}), page background ${p.background}.\nTop to bottom:\n")
        var n = 0
        fun walk(bs: List<Block>, indent: String) {
            for (b in bs) {
                val pre = if (indent.isEmpty()) "${++n}. " else "$indent- "
                when {
                    b.panel != null && b.items.isEmpty() && b.children.isNotEmpty() && b.panel.w >= p.frame.w - 8 ->
                        { sb.append("${pre}full-width band: ${describe(b.panel)}, containing:\n"); walk(b.children, "$indent   ") }
                    b.panel != null && b.items.isEmpty() -> { sb.append("${pre}${describe(b.panel)}${if (b.children.isNotEmpty()) ", containing:" else ""}\n"); walk(b.children, "$indent   ") }
                    b.items.size + b.children.size > 1 -> {
                        sb.append("${pre}a row of ${b.items.size + b.children.size} side by side (stack them on narrow screens):\n")
                        for (i in b.items) sb.append("$indent   - ${describe(i)}\n")
                        walk(b.children, "$indent   ")
                    }
                    else -> sb.append("$pre${describe(b.items[0])}\n")
                }
            }
        }
        walk(blocks(p.items, p.frame.w), "")
        return sb.toString()
    }

    fun prompt(p: Page, notes: String = ""): String = """
You are a careful front-end developer. Write the web page for this approved mockup.

${outline(p)}
Requirements:
- One complete HTML file: <!doctype html>, <head> with <meta charset="utf-8">, <meta name="viewport" content="width=device-width, initial-scale=1">, a <title>, and all CSS in one <style>. No JavaScript, no external files, fonts or images.
- Use the right tags: header/nav/main/section/footer, h1 for the main heading and h2 for others, p, a, button, form with label/input.
- Responsive: mobile-first, readable from 320px to 1280px wide. Content max-width about ${maxOf(p.frame.w, 960)}px, centred. Rows side by side become stacked on narrow screens (flexbox or grid with wrap).
- Keep every piece of text exactly as written, the same order, the same colours, font sizes, bold, and rounded corners.
- Image placeholders: a <div role="img" aria-label="..."> with the given background colour, the given aspect ratio, and the description as centred text.
- Font: system-ui, -apple-system, Roboto, "Segoe UI", sans-serif.
${if (notes.isNotBlank()) "- Also: $notes\n" else ""}Reply with only the HTML file, starting with <!doctype html>.
""".trim()

    /** What the app found when it checked Gemma's code against the mockup. */
    data class Result(val html: String, val missingText: List<String>, val missingLinks: List<String>, val missingColours: List<String>,
                      val removed: List<String>, val complete: Boolean) {
        val ok get() = complete && missingText.isEmpty() && missingLinks.isEmpty()
    }

    fun extractHtml(reply: String): String {
        var t = reply.trim()
        Regex("```(?:html)?\\s*\\n([\\s\\S]*?)```").find(t)?.let { t = it.groupValues[1].trim() }
        val start = Regex("<!doctype html|<html", RegexOption.IGNORE_CASE).find(t)?.range?.first ?: 0
        return t.substring(start).trim()
    }

    private fun decode(s: String) = s.replace("&nbsp;", " ").replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
        .replace("&quot;", "\"").replace("&#39;", "'").replace("&apos;", "'").replace("&rsquo;", "'").replace("&lsquo;", "'")
        .replace("&ndash;", "–").replace("&mdash;", "—").replace("&middot;", "·").replace("&hellip;", "…")
    private fun norm(s: String) = decode(s).lowercase().replace("’", "'").replace(Regex("\\s+"), " ").trim()

    /** Remove anything that could run code or load from the internet. */
    fun sanitize(html: String): Pair<String, List<String>> {
        val removed = ArrayList<String>()
        var h = html
        fun strip(re: Regex, what: String) { if (re.containsMatchIn(h)) { removed += what; h = re.replace(h, "") } }
        strip(Regex("<script[\\s\\S]*?</script\\s*>|<script[^>]*/?>", RegexOption.IGNORE_CASE), "scripts")
        strip(Regex("\\son[a-z]+\\s*=\\s*(\"[^\"]*\"|'[^']*'|[^\\s>]+)", RegexOption.IGNORE_CASE), "event handlers")
        strip(Regex("<(iframe|object|embed)[\\s\\S]*?(</\\1\\s*>|/?>)", RegexOption.IGNORE_CASE), "embedded frames")
        strip(Regex("<link[^>]*>", RegexOption.IGNORE_CASE), "external stylesheets or fonts")
        strip(Regex("@import[^;]*;", RegexOption.IGNORE_CASE), "external stylesheets or fonts")
        if (Regex("javascript:", RegexOption.IGNORE_CASE).containsMatchIn(h)) { removed += "script links"; h = Regex("javascript:[^\"'>]*", RegexOption.IGNORE_CASE).replace(h, "#") }
        // pictures or backgrounds from the internet: the page must work offline
        val remote = Regex("(src\\s*=\\s*[\"']?https?://[^\"'\\s>]*[\"']?)|(url\\(\\s*[\"']?https?://[^)]*\\))", RegexOption.IGNORE_CASE)
        if (remote.containsMatchIn(h)) { removed += "pictures loaded from the internet"; h = remote.replace(h) { m -> if (m.value.startsWith("url", true)) "none" else "src=\"\"" } }
        return h to removed.distinct()
    }

    /** Check Gemma's page against the mockup it was built from. */
    fun check(p: Page, reply: String): Result {
        val (html, removed) = sanitize(extractHtml(reply))
        val complete = Regex("</html\\s*>", RegexOption.IGNORE_CASE).containsMatchIn(html)
        // the visible text, element by element (placeholders and labels carry text too)
        val blocks = html.replace(Regex("<style[\\s\\S]*?</style>|<title[\\s\\S]*?</title>", RegexOption.IGNORE_CASE), " ")
            .replace(Regex("(placeholder|aria-label|alt|value)\\s*=\\s*\"([^\"]*)\"", RegexOption.IGNORE_CASE)) { ">${it.groupValues[2]}<" }
            .split(Regex("<[^>]*>")).map { norm(it) }.filter { it.isNotEmpty() }
        val body = blocks.joinToString(" ")
        // a short label ("Cakes") must be its own element, not just a word inside another sentence
        fun present(line: String): Boolean { val n = norm(line); return if (n.split(' ').size <= 3) blocks.any { it == n || it.trimEnd(':', '.') == n } else body.contains(n) }
        val missingText = p.items.filter { it.text.isNotBlank() && it.type != "box" }
            .flatMap { it.text.split('\n') }.map { it.trim() }.filter { it.isNotEmpty() }
            .filter { line -> !present(line) }
        val lower = html.lowercase()
        val missingLinks = p.items.map { it.link.trim() }.filter { it.isNotEmpty() && it != "#" }.distinct().filter { !lower.contains(it.lowercase()) }
        val colours = p.items.flatMap { listOf(it.background, if (it.type == "button") it.color else "") } + p.background
        val missingColours = colours.filter { it.isNotBlank() }.map { it.lowercase() }.distinct().filter { c ->
            !lower.contains(c) && !(c.length == 7 && c[1] == c[2] && c[3] == c[4] && c[5] == c[6] && lower.contains("#${c[1]}${c[3]}${c[5]}"))
        }
        return Result(html, missingText, missingLinks, missingColours, removed, complete)
    }

    /** Ask Gemma to write the page, then check it. Blocks (a few minutes on a phone). */
    fun build(model: LanguageModel, p: Page, notes: String = ""): Result =
        check(p, model.generateText(prompt(p, notes), 5000, 0f))
}
