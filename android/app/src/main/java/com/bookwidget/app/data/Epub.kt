package com.bookwidget.app.data

import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element as JElement
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode
import org.jsoup.parser.Parser
import org.jsoup.select.NodeTraversor
import org.jsoup.select.NodeVisitor
import java.io.ByteArrayInputStream
import java.net.URI
import java.net.URLDecoder
import java.util.zip.ZipInputStream

/** EPUB -> capítulos reales, sin IA (portado de poc/packgen.py). */
object Epub {
    data class RawChapter(val title: String, val extra: Boolean, val text: String)

    /** Metadatos del OPF (sirven para los EPUB que importa el usuario) + capítulos. */
    data class Parsed(val title: String?, val author: String?, val language: String?, val chapters: List<RawChapter>)

    private data class TocEntry(val title: String, val file: String, val frag: String, val leaf: Boolean, val parent: String)

    private val BLOCK = setOf(
        "p", "div", "br", "h1", "h2", "h3", "h4", "h5", "h6", "li", "tr", "blockquote", "section", "pre", "hr",
    )
    private val SKIP_TITLE = Regex(
        "gutenberg|licen|license|índice|indice|contents|contenido|portada|cover|title ?page|colof|colophon" +
            "|nota del transcriptor|transcriber|imprimatur|copyright|dedicatoria|footnotes|notas$",
        RegexOption.IGNORE_CASE,
    )
    private val CHAPTER_TITLE = Regex(
        "^(cap[ií]tulo|chapter|canto|libro|book|parte|part)\\b|^[IVXLC]+\\b\\.?|^\\d+\\b", RegexOption.IGNORE_CASE,
    )
    private const val MIN_CHAPTER_CHARS = 1500

    private fun local(tag: String) = tag.substringAfter(':')
    private fun Document.byLocal(name: String) = allElements.filter { local(it.tagName()) == name }
    private fun JElement.childrenLocal(name: String) = children().filter { local(it.tagName()) == name }

    private fun join(base: String, href: String): Pair<String, String> {
        val path = href.substringBefore('#')
        val frag = if ('#' in href) href.substringAfter('#') else ""
        if (path.isEmpty()) return "" to frag
        val decoded = URLDecoder.decode(path.replace("+", "%2B"), "UTF-8")
        val resolved = URI("file:///" + (if (base.isEmpty()) "" else "$base/")).resolve(URI(null, null, decoded, null))
        return resolved.path.removePrefix("/") to frag
    }

    private fun dir(path: String) = path.substringBeforeLast('/', "")

    private fun unzip(bytes: ByteArray): Map<String, ByteArray> {
        val out = HashMap<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { z ->
            while (true) {
                val e = z.nextEntry ?: break
                if (!e.isDirectory) out[e.name] = z.readBytes()
            }
        }
        return out
    }

    private fun xml(bytes: ByteArray) = Jsoup.parse(String(bytes, Charsets.UTF_8), "", Parser.xmlParser())

    private fun tocNav(files: Map<String, ByteArray>, path: String): List<TocEntry> {
        val doc = xml(files[path] ?: return emptyList())
        val base = dir(path)
        val nav = doc.byLocal("nav").firstOrNull { el -> el.attributes().any { "toc" in it.value } } ?: return emptyList()
        val out = mutableListOf<TocEntry>()
        fun walk(ol: JElement, parent: String) {
            for (li in ol.childrenLocal("li")) {
                val a = li.childrenLocal("a").firstOrNull()
                val sub = li.childrenLocal("ol").firstOrNull()
                val title = a?.text()?.trim()?.replace(Regex("\\s+"), " ") ?: parent
                if (a != null) {
                    val (f, frag) = join(base, a.attr("href"))
                    out += TocEntry(title, f, frag, sub == null, parent)
                }
                if (sub != null) walk(sub, title)
            }
        }
        nav.childrenLocal("ol").firstOrNull()?.let { walk(it, "") }
        return out
    }

    private fun tocNcx(files: Map<String, ByteArray>, path: String): List<TocEntry> {
        val doc = xml(files[path] ?: return emptyList())
        val base = dir(path)
        val out = mutableListOf<TocEntry>()
        fun walk(node: JElement, parent: String) {
            for (np in node.childrenLocal("navPoint")) {
                val label = np.childrenLocal("navLabel").firstOrNull()?.text()?.trim()?.replace(Regex("\\s+"), " ").orEmpty()
                val src = np.childrenLocal("content").firstOrNull()?.attr("src").orEmpty()
                val kids = np.childrenLocal("navPoint")
                val (f, frag) = join(base, src)
                out += TocEntry(label, f, frag, kids.isEmpty(), parent)
                walk(np, label)
            }
        }
        doc.byLocal("navMap").firstOrNull()?.let { walk(it, "") }
        return out
    }

    /** Texto plano de un XHTML + posición de cada id/name para cortar por anclas. */
    private class TextCollector(val sb: StringBuilder, val anchors: MutableMap<String, Int>) : NodeVisitor {
        override fun head(node: Node, depth: Int) {
            when (node) {
                is TextNode -> sb.append(node.wholeText)
                is JElement -> {
                    for (k in listOf("id", "name")) {
                        val v = node.attr(k)
                        if (v.isNotEmpty()) anchors.putIfAbsent(v, sb.length)
                    }
                    if (node.normalName() in BLOCK) sb.append('\n')
                }
            }
        }

        override fun tail(node: Node, depth: Int) {
            if (node is JElement && node.normalName() in BLOCK) sb.append('\n')
        }
    }

    fun chapters(epub: ByteArray): List<RawChapter> = parse(epub).chapters

    fun parse(epub: ByteArray): Parsed {
        val files = try {
            unzip(epub)
        } catch (e: Exception) {
            throw IllegalArgumentException("El archivo no es un EPUB válido")
        }
        if ("META-INF/container.xml" !in files) throw IllegalArgumentException("El archivo no es un EPUB válido")
        if (files.keys.any { it.endsWith("encryption.xml") } && files.keys.any { it.endsWith("rights.xml") })
            throw IllegalArgumentException("Este EPUB tiene DRM: solo funcionan EPUB sin protección")
        val opfPath = xml(files.getValue("META-INF/container.xml")).byLocal("rootfile").first().attr("full-path")
        val opf = xml(files.getValue(opfPath))
        val base = dir(opfPath)
        fun meta(name: String) = opf.byLocal(name).firstOrNull()?.text()?.trim()?.takeIf { it.isNotEmpty() }
        val manifest = opf.byLocal("item").associateBy { it.attr("id") }
        val spine = opf.byLocal("itemref").mapNotNull { manifest[it.attr("idref")] }.map { join(base, it.attr("href")).first }

        val navItem = manifest.values.firstOrNull { "nav" in it.attr("properties").split(' ') }
        val ncxItem = manifest.values.firstOrNull { it.attr("media-type") == "application/x-dtbncx+xml" }
        var toc = navItem?.let { tocNav(files, join(base, it.attr("href")).first) }.orEmpty()
        if (toc.isEmpty() && ncxItem != null) toc = tocNcx(files, join(base, ncxItem.attr("href")).first)

        // todo el libro como un solo texto, con la posición de cada fichero y ancla
        val full = StringBuilder()
        val fileStart = HashMap<String, Int>()
        val offsets = HashMap<Pair<String, String>, Int>()
        for (f in spine) {
            val bytes = files[f] ?: continue
            val doc = Jsoup.parse(String(bytes, Charsets.UTF_8))
            fileStart[f] = full.length
            val anchors = HashMap<String, Int>()
            NodeTraversor.traverse(TextCollector(full, anchors), doc.body())
            anchors.forEach { (k, v) -> offsets[f to k] = v }
        }
        val text = full.toString()

        // recorte de la cabecera/licencia de Gutenberg
        val m1 = Regex("\\*\\*\\* ?START OF (THE|THIS) PROJECT GUTENBERG[^\\n]*").find(text)
        val m2 = Regex("\\*\\*\\* ?END OF (THE|THIS) PROJECT GUTENBERG").find(text)
        val lo = m1?.range?.last?.plus(1) ?: 0
        val hi = m2?.range?.first ?: text.length

        data class Positioned(val e: TocEntry, val start: Int)
        val entries = toc.mapNotNull { e ->
            val start = (if (e.frag.isNotEmpty()) offsets[e.file to e.frag] else null) ?: fileStart[e.file]
            start?.let { Positioned(e, it) }
        }.sortedBy { it.start }

        val kept = mutableListOf<Pair<TocEntry, String>>()
        entries.forEachIndexed { i, p ->
            if (!p.e.leaf || SKIP_TITLE.containsMatchIn(p.e.title)) return@forEachIndexed
            val end = entries.getOrNull(i + 1)?.start ?: text.length
            val s = maxOf(p.start, lo)
            val t = minOf(end, hi)
            if (t <= s) return@forEachIndexed
            val body = text.substring(s, t)
                .replace(Regex("[ \\t\\u00A0]+"), " ")
                .replace(Regex("\\n\\s*\\n+"), "\n\n")
                .trim()
            if (body.length >= MIN_CHAPTER_CHARS) kept += p.e to body
        }
        val first = kept.indexOfFirst { CHAPTER_TITLE.containsMatchIn(it.first.title) }.coerceAtLeast(0)
        val chapters = kept.mapIndexed { i, (e, body) ->
            val title = if (e.parent.isNotEmpty() && !e.title.lowercase().contains(e.parent.lowercase()) &&
                !SKIP_TITLE.containsMatchIn(e.parent)
            ) "${e.parent} · ${e.title}" else e.title
            RawChapter(title, i < first, body)
        }
        return Parsed(meta("title"), meta("creator"), meta("language"), chapters)
    }
}
