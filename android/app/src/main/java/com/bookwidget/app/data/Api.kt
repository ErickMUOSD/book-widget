package com.bookwidget.app.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.builtins.ListSerializer
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

class ApiException(val code: Int, message: String) : Exception(message)

/** Cliente del backend FastAPI. `IOException` = sin red; `ApiException` = el servidor respondió con error. */
class Api(baseUrl: String, private val token: String) {
    private val base = baseUrl.trimEnd('/')
    private val json = Json { ignoreUnknownKeys = true }

    // generar el pixel art puede tardar: lectura generosa
    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(150, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private suspend fun call(method: String, path: String, body: JsonObject? = null): ByteArray =
        withContext(Dispatchers.IO) {
            val payload = (body?.toString() ?: "").toRequestBody("application/json".toMediaType())
            val builder = try {
                Request.Builder().url(base + path)
            } catch (e: IllegalArgumentException) {
                throw ApiException(0, "URL del servidor inválida")
            }
            builder.header("X-Device-Token", token)
            val req = when (method) {
                "GET" -> builder.get()
                "DELETE" -> builder.delete()
                else -> builder.method(method, payload)
            }.build()
            http.newCall(req).execute().use { resp ->
                val bytes = resp.body?.bytes() ?: ByteArray(0)
                if (!resp.isSuccessful) throw ApiException(resp.code, errorMessage(resp.code, bytes))
                bytes
            }
        }

    private fun errorMessage(code: Int, bytes: ByteArray): String {
        val detail = runCatching {
            val d = json.parseToJsonElement(bytes.decodeToString()).jsonObject["detail"]
            (d as? JsonPrimitive)?.contentOrNull ?: d?.toString()
        }.getOrNull()
        return when {
            code == 401 -> "Token inválido o revocado"
            detail != null -> detail
            else -> "Error $code del servidor"
        }
    }

    private suspend inline fun <reified T> get(path: String): T =
        json.decodeFromString(call("GET", path).decodeToString())

    private suspend inline fun <reified T> send(method: String, path: String, body: JsonObject? = null): T =
        json.decodeFromString(call(method, path, body).decodeToString())

    /** Comprueba servidor + token. Lanza si algo falla. */
    suspend fun verify() {
        call("GET", "/books")
    }

    suspend fun books(): List<BookSummary> =
        json.decodeFromString(ListSerializer(BookSummary.serializer()), call("GET", "/books").decodeToString())

    suspend fun createBook(title: String, author: String, synopsis: String, totalChapters: Int?): BookSummary =
        send("POST", "/books", buildJsonObject {
            put("title", title)
            put("author", author)
            put("synopsis", synopsis)
            if (totalChapters != null) put("total_chapters", totalChapters)
        })

    suspend fun chapters(bookId: Long): List<ChapterInfo> = get<BookDetail>("/books/$bookId").chapters

    suspend fun widget(bookId: Long? = null): WidgetState? =
        try {
            get<WidgetState>(if (bookId == null) "/widget" else "/books/$bookId/widget")
        } catch (e: ApiException) {
            if (e.code == 404 && bookId == null) null else throw e
        }

    suspend fun progress(bookId: Long, chapter: Int, level: String): WidgetState =
        send("PUT", "/books/$bookId/progress", buildJsonObject {
            put("current_chapter", chapter)
            put("spoiler_level", level)
        })

    suspend fun advance(bookId: Long): WidgetState = send("POST", "/books/$bookId/advance")

    suspend fun patchState(bookId: Long, sealed: Boolean? = null, level: String? = null): WidgetState =
        send("PATCH", "/books/$bookId/state", buildJsonObject {
            if (sealed != null) put("sealed", sealed)
            if (level != null) put("spoiler_level", level)
        })

    suspend fun regenerate(bookId: Long): WidgetState = send("POST", "/books/$bookId/sprite/regenerate")

    suspend fun activate(bookId: Long) {
        call("POST", "/books/$bookId/activate")
    }

    /** PNG de un frame del sprite (`/sprites/{id}/{n}.png`). */
    suspend fun image(path: String): ByteArray = call("GET", path)

    suspend fun shareCard(bookId: Long, sealed: Boolean): ByteArray =
        call("GET", "/books/$bookId/share-card.png?sealed=$sealed")
}

/** Mensaje legible para el usuario a partir de cualquier fallo de red/servidor. */
fun Throwable.friendly(): String = when (this) {
    is ApiException -> message ?: "Error del servidor"
    is IOException -> "No se pudo conectar con el servidor (¿misma red o Tailscale?)"
    else -> message ?: "Error inesperado"
}
