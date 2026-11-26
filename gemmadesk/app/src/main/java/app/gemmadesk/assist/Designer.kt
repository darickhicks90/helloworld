package app.gemmadesk.assist

import app.gemmadesk.data.Html
import app.gemmadesk.data.Item
import app.gemmadesk.data.Page
import app.gemmadesk.llm.LanguageModel
import app.gemmadesk.llm.extractJson
import org.json.JSONArray
import org.json.JSONObject

/**
 * Gemma as a layout designer. It returns a list of items (type, position,
 * size, words, colours) – the same thing the design screen edits – never
 * HTML. The app then tidies the layout so it fits the frame, and makes the
 * HTML itself.
 */
class Designer(private val model: LanguageModel) {
    companion object {
        val SCHEMA = """
{"type":"object","properties":{
 "background":{"type":"string"},
 "items":{"type":"array","items":{"type":"object","properties":{
   "id":{"type":"string"},
   "type":{"type":"string","enum":["heading","text","button","image","box","input","link","divider"]},
   "x":{"type":"integer"},"y":{"type":"integer"},"w":{"type":"integer"},"h":{"type":"integer"},
   "text":{"type":"string"},"fontSize":{"type":"integer"},"bold":{"type":"boolean"},
   "color":{"type":"string"},"background":{"type":"string"},"radius":{"type":"integer"},
   "align":{"type":"string","enum":["left","center","right"]}},
   "required":["type","x","y","w","h","text"]}}},
"required":["background","items"]}
""".trimIndent()

        private const val RULES = """
Item types: heading, text (a paragraph), button, image (a placeholder; "text" describes the picture), box (a coloured panel drawn behind other items), input (a text field; "text" is its placeholder), link, divider (a thin line).
Rules:
- x, y, w, h are CSS pixels from the top-left of the page. Every item must fit inside the page width.
- Keep a 16 to 24 px margin at the sides, line items up, and leave at least 12 px between items.
- Put boxes BEFORE the items that sit on top of them (later items are drawn on top).
- Make a text item tall enough for its words: about 1.3 × fontSize per line.
- Colours are hex like #1F2430. Use a small, consistent palette with good contrast (dark text on light backgrounds, light text on dark ones).
- Write real, useful words for the page, not lorem ipsum.
"""

        fun newPrompt(description: String, page: Page) = """
You design web pages and app screens. Lay out this page as a list of items.
Page: "${page.name}", ${page.frame.w} px wide (${page.frame.name.lowercase()} screen), about ${page.frame.h} px tall (it can be taller if needed).
$RULES
The user wants: $description
""".trim()

        fun editPrompt(instruction: String, page: Page) = """
You design web pages and app screens. Here is the current page as JSON, ${page.frame.w} px wide:
${compact(page)}
$RULES
Change the page as the user asks, and return the WHOLE page (every item, changed or not). Keep each unchanged item's "id", position and size exactly.
The user wants: $instruction
""".trim()

        /** The page as short JSON for the prompt. */
        fun compact(p: Page): String = JSONObject().put("background", p.background).put("items", JSONArray().apply {
            for (i in p.items) put(JSONObject().put("id", i.id).put("type", i.type).put("x", i.x).put("y", i.y).put("w", i.w).put("h", i.h)
                .put("text", i.text).put("fontSize", i.fontSize).put("bold", i.bold).put("color", i.color).put("background", i.background)
                .put("radius", i.radius).put("align", i.align))
        }).toString()

        /**
         * Make Gemma's layout safe and tidy: known types only, valid colours,
         * everything inside the frame, sizes on a 4 px grid, sensible limits.
         */
        fun tidy(o: JSONObject, page: Page): Page {
            val W = page.frame.w
            val a = o.optJSONArray("items") ?: JSONArray()
            val keep = page.items.associateBy { it.id }
            val items = ArrayList<Item>()
            for (k in 0 until minOf(a.length(), 80)) {
                val j = a.optJSONObject(k) ?: continue
                val type = j.optString("type").takeIf { it in Item.TYPES } ?: "box"
                fun snap(v: Int) = (v + 2) / 4 * 4
                var w = snap(j.optInt("w", 120)).coerceIn(if (type == "divider") 8 else 24, W)
                var x = snap(j.optInt("x", 16)).coerceIn(0, W - 8)
                if (x + w > W) { if (w > W - 32) { x = 16; w = W - 32 } else x = (W - w - 16) / 4 * 4 }
                val minH = if (type == "divider") 1 else 16
                val h = snap(j.optInt("h", 40)).coerceIn(minH, 2000).let { if (type == "divider") j.optInt("h", 2).coerceIn(1, 8) else it }
                val y = snap(j.optInt("y", 0)).coerceIn(0, 6000)
                val fs = j.optInt("fontSize", if (type == "heading") 28 else 16).coerceIn(10, 96)
                val id = j.optString("id").takeIf { it in keep } ?: app.gemmadesk.data.newId()
                val defaults = Item.create(type, x, y, W)
                items += Item(id = id, type = type, x = x, y = y, w = w, h = h,
                    text = j.optString("text").take(600), fontSize = fs, bold = j.optBoolean("bold", type == "heading" || type == "button"),
                    color = Html.safeColor(j.optString("color"), defaults.color).let { if (it == "transparent") defaults.color else it },
                    background = j.optString("background").let { b -> if (b.isBlank()) (if (type in setOf("button", "image", "divider")) defaults.background else "") else Html.safeColor(b, defaults.background) },
                    radius = j.optInt("radius", defaults.radius).coerceIn(0, 200), align = j.optString("align", defaults.align).takeIf { it in setOf("left", "center", "right") } ?: defaults.align,
                    link = keep[id]?.link ?: defaults.link)
            }
            // a text item too short for its words would spill over what's below: give it room
            val fixed = items.map { i ->
                if (i.type !in setOf("text", "heading") || i.text.isBlank()) i else {
                    val charsPerLine = maxOf(1, (i.w / (i.fontSize * 0.52)).toInt())
                    val lines = i.text.split('\n').sumOf { maxOf(1, (it.length + charsPerLine - 1) / charsPerLine) }
                    val need = (lines * i.fontSize * 1.3 + 4).toInt()
                    if (i.h < need) i.copy(h = (need + 3) / 4 * 4) else i
                }
            }
            return page.copy(background = Html.safeColor(o.optString("background"), page.background).let { if (it == "transparent") page.background else it }, items = fixed)
        }
    }

    /** A first layout from a description. Blocks (a minute or two on a phone). */
    fun design(description: String, page: Page): Page? {
        val raw = model.generateJson(newPrompt(description, page), SCHEMA, 3000, 0.2f)
        val o = extractJson(raw)?.let { runCatching { JSONObject(it) }.getOrNull() } ?: return null
        return tidy(o, page).takeIf { it.items.isNotEmpty() }
    }

    /** Change an existing layout as asked. */
    fun edit(instruction: String, page: Page): Page? {
        val raw = model.generateJson(editPrompt(instruction, page), SCHEMA, 3000, 0f)
        val o = extractJson(raw)?.let { runCatching { JSONObject(it) }.getOrNull() } ?: return null
        return tidy(o, page).takeIf { it.items.isNotEmpty() }
    }
}
