package com.bookwidget.app.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Prepara un libro leyendo su texto real:
 *  1. algoritmos clásicos (TF-IDF, TextRank, MMR) eligen las citas de TODOS los capítulos (instantáneo, sin red);
 *  2. iconos pixelados para cada capítulo (sin red);
 *  3. Groq convierte en susurros las citas del capítulo siguiente y del de después (lo mínimo).
 * Si Groq falla o no hay red, se quedan las citas.
 */
object Generator {
    data class Work(val chapter: Int, val label: String)

    private val locks = HashMap<String, Mutex>()
    private val _status = MutableStateFlow<Map<String, Work>>(emptyMap())
    val status: StateFlow<Map<String, Work>> = _status.asStateFlow()

    private fun lock(id: String) = synchronized(locks) { locks.getOrPut(id) { Mutex() } }
    private fun setStatus(id: String, s: Work?) = _status.update { if (s == null) it - id else it + (id to s) }

    suspend fun run(ctx: Context, id: String, onChapterReady: suspend () -> Unit) = lock(id).withLock {
        try {
            quotes(ctx, id, onChapterReady)
            icons(ctx, id, onChapterReady)
            narrate(ctx, id, onChapterReady)
        } finally {
            setStatus(id, null)
        }
    }

    private suspend fun quotes(ctx: Context, id: String, onChapterReady: suspend () -> Unit) {
        val book = Library.get(ctx, id) ?: return
        if (book.chapters.none { !it.hasText }) return
        setStatus(id, Work(book.chapters.first { !it.hasText }.n, "Leyendo el libro"))
        val texts = Library.allTexts(ctx, id, book.total)
        val filled = withContext(Dispatchers.Default) {
            val stats = Classic.BookStats(texts)
            book.chapters.map { c ->
                if (c.hasText) c else Classic.captions(texts[c.n - 1], stats).let { r ->
                    c.copy(niebla = r.niebla, pista = r.pista, peligro = r.peligro, escena = r.scene, model = "citas")
                }
            }
        }
        Library.update(ctx, id) { b -> b.copy(chapters = b.chapters.mapIndexed { i, old -> if (old.hasText) old else filled[i] }, error = null) }
        onChapterReady()
    }

    private suspend fun icons(ctx: Context, id: String, onChapterReady: suspend () -> Unit) {
        val book = Library.get(ctx, id) ?: return
        if (book.chapters.none { it.hasText && it.sprite == null }) return
        setStatus(id, Work(book.chapters.first { it.hasText && it.sprite == null }.n, "Dibujando"))
        var acc = book
        val drawn = withContext(Dispatchers.Default) {
            book.chapters.map { c ->
                if (!c.hasText || c.sprite != null) c
                else c.copy(sprite = IconSprites.draw(ctx, acc, c, c.escena?.elementos?.map { it.nombre }.orEmpty()))
                    .also { nc -> acc = acc.copy(chapters = acc.chapters.map { if (it.n == nc.n) nc else it }) }
            }
        }
        Library.update(ctx, id) { b ->
            b.copy(chapters = b.chapters.mapIndexed { i, old -> if (old.sprite == null && drawn[i].sprite != null) old.copy(sprite = drawn[i].sprite) else old })
        }
        onChapterReady()
    }

    /** Groq narra solo el capítulo siguiente y el de después; cualquier fallo deja las citas. */
    private suspend fun narrate(ctx: Context, id: String, onChapterReady: suspend () -> Unit) {
        val key = Prefs.groqKey(ctx)
        if (key.isBlank()) return
        val book = Library.get(ctx, id) ?: return
        for (n in book.wanted()) {
            val c = book.chapters[n - 1]
            // citas sin narrar, o narradas con una versión anterior del narrador (leyendas más cortas)
            val stale = c.model == "groq" && c.narration < Groq.VERSION && c.quotes != null
            if (!c.hasText || !(c.model == "citas" && !c.narrated || stale)) continue
            setStatus(id, Work(n, "Narrando el capítulo $n"))
            val known = c.escena?.elementos?.map { it.nombre }.orEmpty()
            val base = if (stale) c.quotes!! else listOfNotNull(c.niebla?.let { "niebla" to it }, c.pista?.let { "pista" to it }, c.peligro?.let { "peligro" to it }).toMap()
            val levels = base.toList()
            val text = runCatching { Library.chapterText(ctx, id, n) }.getOrDefault("")
            val out = HashMap<String, String>()
            try {
                for ((lv, q) in levels) Groq.whisper(key, lv, Classic.passage(text, q), book.language, known)?.let { out[lv] = it }
            } catch (e: Groq.BadKey) {
                Library.update(ctx, id) { it.copy(error = e.message) }
                return
            } catch (e: Exception) {
                return // sin red o sin cuota: se quedan las citas y se reintenta en la próxima pasada
            }
            Library.editChapter(ctx, id, n) { ch ->
                if (out.isEmpty()) ch.copy(narrated = true, narration = Groq.VERSION)
                else ch.copy(
                    niebla = out["niebla"] ?: base["niebla"], pista = out["pista"] ?: base["pista"], peligro = out["peligro"] ?: base["peligro"],
                    quotes = base, narrated = true, narration = Groq.VERSION, model = "groq",
                )
            }
            onChapterReady()
        }
    }
}
