package com.bookwidget.app.ui

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.bookwidget.app.data.Book
import com.bookwidget.app.data.Catalog
import com.bookwidget.app.data.Generator
import com.bookwidget.app.data.Groq
import com.bookwidget.app.data.Level
import com.bookwidget.app.data.Library
import com.bookwidget.app.data.Prefs
import com.bookwidget.app.data.Scene
import com.bookwidget.app.data.SearchResult
import com.bookwidget.app.data.Source
import com.bookwidget.app.data.SpriteJson
import com.bookwidget.app.data.Sprites
import com.bookwidget.app.data.Thumbnails
import com.bookwidget.app.widget.Work
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface Screen {
    data object Books : Screen
    data object Search : Screen
    data class Detail(val id: String) : Screen
    data class Chapters(val id: String) : Screen
    data object Settings : Screen
}

/** Operación que bloquea la pantalla (alta de un libro): título, paso actual y lista de pasos. */
data class Blocking(val title: String, val step: String, val steps: List<String>)

class AppViewModel(app: Application) : AndroidViewModel(app) {
    private val ctx get() = getApplication<Application>()
    private var framesFor: SpriteJson? = null

    var loaded by mutableStateOf(false); private set
    var groqKey by mutableStateOf(""); private set
    var groqTest by mutableStateOf<String?>(null); private set
    var testing by mutableStateOf(false); private set
    var animate by mutableStateOf(true); private set
    var screen by mutableStateOf<Screen>(Screen.Books)
    var books by mutableStateOf<List<Book>>(emptyList()); private set
    var activeId by mutableStateOf<String?>(null); private set
    var current by mutableStateOf<Book?>(null); private set
    var frames by mutableStateOf<List<ImageBitmap>>(emptyList()); private set
    var status by mutableStateOf<Map<String, Generator.Work>>(emptyMap()); private set
    var blocking by mutableStateOf<Blocking?>(null); private set
    var error by mutableStateOf<String?>(null)
    var notice by mutableStateOf<String?>(null)

    var results by mutableStateOf<List<SearchResult>>(emptyList()); private set
    var searchNotes by mutableStateOf<List<String>>(emptyList()); private set
    var searching by mutableStateOf(false); private set
    var searched by mutableStateOf(false); private set

    init {
        viewModelScope.launch {
            groqKey = Prefs.groqKey(ctx)
            animate = Prefs.animate(ctx)
            val purged = Library.purgeWithoutText(ctx)
            if (purged > 0) {
                notice = "Se quitaron $purged libros sin texto de versiones anteriores."
                if (Prefs.activeBook(ctx)?.let { Library.get(ctx, it) } == null) Prefs.setActiveBook(ctx, Library.list(ctx).firstOrNull()?.id)
            }
            loaded = true
            reload()
            Work.refreshWidget(ctx) // por si el widget quedó en «cargando» tras una actualización
            Library.active(ctx)?.let { Work.generate(ctx, it.id) } // por si quedó algo a medias
        }
        viewModelScope.launch { Library.revision.collect { reload() } }
        viewModelScope.launch { Generator.status.collect { status = it } }
    }

    fun reload() {
        viewModelScope.launch {
            books = Library.list(ctx)
            activeId = Prefs.activeBook(ctx)
            current = when (val s = screen) {
                is Screen.Detail -> books.firstOrNull { it.id == s.id }
                is Screen.Chapters -> books.firstOrNull { it.id == s.id }
                else -> null
            }
            val sprite = current?.sprite
            if (sprite != framesFor) {
                framesFor = sprite
                frames = sprite?.let { sp -> Sprites.bitmaps(sp).map { it.asImageBitmap() } }.orEmpty()
            }
        }
    }

    fun navigate(to: Screen) {
        if (blocking != null) return
        screen = to
        error = null
        reload()
    }

    /** El capítulo que el lector va a leer se está preparando: se bloquean las acciones que lo cambiarían. */
    fun generatingNext(b: Book): Generator.Work? = status[b.id]?.takeIf { it.chapter == b.progress.current + 1 }

    fun waitingNext(b: Book): Boolean = b.error == null && b.next?.ready == false

    fun thumbnail(b: Book): Bitmap = Thumbnails.forBook(b)

    // ---------- configuración: Groq ----------
    /** Guarda la clave y la prueba con un susurro de ejemplo. */
    fun saveGroqKey(key: String) {
        viewModelScope.launch {
            testing = true
            groqTest = null
            try {
                val sample = Groq.test(key.trim())
                Prefs.setGroqKey(ctx, key)
                groqKey = key.trim()
                groqTest = "✓ $sample"
                books.forEach { b -> if (b.error != null) retry(b.id) }
                books.forEach { Work.generate(ctx, it.id) }
            } catch (e: Exception) {
                groqTest = "✗ ${e.message}"
            } finally {
                testing = false
            }
        }
    }

    fun changeAnimate(value: Boolean) {
        animate = value
        viewModelScope.launch {
            Prefs.setAnimate(ctx, value)
            Work.refreshWidget(ctx)
        }
    }

    // ---------- búsqueda y alta ----------
    fun search(query: String, spanishOnly: Boolean) {
        if (query.isBlank() || searching) return
        viewModelScope.launch {
            searching = true
            error = null
            try {
                val (found, errors) = Catalog.search(query, spanishOnly)
                results = found
                searchNotes = errors.map { "No respondió $it" }
                searched = true
            } finally {
                searching = false
            }
        }
    }

    fun add(r: SearchResult) {
        val steps = when (r.source) {
            Source.GUTENBERG -> listOf("Descargando el EPUB", "Separando capítulos")
            Source.WIKISOURCE -> listOf("Exportando el EPUB de Wikisource", "Separando capítulos")
        }
        block(r.title, steps) { onStep ->
            when (r.source) {
                Source.GUTENBERG -> Library.addFromGutenberg(ctx, r.gutenberg!!, onStep)
                Source.WIKISOURCE -> Library.addFromWikisource(ctx, r, onStep)
            }
        }
    }

    fun importEpub(uri: Uri) {
        block("Tu EPUB", listOf("Leyendo el archivo", "Separando capítulos")) { onStep ->
            onStep("Leyendo el archivo")
            val bytes = withContext(Dispatchers.IO) {
                ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: throw IllegalStateException("No pude abrir el archivo")
            }
            Library.importEpub(ctx, bytes, onStep)
        }
    }

    /** Alta de un libro con la pantalla bloqueada; al terminar abre el libro y empieza a prepararlo. */
    private fun block(title: String, steps: List<String>, action: suspend (onStep: (String) -> Unit) -> Book) {
        if (blocking != null) return
        blocking = Blocking(title, steps.first(), steps)
        error = null
        viewModelScope.launch {
            try {
                val book = action { step -> blocking = blocking?.copy(step = step) }
                Work.generate(ctx, book.id)
                blocking = null
                navigate(Screen.Detail(book.id))
            } catch (e: Exception) {
                blocking = null
                error = e.message ?: e.toString()
            }
        }
    }

    // ---------- libro ----------
    fun activate(id: String) {
        viewModelScope.launch {
            Prefs.setActiveBook(ctx, id)
            Library.notifyChanged()
            Work.refreshWidget(ctx)
            Work.generate(ctx, id)
        }
    }

    fun setChapter(chapter: Int) = progressChange { id -> Library.setChapter(ctx, id, chapter) }

    fun advance() = progressChange { id -> Library.advance(ctx, id) }

    fun setSealed(sealed: Boolean) = progressChange(generate = false) { id -> Library.setSealed(ctx, id, sealed) }

    fun setLevel(level: Level) = progressChange(generate = false) { id -> Library.setLevel(ctx, id, level.id) }

    fun retry(id: String) {
        viewModelScope.launch {
            Library.update(ctx, id) { it.copy(error = null) }
            Work.generate(ctx, id)
        }
    }

    fun delete(id: String) {
        viewModelScope.launch {
            Library.delete(ctx, id)
            Work.refreshWidget(ctx)
            navigate(Screen.Books)
        }
    }

    /** Rehace el capítulo que el lector va a leer (nuevas citas, dibujo y narración). */
    fun redoNext() {
        val b = current ?: return
        val n = b.progress.current + 1
        if (n > b.total || waitingNext(b)) return
        viewModelScope.launch {
            Library.update(ctx, b.id) { bk ->
                bk.copy(error = null, chapters = bk.chapters.map {
                    if (it.n == n) it.copy(niebla = null, pista = null, peligro = null, escena = null, sprite = null, model = null, quotes = null, narrated = false) else it
                }, progress = bk.progress.copy(sealed = true))
            }
            Work.refreshWidget(ctx)
            Work.generate(ctx, b.id)
        }
    }

    /** «Nuevo dibujo» para el capítulo que el lector va a leer. */
    fun newSprite() {
        val b = current ?: return
        val n = b.progress.current + 1
        if (n > b.total || waitingNext(b)) return
        viewModelScope.launch {
            Library.editChapter(ctx, b.id, n) { it.copy(sprite = null) }
            Work.generate(ctx, b.id)
        }
    }

    fun saveChapter(n: Int, niebla: String, pista: String, peligro: String, scene: Scene) {
        val b = current ?: return
        viewModelScope.launch {
            Library.editChapter(ctx, b.id, n) {
                it.copy(niebla = niebla.trim(), pista = pista.trim(), peligro = peligro.trim().ifEmpty { null }, escena = scene, model = "editado", narrated = true)
            }
            Work.refreshWidget(ctx)
        }
    }

    private fun progressChange(generate: Boolean = true, block: suspend (String) -> Unit) {
        val b = current ?: return
        if (generate && waitingNext(b)) return // no se cambia de capítulo mientras se prepara el siguiente
        viewModelScope.launch {
            try {
                block(b.id)
                Work.refreshWidget(ctx)
                if (generate) Work.generate(ctx, b.id)
            } catch (e: Exception) {
                error = e.message
            }
        }
    }
}
