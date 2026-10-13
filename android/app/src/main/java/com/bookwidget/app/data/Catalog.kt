package com.bookwidget.app.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import java.io.IOException

enum class Source(val label: String) {
    GUTENBERG("Gutenberg"),
    WIKISOURCE("Wikisource"),
}

/** Un resultado de búsqueda de cualquier catálogo. `fullText` = se puede descargar y leer el texto real. */
data class SearchResult(
    val id: String,
    val source: Source,
    val title: String,
    val author: String,
    val detail: String,
    val fullText: Boolean,
    val gutenberg: GBook? = null,
    val wikisourcePage: String? = null,
) {
    /** Para la portada pixel que se genera en el móvil. */
    val genre: String get() = gutenberg?.let { genreFromSubjects(it.subjects + it.bookshelves) }
 ?: "clasico"
}

@Serializable
private data class WSHit(val title: String)

@Serializable
private data class WSQuery(val search: List<WSHit> = emptyList())

@Serializable
private data class WSPage(val query: WSQuery = WSQuery())

/**
 * Catálogo: no aloja nada, solo consulta bibliotecas abiertas con el texto completo (dominio público):
 * Gutenberg (Gutendex) y Wikisource. Siempre texto real: los algoritmos y Groq leen el libro de verdad.
 */
object Catalog {
    private val json = Json { ignoreUnknownKeys = true }
    private const val UA = "book-widget/0.3 (lector personal; contacto en el repo)"

    private fun get(url: String): ByteArray {
        val req = Request.Builder().url(url).header("User-Agent", UA).build()
        Gutendex.http.newCall(req).execute().use { r ->
            if (!r.isSuccessful) throw IOException("HTTP ${r.code}")
            return r.body!!.bytes()
        }
    }

    /** Busca en todos a la vez; si una fuente falla, las demás siguen. */
    suspend fun search(query: String, spanishOnly: Boolean): Pair<List<SearchResult>, List<String>> = coroutineScope {
        val errors = mutableListOf<String>()
        val g = async { runCatching { gutenberg(query, spanishOnly) } }
        val w = async { runCatching { wikisource(query) } }
        val all = listOf(Source.GUTENBERG to g.await(), Source.WIKISOURCE to w.await())
            .flatMap { (src, r) -> r.getOrElse { errors += "${src.label}: ${it.message}"; emptyList() } }
        all to errors
    }

    private suspend fun gutenberg(query: String, spanishOnly: Boolean) =
        Gutendex.search(query, if (spanishOnly) "es" else null).take(10).map { b ->
            SearchResult(
                id = "gutenberg-${b.id}", source = Source.GUTENBERG, title = b.title,
                author = b.author.ifBlank { "Anónimo" }, detail = "${b.languages.joinToString()} · ⬇ ${b.downloads}",
                fullText = true, gutenberg = b,
            )
        }

    private suspend fun wikisource(query: String) = withContext(Dispatchers.IO) {
        val url = "https://es.wikisource.org/w/api.php".toHttpUrl().newBuilder()
            .addQueryParameter("action", "query").addQueryParameter("list", "search")
            .addQueryParameter("srsearch", query).addQueryParameter("srnamespace", "0")
            .addQueryParameter("srlimit", "30").addQueryParameter("format", "json")
            .build().toString()
        // los capítulos son subpáginas («Obra/Capítulo I»): solo interesan las obras. La búsqueda de
        // Wikisource es de texto completo: se exige que el título contenga la mitad de las palabras buscadas.
        val words = norm(query).split(' ').filter { it.length > 2 }
        json.decodeFromString<WSPage>(get(url).decodeToString()).query.search
            .map { it.title }.filter { '/' !in it }
            .filter { t -> words.isEmpty() || words.count { it in norm(t) } * 2 >= words.size }
            .take(6).map { t ->
                SearchResult(
                    id = "wikisource-" + t.replace(Regex("[^A-Za-z0-9]+"), "_"), source = Source.WIKISOURCE,
                    title = t, author = "", detail = "es", fullText = true, wikisourcePage = t,
                )
            }
    }

    private fun norm(s: String) = java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFD)
        .replace(Regex("\\p{M}+"), "").lowercase().replace(Regex("[^a-z0-9ñ ]+"), " ").trim()

    suspend fun wikisourceEpub(page: String): ByteArray = withContext(Dispatchers.IO) {
        val url = "https://ws-export.wmcloud.org/".toHttpUrl().newBuilder()
            .addQueryParameter("lang", "es").addQueryParameter("page", page).addQueryParameter("format", "epub-3")
            .build().toString()
        get(url)
    }
}
