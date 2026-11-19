package app.foxbox.notes

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.RectF
import android.graphics.pdf.PdfDocument
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** A memo book as a plain text file, Markdown, a Word document (with the sketches), or a PDF of the pages. */
object NoteExport {
    enum class Format(val label: String, val ext: String, val mime: String) {
        TXT("Plain text (.txt)", "txt", "text/plain"),
        MD("Markdown (.md)", "md", "text/markdown"),
        DOCX("Word document (.docx)", "docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document"),
        PDF("PDF, as the pages look (.pdf)", "pdf", "application/pdf"),
    }

    fun build(c: Context, b: Notes.Book, f: Format): ByteArray = when (f) {
        Format.TXT -> text(b).toByteArray()
        Format.MD -> markdown(b).toByteArray()
        Format.DOCX -> docx(b)
        Format.PDF -> pdf(c, b)
    }

    /** The pages that have something on them, numbered as in the book. */
    private fun used(b: Notes.Book) = b.pages.withIndex().filter { !it.value.empty }

    /** A page's lines in reading order, with a marker where each sketch sits. */
    private fun items(p: Notes.Page): List<Any> {
        val out = ArrayList<Any>()
        for (i in 0 until Notes.LINES) {
            p.sketches.filter { it.line == i }.forEach { out += it }
            if (p.sketches.none { it.covers(i) }) out += p.lines[i]
        }
        while (out.isNotEmpty() && (out.last() as? String)?.isBlank() == true) out.removeAt(out.size - 1)
        return out
    }

    fun text(b: Notes.Book): String = buildString {
        append(b.name).append("\n\n")
        for ((i, p) in used(b)) {
            append("— page ${i + 1} —\n")
            for (it in items(p)) append(if (it is String) it.trimEnd() else "[sketch]").append('\n')
            append('\n')
        }
    }

    fun markdown(b: Notes.Book): String = buildString {
        append("# ").append(b.name).append("\n\n")
        for ((i, p) in used(b)) {
            append("## Page ${i + 1}\n\n")
            for (it in items(p)) append(if (it is String) it.trimEnd() + "  " else "*[sketch]*").append('\n')
            append('\n')
        }
    }

    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    fun docx(b: Notes.Book): ByteArray {
        val images = ArrayList<Pair<String, ByteArray>>()
        val body = StringBuilder()
        fun para(t: String, style: String? = null) {
            body.append("<w:p>")
            if (style != null) body.append("<w:pPr><w:pStyle w:val=\"$style\"/></w:pPr>")
            body.append("<w:r><w:t xml:space=\"preserve\">").append(esc(t)).append("</w:t></w:r></w:p>")
        }
        para(b.name, "Title")
        val pages = used(b)
        pages.forEachIndexed { n, (i, p) ->
            para("Page ${i + 1}", "Heading1")
            for (it in items(p)) {
                if (it is String) para(it.trimEnd())
                else {
                    val s = it as Notes.Sketch
                    val png = Notes.sketchPng(s.id) ?: continue
                    val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeByteArray(png, 0, png.size, o)
                    val rid = "rIdImg${images.size + 1}"
                    images += rid to png
                    // 5 inches wide, height to the picture's shape (EMUs: 914400 an inch)
                    val cx = 914400L * 5; val cy = if (o.outWidth > 0) cx * o.outHeight / o.outWidth else cx * 3 / 4
                    val id = images.size
                    body.append("<w:p><w:r><w:drawing><wp:inline distT=\"0\" distB=\"0\" distL=\"0\" distR=\"0\"><wp:extent cx=\"$cx\" cy=\"$cy\"/>")
                        .append("<wp:docPr id=\"$id\" name=\"Sketch $id\"/><a:graphic xmlns:a=\"http://schemas.openxmlformats.org/drawingml/2006/main\">")
                        .append("<a:graphicData uri=\"http://schemas.openxmlformats.org/drawingml/2006/picture\"><pic:pic xmlns:pic=\"http://schemas.openxmlformats.org/drawingml/2006/picture\">")
                        .append("<pic:nvPicPr><pic:cNvPr id=\"$id\" name=\"sketch$id.png\"/><pic:cNvPicPr/></pic:nvPicPr>")
                        .append("<pic:blipFill><a:blip r:embed=\"$rid\"/><a:stretch><a:fillRect/></a:stretch></pic:blipFill>")
                        .append("<pic:spPr><a:xfrm><a:off x=\"0\" y=\"0\"/><a:ext cx=\"$cx\" cy=\"$cy\"/></a:xfrm><a:prstGeom prst=\"rect\"><a:avLst/></a:prstGeom></pic:spPr>")
                        .append("</pic:pic></a:graphicData></a:graphic></wp:inline></w:drawing></w:r></w:p>")
                }
            }
            if (n < pages.size - 1) body.append("<w:p><w:r><w:br w:type=\"page\"/></w:r></w:p>")
        }
        val document = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>" +
            "<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\" xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\" " +
            "xmlns:wp=\"http://schemas.openxmlformats.org/drawingml/2006/wordprocessingDrawing\"><w:body>$body<w:sectPr/></w:body></w:document>"
        val styles = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?><w:styles xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\">" +
            "<w:style w:type=\"paragraph\" w:default=\"1\" w:styleId=\"Normal\"><w:name w:val=\"Normal\"/><w:rPr><w:sz w:val=\"24\"/></w:rPr></w:style>" +
            "<w:style w:type=\"paragraph\" w:styleId=\"Title\"><w:name w:val=\"Title\"/><w:basedOn w:val=\"Normal\"/><w:rPr><w:b/><w:sz w:val=\"40\"/></w:rPr></w:style>" +
            "<w:style w:type=\"paragraph\" w:styleId=\"Heading1\"><w:name w:val=\"heading 1\"/><w:basedOn w:val=\"Normal\"/><w:pPr><w:spacing w:before=\"240\"/></w:pPr><w:rPr><w:b/><w:sz w:val=\"28\"/></w:rPr></w:style></w:styles>"
        val rels = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?><Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">" +
            "<Relationship Id=\"rIdStyles\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles\" Target=\"styles.xml\"/>" +
            images.joinToString("") { (rid, _) -> "<Relationship Id=\"$rid\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/image\" Target=\"media/$rid.png\"/>" } +
            "</Relationships>"
        val types = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?><Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">" +
            "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/><Default Extension=\"xml\" ContentType=\"application/xml\"/>" +
            "<Default Extension=\"png\" ContentType=\"image/png\"/>" +
            "<Override PartName=\"/word/document.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml\"/>" +
            "<Override PartName=\"/word/styles.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.styles+xml\"/></Types>"
        val root = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?><Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">" +
            "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"word/document.xml\"/></Relationships>"
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z ->
            fun put(name: String, bytes: ByteArray) { z.putNextEntry(ZipEntry(name)); z.write(bytes); z.closeEntry() }
            put("[Content_Types].xml", types.toByteArray())
            put("_rels/.rels", root.toByteArray())
            put("word/document.xml", document.toByteArray())
            put("word/styles.xml", styles.toByteArray())
            put("word/_rels/document.xml.rels", rels.toByteArray())
            images.forEach { (rid, png) -> put("word/media/$rid.png", png) }
        }
        return out.toByteArray()
    }

    /** Every used page as it looks in the book (A5, the handwriting font, the sketches). */
    fun pdf(c: Context, b: Notes.Book): ByteArray {
        val doc = PdfDocument()
        val art = PageArt(c)
        val pages = used(b).ifEmpty { listOf(IndexedValue(0, b.pages.first())) }
        val w = 420; val h = 595
        for ((n, iv) in pages.withIndex()) {
            val pg = doc.startPage(PdfDocument.PageInfo.Builder(w, h, n + 1).create())
            val pw = (h - 40) * Notes.ASPECT
            val g = PageGeo(RectF((w - pw) / 2, 20f, (w + pw) / 2, h - 20f))
            art.draw(pg.canvas, g, iv.value, iv.index + 1)
            doc.finishPage(pg)
        }
        val out = ByteArrayOutputStream()
        doc.writeTo(out); doc.close()
        return out.toByteArray()
    }
}
