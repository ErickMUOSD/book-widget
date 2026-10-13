package com.bookwidget.app.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.text.Normalizer
import java.util.concurrent.TimeUnit

/**
 * La única IA de la app: Groq (API compatible con OpenAI, clave gratuita del usuario).
 * No decide QUÉ contar: eso lo eligen los algoritmos clásicos sobre el texto real (Classic.kt).
 * Solo convierte la cita en un «susurro»; un validador clásico rechaza lo que invente y, si algo
 * falla, se queda la cita. Probado en poc/narrator.py (gpt-oss-120b: 16/18, español 12/12).
 */
object Groq {
    const val KEY_URL = "https://console.groq.com/keys"
    const val MAX_WORDS = 50
    private const val BASE = "https://api.groq.com/openai/v1"
    /** Por orden de preferencia; si Groq retira uno, se elige el siguiente disponible. */
    private val PREFERRED = listOf("openai/gpt-oss-120b", "qwen/qwen3.8-27b", "openai/gpt-oss-20b")
    @Volatile private var model: String? = null

    class BadKey : IOException("Groq no acepta la clave. Crea una nueva y cópiala con su botón de copiar.")

    private val json = Json { ignoreUnknownKeys = true }
    private val http = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).build()

    /** Versión del narrador: los capítulos narrados con una versión anterior se vuelven a narrar. */
    const val VERSION = 2

    // Se le da el PASAJE real (la cita y sus frases vecinas): con más material escribe leyendas de ~4 renglones
    // sin inventar. Probado en poc/narrator.py (PROMPT=pasaje): 18/18 aceptadas con gpt-oss-120b.
    private const val PASSAGE = "Eres el narrador misterioso de un juego para lectores. Te doy un PASAJE real de un libro. Escribe una leyenda " +
        "que despierte las ganas de leerlo: 2 o 3 frases, entre 30 y 45 palabras, en español y en presente, con tono evocador. " +
        "Usa SOLO personas, objetos, lugares y hechos que aparecen en el pasaje; no inventes nada y no reveles cómo termina. " +
        "No digas «capítulo», «spoiler» ni «pasaje». Responde solo con la leyenda."
    private val SHOTS = mapOf(
        "niebla" to ("La niebla cubría el puerto desde el amanecer. Las farolas apenas alumbraban los barcos dormidos, y el agua golpeaba los pilotes con un rumor lento. Nadie caminaba por el muelle; solo un perro buscaba algo entre las redes." to
            "La niebla se traga el puerto desde el amanecer. Las farolas apenas alumbran barcos dormidos mientras el agua golpea los pilotes con un rumor lento. En el muelle vacío, solo un perro busca algo entre las redes."),
        "pista" to ("El capitán guardó el mapa en el cofre y lo cerró con una llave de hierro. Después colgó la llave de su cuello y subió a cubierta sin decir palabra. Los marineros se miraron: nadie había visto antes aquel mapa." to
            "Un mapa que nadie había visto antes. Un cofre cerrado con una llave de hierro que ahora cuelga del cuello del capitán. Él sube a cubierta en silencio, y los marineros se miran sin atreverse a preguntar."),
        "peligro" to ("Oyó pasos en la escalera y apagó la vela con la mano temblorosa. Los pasos se detuvieron frente a su puerta. Contuvo el aliento; alguien probaba el picaporte, muy despacio." to
            "Pasos en la escalera. Una vela que se apaga con mano temblorosa. Frente a la puerta, los pasos se detienen, y alguien prueba el picaporte, muy despacio, mientras ella contiene el aliento."),
    )

    /** Palabras de tono que el narrador puede añadir sin inventar hechos. */
    private val MOOD = """
        susurro susurra susurran sombra sombras misterio secreto secretos algo alguien aguarda aguardan espera esperan
        quizá quizás pronto nadie silencio silencioso silenciosa pesa pesan late laten acecha acechan umbral cada mientras
        aunque oculto oculta ocultos tiempo momento instante todavía nunca siempre como cuando donde aquí allí ahora
        insistente lento lenta despacio callado callada inquieto inquieta extraño extraña cierto cierta otra otro
        embargo entre parece parecía parecen podría puede acaso apenas casi solo sólo tanto demasiado
        misterioso misteriosa misteriosos inquietante oscuro oscura oscuros profundo profunda frío fría lejano lejana
        tarde noche vida mundo destino promesa pregunta respuesta verdad mentira miedo temor duda deseo
        susurrando esperando aguardando ocurrirá ocurre pasa pasará viene vendrá queda quedan
    """.trimIndent().split(Regex("\\s+")).toSet()
    private val EN = setOf("the", "and", "of", "to", "was", "his", "her", "with", "that", "were", "had", "from", "which")
    private val WORD = Regex("[A-Za-zÁÉÍÓÚÜÑáéíóúüñ]+")

    private fun stem(w: String) = Normalizer.normalize(w.lowercase(), Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "").take(5)
    private fun msg(role: String, content: String) = buildJsonObject { put("role", role); put("content", content) }

    private fun send(key: String, body: String, path: String = "/chat/completions"): Pair<Int, String> {
        val b = Request.Builder().url(BASE + path).header("Authorization", "Bearer $key")
        val req = if (path == "/models") b.get().build() else b.post(body.toRequestBody("application/json".toMediaType())).build()
        http.newCall(req).execute().use { return it.code to it.body?.string().orEmpty() }
    }

    /** Modelo a usar: el preferido que Groq ofrezca hoy (se consulta una vez por sesión). */
    private fun resolveModel(key: String): String {
        model?.let { return it }
        val (code, text) = send(key, "", "/models")
        if (code == 401 || code == 403) throw BadKey()
        val ids = runCatching { json.parseToJsonElement(text).jsonObject["data"]!!.jsonArray.map { it.jsonObject["id"]!!.jsonPrimitive.content } }.getOrDefault(emptyList())
        val chosen = PREFERRED.firstOrNull { it in ids }
            ?: ids.firstOrNull { id -> listOf("whisper", "guard", "orpheus", "tts", "allam").none { it in id } }
            ?: PREFERRED.first()
        model = chosen
        return chosen
    }

    private suspend fun chat(key: String, messages: JsonArray): String = withContext(Dispatchers.IO) {
        repeat(2) {
            val m = resolveModel(key)
            val body = buildJsonObject {
                put("model", m); put("temperature", 0.5); put("max_tokens", 600); put("messages", messages)
                // los modelos de Groq razonan antes de responder: que piensen poco y no devuelvan el razonamiento
                put("reasoning_format", "hidden"); put("reasoning_effort", if ("qwen" in m) "none" else "low")
            }.toString()
            val (code, text) = send(key, body)
            when {
                code == 200 -> {
                    val content = json.parseToJsonElement(text).jsonObject["choices"]!!.jsonArray[0].jsonObject["message"]!!.jsonObject["content"]!!.jsonPrimitive.content
                    return@withContext content.replace(Regex("<think>.*?</think>", RegexOption.DOT_MATCHES_ALL), "")
                        .split(Regex("\\s+")).joinToString(" ").trim('"', '«', '»', '“', '”', ' ')
                }
                code == 401 || code == 403 -> throw BadKey()
                code == 404 || "model" in text && code == 400 -> model = null // modelo retirado: se elige otro
                code == 429 -> throw IOException("Groq: límite gratuito alcanzado por ahora. Se usan las citas.")
                else -> throw IOException("Groq: HTTP $code ${text.take(120)}")
            }
        }
        throw IOException("Groq no tiene ningún modelo disponible ahora")
    }

    private suspend fun translate(key: String, quote: String) = chat(key, buildJsonArray {
        add(msg("system", "Traduce al español el texto del usuario, de forma fiel y natural. Responde solo con la traducción."))
        add(msg("user", quote))
    })

    /** Null si vale; si no, el motivo (reglas clásicas, baratas y deterministas). */
    fun validate(text: String, quote: String, known: List<String>): String? {
        val words = text.split(' ').filter { it.isNotBlank() }
        if (words.size !in 20..MAX_WORDS) return "${words.size} palabras"
        val low = text.lowercase()
        if (listOf("capítulo", "spoiler", "pasaje", "narrador", "leyenda").any { it in low }) return "palabra prohibida"
        val toks = WORD.findAll(text).map { it.value.lowercase() }.toList()
        if (toks.count { it in EN } >= 2) return "en inglés"
        val qstems = WORD.findAll(quote).map { stem(it.value) }.toSet() + known.flatMap { k -> k.split(' ').map(::stem) }
        val content = toks.filter { it.length > 3 && it !in Classic.STOP && it !in MOOD }
        val loose = content.filter { stem(it) !in qstems }
        if (content.isNotEmpty() && loose.size.toDouble() / content.size > 0.4) return "inventa: ${loose.take(3)}"
        val qset = WORD.findAll(quote).map { it.value.lowercase() }.toSet()
        val jacc = (toks.toSet() intersect qset).size.toDouble() / ((toks.toSet() union qset).size.coerceAtLeast(1))
        if (words.size >= 0.85 * quote.split(' ').size && jacc > 0.7) return "copia la cita"
        val allowed = qset + known.flatMap { k -> k.lowercase().split(' ') }
        WORD.findAll(text).forEachIndexed { i, m ->
            val w = m.value
            val before = text.substring(0, m.range.first).trimEnd()
            val start = i == 0 || before.endsWith('.') || before.endsWith('!') || before.endsWith('?') || before.endsWith('¿') || before.endsWith('¡')
            if (w[0].isUpperCase() && !start && w.lowercase() !in allowed && w.lowercase() !in Classic.STOP) return "nombre nuevo: $w"
        }
        return null
    }

    /** Leyenda narrada a partir del PASAJE real. Null si no pasa el validador (se queda la cita). */
    suspend fun whisper(key: String, level: String, passage: String, language: String?, known: List<String>): String? {
        val source = if (language != null && language != "es") translate(key, passage) else passage
        val messages = buildJsonArray {
            add(msg("system", PASSAGE))
            (listOf("niebla", "pista", "peligro").filter { it != level } + level).forEach { lv ->
                add(msg("user", "PASAJE: ${SHOTS.getValue(lv).first}")); add(msg("assistant", SHOTS.getValue(lv).second))
            }
            add(msg("user", "PASAJE: $source"))
        }
        repeat(2) {
            val out = chat(key, messages)
            if (validate(out, source, known) == null) return out
        }
        return null
    }

    /** Prueba de la clave en el paso de configuración: un susurro de ejemplo. */
    suspend fun test(key: String): String {
        model = null
        return whisper(key, "pista", "La vieja buscó las llaves en el bolsillo y entró en la habitación contigua. El joven se quedó solo, " +
            "escuchando; oyó cómo abría la cómoda. Pensó que la llave grande, la de muescas, sería la del cofre.", "es", emptyList())
            ?: "La clave funciona (la respuesta de prueba no pasó el validador, pero eso es normal a veces)."
    }
}
