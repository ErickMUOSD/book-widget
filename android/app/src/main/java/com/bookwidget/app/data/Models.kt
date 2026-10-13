package com.bookwidget.app.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// ---------- pack del libro (mismo formato que poc/packgen.py, + progreso local) ----------
@Serializable
data class Element(val tipo: String = "objeto", val nombre: String = "", val detalle: String = "")

@Serializable
data class Scene(val nudo: String = "", val desenlace: String = "", val elementos: List<Element> = emptyList())

@Serializable
data class SpriteJson(
    val name: String,
    val subject: String = "",
    val palette: List<String>,
    val pixels: List<String>,
    val frames: List<List<List<Int>>> = emptyList(),
    val fallback: Boolean = false,
)

@Serializable
data class Chapter(
    val n: Int,
    val title: String,
    val chars: Int = 0,
    val niebla: String? = null,
    val pista: String? = null,
    val peligro: String? = null,
    val escena: Scene? = null,
    val sprite: SpriteJson? = null,
    val model: String? = null,
    /** Citas originales cuando el narrador las convirtió en susurros (respaldo y edición). */
    val quotes: Map<String, String>? = null,
    /** Ya se intentó narrar (para no repetir llamadas si el validador rechazó todo). */
    val narrated: Boolean = false,
    /** Versión del narrador con la que se narró (si cambia, se vuelve a narrar). */
    val narration: Int = 0,
) {
    /** «peligro» es opcional: en los libros sin texto no se puede insinuar el conflicto sin inventar. */
    val hasText get() = niebla != null && pista != null
    val ready get() = hasText && sprite != null

    fun caption(level: String): String? = when (level) {
        "niebla" -> niebla
        "peligro" -> peligro ?: pista
        else -> pista
    }
}

@Serializable
data class Author(val name: String, @SerialName("death_year") val deathYear: Int? = null)

@Serializable
data class Sources(
    val gutenberg: Int? = null,
    val wikisource: String? = null,
    val imported: Boolean = false,
    val epub: String? = null,
) {
    val label get() = when {
        gutenberg != null -> "Gutenberg"
        wikisource != null -> "Wikisource"
        imported -> "Tu EPUB"
        else -> ""
    }
}

@Serializable
data class Progress(val current: Int = 0, val level: String = "pista", val sealed: Boolean = true)

@Serializable
data class Book(
    val format: Int = 1,
    val id: String,
    val title: String,
    val authors: List<Author> = emptyList(),
    val language: String? = null,
    val genre: String = "clasico",
    val sources: Sources = Sources(),
    val chapters: List<Chapter> = emptyList(),
    val progress: Progress = Progress(),
    val error: String? = null,
    @SerialName("added_at") val addedAt: Long = 0,
) {
    val author get() = authors.joinToString("; ") { it.name }
    val total get() = chapters.size
    val finished get() = total > 0 && progress.current >= total
    val theme get() = themeFor(genre)

    /** El capítulo que el lector va a leer (el siguiente al que terminó). */
    val next: Chapter? get() = chapters.getOrNull(progress.current)

    /** Capítulos que hay que tener listos: el siguiente y, por adelantado, el de después. */
    fun wanted(): List<Int> = listOf(progress.current + 1, progress.current + 2).filter { it in 1..total }

    val pending get() = wanted().any { n -> chapters[n - 1].ready.not() }

    /** Sprite a mostrar: el del capítulo siguiente o, si aún no está, el último que exista antes. */
    val sprite: SpriteJson?
        get() = next?.sprite ?: chapters.take(progress.current + 1).lastOrNull { it.sprite != null }?.sprite

    val caption: String? get() = next?.caption(progress.level)
}

// ---------- tema y niveles ----------
@Serializable
data class Theme(
    val id: String = "otro",
    val name: String = "General",
    val bg: String = "#1f2937",
    val card: String = "#2b3646",
    val border: String = "#9ca3af",
    val text: String = "#f3f4f6",
    val accent: String = "#facc15",
    @SerialName("base_palette") val basePalette: List<String> = emptyList(),
)

enum class Level(val id: String, val label: String, val hint: String) {
    NIEBLA("niebla", "Niebla", "Solo atmósfera"),
    PISTA("pista", "Pista", "Un objeto o lugar clave"),
    PELIGRO("peligro", "Peligro", "Insinúa un giro");

    companion object {
        fun of(id: String) = entries.firstOrNull { it.id == id } ?: PISTA
    }
}

private val GLYPHS = "#%*+"

/** Texto "rúnico" con la forma de la leyenda (mismo algoritmo que la web y la tarjeta del backend). */
fun scramble(caption: String): String =
    caption.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }.mapIndexed { i, w ->
        (0 until maxOf(2, minOf(w.length, 9))).map { j -> GLYPHS[(i + j) % GLYPHS.length] }.joinToString("")
    }.joinToString(" ")
