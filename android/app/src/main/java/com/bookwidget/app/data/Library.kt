package com.bookwidget.app.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Biblioteca local: cada libro es un pack JSON (books/<id>/book.json) más el texto de sus capítulos
 * (books/<id>/text/<n>.txt), que solo se usa para generar. Del móvil solo salen a Groq las citas cortas que se narran.
 */
object Library {
    private val mutex = Mutex()
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = false; encodeDefaults = true }

    /** Sube con cada cambio: la UI y el widget se refrescan al verlo. */
    private val _revision = MutableStateFlow(0)
    val revision: StateFlow<Int> = _revision.asStateFlow()

    private fun root(ctx: Context) = File(ctx.filesDir, "books").also { it.mkdirs() }
    private fun dir(ctx: Context, id: String) = File(root(ctx), id.replace(Regex("[^A-Za-z0-9_-]"), "_"))
    private fun file(ctx: Context, id: String) = File(dir(ctx, id), "book.json")
    private fun textFile(ctx: Context, id: String, n: Int) = File(dir(ctx, id), "text/$n.txt")

    private fun read(ctx: Context, id: String): Book? =
        runCatching { json.decodeFromString<Book>(file(ctx, id).readText()) }.getOrNull()

    private fun write(ctx: Context, book: Book) {
        val f = file(ctx, book.id).also { it.parentFile!!.mkdirs() }
        val tmp = File(f.path + ".tmp")
        tmp.writeText(json.encodeToString(Book.serializer(), book))
        tmp.renameTo(f)
    }

    /** Quita los libros sin texto de versiones anteriores (el antiguo «modo memoria»). */
    suspend fun purgeWithoutText(ctx: Context): Int = withContext(Dispatchers.IO) {
        mutex.withLock {
            root(ctx).listFiles().orEmpty().filter { !File(it, "text").isDirectory }.onEach { it.deleteRecursively() }.size
        }
    }

    suspend fun list(ctx: Context): List<Book> = withContext(Dispatchers.IO) {
        mutex.withLock {
            root(ctx).listFiles().orEmpty().mapNotNull { d ->
                runCatching { json.decodeFromString<Book>(File(d, "book.json").readText()) }.getOrNull()
            }.sortedByDescending { it.addedAt }
        }
    }

    suspend fun get(ctx: Context, id: String): Book? = withContext(Dispatchers.IO) { mutex.withLock { read(ctx, id) } }

    suspend fun active(ctx: Context): Book? = Prefs.activeBook(ctx)?.let { get(ctx, it) }

    suspend fun update(ctx: Context, id: String, transform: (Book) -> Book): Book? = withContext(Dispatchers.IO) {
        val out = mutex.withLock { read(ctx, id)?.let(transform)?.also { write(ctx, it) } }
        _revision.value++
        out
    }

    suspend fun allTexts(ctx: Context, id: String, total: Int): List<String> =
        withContext(Dispatchers.IO) { (1..total).map { textFile(ctx, id, it).readText() } }

    suspend fun chapterText(ctx: Context, id: String, n: Int): String =
        withContext(Dispatchers.IO) { textFile(ctx, id, n).readText() }

    suspend fun delete(ctx: Context, id: String) {
        withContext(Dispatchers.IO) { mutex.withLock { dir(ctx, id).deleteRecursively() } }
        if (Prefs.activeBook(ctx) == id) Prefs.setActiveBook(ctx, list(ctx).firstOrNull()?.id)
        _revision.value++
    }

    /** Guarda un libro a partir de un EPUB: capítulos reales, texto en disco, aún sin pre-spoilers. */
    private suspend fun addFromEpub(
        ctx: Context, id: String, bytes: ByteArray, title: String?, authors: List<Author>, language: String?,
        genre: String, sources: Sources, onStep: (String) -> Unit,
    ): Book {
        onStep("Separando capítulos")
        val parsed = withContext(Dispatchers.Default) { Epub.parse(bytes) }
        val raw = parsed.chapters.filter { !it.extra }
        if (raw.isEmpty()) throw IllegalStateException("No encontré capítulos en este EPUB")
        val book = Book(
            id = id,
            title = title ?: parsed.title ?: "Sin título",
            authors = authors.ifEmpty { listOfNotNull(parsed.author?.let { Author(it) }) },
            language = language ?: parsed.language,
            genre = genre,
            sources = sources,
            chapters = raw.mapIndexed { i, c -> Chapter(n = i + 1, title = c.title, chars = c.text.length) },
            addedAt = System.currentTimeMillis(),
        )
        withContext(Dispatchers.IO) {
            mutex.withLock {
                raw.forEachIndexed { i, c -> textFile(ctx, id, i + 1).also { it.parentFile!!.mkdirs() }.writeText(c.text) }
                write(ctx, book)
            }
        }
        return saved(ctx, book)
    }

    private suspend fun saved(ctx: Context, book: Book): Book {
        if (Prefs.activeBook(ctx) == null) Prefs.setActiveBook(ctx, book.id)
        _revision.value++
        return book
    }

    suspend fun addFromGutenberg(ctx: Context, g: GBook, onStep: (String) -> Unit): Book {
        val id = "gutenberg-${g.id}"
        get(ctx, id)?.let { return it }
        onStep("Descargando el EPUB")
        val bytes = Gutendex.epub(g)
        return addFromEpub(
            ctx, id, bytes, g.title, g.authors.map { Author(it.name, it.deathYear) }, g.languages.firstOrNull(),
            genreFromSubjects(g.subjects + g.bookshelves), Sources(gutenberg = g.id, epub = g.epub), onStep,
        )
    }

    suspend fun addFromWikisource(ctx: Context, r: SearchResult, onStep: (String) -> Unit): Book {
        get(ctx, r.id)?.let { return it }
        onStep("Exportando el EPUB de Wikisource")
        val bytes = Catalog.wikisourceEpub(r.wikisourcePage!!)
        return addFromEpub(ctx, r.id, bytes, r.title, emptyList(), "es", "clasico", Sources(wikisource = r.wikisourcePage), onStep)
    }

    /** EPUB del usuario (sin DRM). Se procesa y se queda en el móvil. */
    suspend fun importEpub(ctx: Context, bytes: ByteArray, onStep: (String) -> Unit): Book {
        val hash = java.security.MessageDigest.getInstance("SHA-1").digest(bytes).joinToString("") { "%02x".format(it) }
        val id = "epub-${hash.take(12)}"
        get(ctx, id)?.let { return it }
        return addFromEpub(ctx, id, bytes, null, emptyList(), null, "otro", Sources(imported = true), onStep)
    }

    /** Edición manual de un capítulo (leyendas o ficha), como en el panel. */
    suspend fun editChapter(ctx: Context, id: String, n: Int, transform: (Chapter) -> Chapter) = update(ctx, id) { b ->
        b.copy(chapters = b.chapters.map { if (it.n == n) transform(it) else it })
    }

    // ---------- progreso (local, inmediato) ----------
    suspend fun setChapter(ctx: Context, id: String, chapter: Int) = update(ctx, id) { b ->
        val c = chapter.coerceIn(0, b.total)
        if (c == b.progress.current) b else b.copy(progress = b.progress.copy(current = c, sealed = true), error = null)
    }

    suspend fun advance(ctx: Context, id: String) = update(ctx, id) { b ->
        if (b.finished) b else b.copy(progress = b.progress.copy(current = b.progress.current + 1, sealed = true), error = null)
    }

    suspend fun setSealed(ctx: Context, id: String, sealed: Boolean) =
        update(ctx, id) { it.copy(progress = it.progress.copy(sealed = sealed)) }

    suspend fun setLevel(ctx: Context, id: String, level: String) =
        update(ctx, id) { it.copy(progress = it.progress.copy(level = level)) }

    fun notifyChanged() {
        _revision.value++
    }
}
