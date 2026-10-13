package com.bookwidget.app.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

@Serializable
data class GAuthor(val name: String, @SerialName("death_year") val deathYear: Int? = null)

@Serializable
data class GBook(
    val id: Int,
    val title: String,
    val authors: List<GAuthor> = emptyList(),
    val languages: List<String> = emptyList(),
    val subjects: List<String> = emptyList(),
    val bookshelves: List<String> = emptyList(),
    val formats: Map<String, String> = emptyMap(),
    @SerialName("download_count") val downloads: Int = 0,
) {
    val epub get() = formats["application/epub+zip"]
    val author get() = authors.joinToString("; ") { it.name }
}

@Serializable
private data class GPage(val results: List<GBook> = emptyList())

/** Catálogo de Project Gutenberg a través de Gutendex (JSON, sin clave). */
object Gutendex {
    private const val BASE = "https://gutendex.com/books/"
    private val json = Json { ignoreUnknownKeys = true }
    val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .build()

    private fun get(url: String): ByteArray {
        val req = Request.Builder().url(url).header("User-Agent", "book-widget/0.2").build()
        http.newCall(req).execute().use { r ->
            if (!r.isSuccessful) throw IOException("HTTP ${r.code} en $url")
            return r.body!!.bytes()
        }
    }

    suspend fun search(query: String, language: String?): List<GBook> = withContext(Dispatchers.IO) {
        val url = BASE.toHttpUrl().newBuilder().addQueryParameter("search", query.trim()).apply {
            if (!language.isNullOrBlank()) addQueryParameter("languages", language)
        }.build().toString()
        json.decodeFromString<GPage>(get(url).decodeToString()).results.filter { it.epub != null }
    }

    suspend fun epub(book: GBook): ByteArray = withContext(Dispatchers.IO) { get(book.epub!!) }
}
