package com.bookwidget.app.ui

import android.app.Application
import android.graphics.BitmapFactory
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.bookwidget.app.data.Api
import com.bookwidget.app.data.BookSummary
import com.bookwidget.app.data.ChapterInfo
import com.bookwidget.app.data.Config
import com.bookwidget.app.data.Level
import com.bookwidget.app.data.Prefs
import com.bookwidget.app.data.SpriteInfo
import com.bookwidget.app.data.WidgetState
import com.bookwidget.app.data.friendly
import com.bookwidget.app.widget.Sync
import com.bookwidget.app.widget.Work
import kotlinx.coroutines.launch

sealed interface Screen {
    data object Books : Screen
    data class Book(val id: Long) : Screen
    data object Settings : Screen
}

class AppViewModel(app: Application) : AndroidViewModel(app) {
    private val ctx get() = getApplication<Application>()
    private var api: Api? = null
    private val frameCache = HashMap<String, ImageBitmap>()

    var loaded by mutableStateOf(false); private set
    var config by mutableStateOf<Config?>(null); private set
    var animate by mutableStateOf(true); private set
    var screen by mutableStateOf<Screen>(Screen.Books)
    var books by mutableStateOf<List<BookSummary>>(emptyList()); private set
    var current by mutableStateOf<WidgetState?>(null); private set
    var chapters by mutableStateOf<List<ChapterInfo>>(emptyList()); private set
    var frames by mutableStateOf<List<ImageBitmap>>(emptyList()); private set
    var working by mutableStateOf<String?>(null); private set
    var error by mutableStateOf<String?>(null)

    init {
        viewModelScope.launch {
            config = Prefs.config(ctx)
            animate = Prefs.animate(ctx)
            api = config?.let { Api(it.baseUrl, it.token) }
            loaded = true
            if (api != null) {
                Work.schedulePeriodic(ctx)
                refreshBooks()
            }
        }
    }

    // ---------- conexión ----------
    fun connect(url: String, token: String, onDone: (String?) -> Unit) {
        viewModelScope.launch {
            val normalized = Prefs.normalizeUrl(url)
            val candidate = Api(normalized, token.trim())
            try {
                candidate.verify()
            } catch (e: Throwable) {
                onDone(e.friendly())
                return@launch
            }
            Prefs.saveConfig(ctx, normalized, token)
            config = Config(normalized, token.trim())
            api = candidate
            screen = Screen.Books
            Work.schedulePeriodic(ctx)
            refreshBooks()
            api?.widget()?.let { Sync.apply(ctx, candidate, it) }
            onDone(null)
        }
    }

    fun disconnect() {
        viewModelScope.launch {
            Prefs.clearConfig(ctx)
            config = null
            api = null
            books = emptyList()
            current = null
            Work.cancelPeriodic(ctx)
        }
    }

    fun setAnimate(value: Boolean) {
        animate = value
        viewModelScope.launch {
            Prefs.setAnimate(ctx, value)
            api?.let { a -> a.widget()?.let { Sync.apply(ctx, a, it) } } // repinta el widget con/sin animación
        }
    }

    // ---------- libros ----------
    fun refreshBooks() {
        val a = api ?: return
        viewModelScope.launch {
            try {
                books = a.books()
                error = null
            } catch (e: Throwable) {
                error = e.friendly()
            }
        }
    }

    fun addBook(title: String, author: String, synopsis: String, total: Int?, onDone: (String?) -> Unit) {
        val a = api ?: return
        viewModelScope.launch {
            try {
                a.createBook(title.trim(), author.trim(), synopsis.trim(), total)
                refreshBooks()
                onDone(null)
            } catch (e: Throwable) {
                onDone(e.friendly())
            }
        }
    }

    fun openBook(id: Long) {
        screen = Screen.Book(id)
        current = null
        frames = emptyList()
        chapters = emptyList()
        launchWork(null) { a ->
            show(a.widget(id)!!)
            chapters = a.chapters(id) // títulos para el selector de capítulo
        }
    }

    fun activate(id: Long) = launchWork(null) { a ->
        a.activate(id)
        a.widget()?.let { Sync.apply(ctx, a, it) } // el widget pasa a este libro
        refreshBooks()
    }

    // ---------- progreso ----------
    fun saveProgress(chapter: Int, level: Level) = launchWork("Generando personaje…") { a ->
        changed(a, a.progress(current!!.bookId, chapter, level.id))
    }

    fun advance() = launchWork("Invocando al siguiente personaje…") { a -> changed(a, a.advance(current!!.bookId)) }

    fun regenerate() = launchWork("Dibujando un personaje nuevo…") { a -> changed(a, a.regenerate(current!!.bookId)) }

    fun setSealed(sealed: Boolean) {
        val s = current ?: return
        current = s.copy(sealed = sealed) // optimista: se ve al instante
        launchWork(null) { a -> changed(a, a.patchState(s.bookId, sealed = sealed)) }
    }

    fun setLevel(level: Level) {
        val s = current ?: return
        launchWork(null) { a -> changed(a, a.patchState(s.bookId, level = level.id)) }
    }

    fun share(sealed: Boolean) {
        val s = current ?: return
        launchWork("Preparando tarjeta…") { a -> shareCard(ctx, a, s.bookId, sealed, s.title) }
    }

    // ---------- internos ----------
    private suspend fun changed(a: Api, state: WidgetState) {
        show(state)
        if (state.isActive) Sync.apply(ctx, a, state) // el libro activo es el del widget
        refreshBooks()
    }

    private suspend fun show(state: WidgetState) {
        frames = loadFrames(state.sprite) // primero los frames: evita parpadeos
        current = state
    }

    private suspend fun loadFrames(sprite: SpriteInfo?): List<ImageBitmap> {
        val a = api ?: return emptyList()
        return sprite?.frameUrls.orEmpty().mapNotNull { url ->
            frameCache[url] ?: runCatching {
                val bytes = a.image(url)
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size).asImageBitmap()
            }.getOrNull()?.also { frameCache[url] = it }
        }
    }

    private fun launchWork(label: String?, block: suspend (Api) -> Unit) {
        val a = api ?: return
        viewModelScope.launch {
            working = label
            error = null
            try {
                block(a)
            } catch (e: Throwable) {
                error = e.friendly()
            } finally {
                working = null
            }
        }
    }
}
