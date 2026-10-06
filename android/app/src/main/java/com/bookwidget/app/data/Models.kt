package com.bookwidget.app.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class Theme(
    val id: String = "otro",
    val name: String = "General",
    val bg: String = "#1f2937",
    val card: String = "#2b3646",
    val border: String = "#9ca3af",
    val text: String = "#f3f4f6",
    val accent: String = "#facc15",
)

@Serializable
data class SpriteInfo(
    val id: Long,
    val name: String,
    val subject: String = "",
    val chapter: Int = 0,
    @SerialName("frame_count") val frameCount: Int = 1,
    @SerialName("frame_urls") val frameUrls: List<String> = emptyList(),
    val fallback: Boolean = false,
)

@Serializable
data class WidgetState(
    @SerialName("book_id") val bookId: Long,
    val title: String,
    val author: String = "",
    val genre: String = "otro",
    val theme: Theme = Theme(),
    val status: String = "ready",
    @SerialName("status_detail") val statusDetail: String = "",
    @SerialName("current_chapter") val currentChapter: Int = 0,
    @SerialName("total_chapters") val totalChapters: Int = 0,
    @SerialName("next_chapter") val nextChapter: Int? = null,
    val finished: Boolean = false,
    @SerialName("spoiler_level") val spoilerLevel: String = "pista",
    val sealed: Boolean = true,
    @SerialName("is_active") val isActive: Boolean = false,
    val caption: String? = null,
    val sprite: SpriteInfo? = null,
)

@Serializable
data class BookSummary(
    val id: Long,
    val title: String,
    val author: String = "",
    val genre: String = "otro",
    val theme: Theme = Theme(),
    val synopsis: String = "",
    @SerialName("total_chapters") val totalChapters: Int = 0,
    val status: String = "pending",
    @SerialName("status_detail") val statusDetail: String = "",
    @SerialName("is_active") val isActive: Boolean = false,
    @SerialName("current_chapter") val currentChapter: Int = 0,
    @SerialName("spoiler_level") val spoilerLevel: String = "pista",
    val sealed: Boolean = true,
)

@Serializable
data class ChapterInfo(val number: Int, val title: String = "")

/** Solo lo que la app usa de GET /books/{id}. */
@Serializable
data class BookDetail(val chapters: List<ChapterInfo> = emptyList())

enum class Level(val id: String, val icon: String, val label: String, val hint: String) {
    NIEBLA("niebla", "☁️", "Niebla", "Solo atmósfera"),
    PISTA("pista", "🕯️", "Pista", "Un objeto o lugar clave"),
    PELIGRO("peligro", "🔥", "Peligro", "Insinúa un giro");

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
