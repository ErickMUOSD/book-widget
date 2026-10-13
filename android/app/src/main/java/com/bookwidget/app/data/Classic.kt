package com.bookwidget.app.data

import kotlin.math.ln
import kotlin.math.max
import kotlin.math.pow

/**
 * Pre-spoilers SIN IA con algoritmos clásicos sobre el texto real (portado de poc/classic.py):
 * - TF-IDF (Salton): lo distintivo de cada capítulo frente al resto del libro.
 * - TextRank (Mihalcea y Tarau, 2004): centralidad de frases por solapamiento léxico.
 * - MMR (Carbonell y Goldstein, 1998): no elegir dos frases que dicen lo mismo.
 * - Posición (Edmundson, 1969): el final resuelve, así que nunca se cita el último cuarto.
 * - Léxicos propios de atmósfera y tensión (sin licencias restrictivas).
 * Las leyendas son citas del capítulo, como el texto de una contraportada.
 */
object Classic {
    val STOP = """
        a al algo algunas algunos ante antes aquel aquella aquellas aquello aquellos aqui aquí asi así aun aún
        bajo bien cada casi como cómo con contra cual cuál cuando cuándo de del desde donde dónde dos el él ella
        ellas ello ellos en entre era eran eres es esa esas ese eso esos esta está estaba estaban estado estar
        estas este esto estos estoy fue fueron fui ha había habían haber habia hace hacia hasta hay he la las le
        les lo los mas más me mi mí mis mismo mucho muy nada ni no nos nosotros o os otra otro otros para pero
        poco por porque que qué quien quién se sea ser si sí sido siempre sin sino sobre su sus tal también tan
        tanto te tenía tener ti tiene todo todos tras tu tú tus un una uno unos usted ustedes ya yo vez veces
        dijo decía dice luego entonces pues después ahora cual cuales mientras durante tenia habia
        señor señora don doña cosa cosas día días hombre mujer parte nunca tiempo
        the a an and of to in is was he she it that his her had with for as at by on not be but they you i him
    """.trimIndent().split(Regex("\\s+")).toSet()

    val ATMOS = """
        luz sombra sombras niebla bruma frío fría calor viento lluvia noche oscuridad oscuro oscura silencio olor
        olía ruido rumor polvo humo sol luna nubes cielo calle calles nieve hielo fuego llama vela velas gris
        pálido pálida amarillo amarillas rojo negra negro blanco blanca húmedo húmeda tibio sofocante bochorno
        hedor aire tormenta trueno relámpago crepúsculo madrugada aurora atardecer tarde mañana lejos eco
        murmullo susurro brillo resplandor tinieblas penumbra helado helada ardiente polvoriento espeso rancio
        light shadow fog mist cold wind rain night dark darkness silence smell snow moon sky fire grey pale
    """.trimIndent().split(Regex("\\s+")).toSet()

    private val TENSION = """
        muerte muerto muerta morir matar asesinato asesino sangre miedo terror temor horror pánico grito gritó
        gritos peligro amenaza crimen golpe hacha cuchillo arma pistola veneno huir huyó escapar desesperación
        angustia culpa furia rabia odio celos traición secreto prisión preso cárcel enemigo lucha pelea herida
        temblaba temblando temblor estremeció horrible terrible espantoso siniestro fatal ruina deuda locura
        fiebre delirio desmayo sospecha acusación juicio policía
        death dead kill blood fear terror horror scream danger threat crime knife gun poison escape wolf wolves
    """.trimIndent().split(Regex("\\s+")).toSet()

    private val WORD = Regex("[A-Za-zÁÉÍÓÚÜÑáéíóúüñ]+")
    private val SENT = Regex("(?<=[.!?…»])\\s+(?=[—\\-«\"¿¡A-ZÁÉÍÓÚÑ])")
    private val VERBISH = listOf("aba", "aban", "ían", "ando", "iendo", "ado", "ada", "ido", "ida", "mente", "ría", "rían", "ase", "iese")

    fun toks(s: String) = WORD.findAll(s).map { it.value.lowercase() }.toList()
    fun content(ws: List<String>) = ws.filter { it.length > 3 && it !in STOP }

    fun sentences(text: String): List<String> {
        // algunas ediciones cortan cada línea del párrafo: un salto simple es un espacio
        val joined = text.replace(Regex("(?<!\\n)\\n(?!\\n)"), " ").replace(Regex("\\[\\d+\\]"), "")
        return joined.split(Regex("\\n\\s*\\n")).flatMap { para ->
            SENT.split(para.split(Regex("\\s+")).joinToString(" ")).map { it.trim() }.filter { it.length > 1 }
        }
    }

    /** Nombres propios: palabras con mayúscula que no abren frase («Catalina Ivanovna»), al menos 2 menciones. */
    fun entities(sents: List<String>): List<String> {
        val c = HashMap<String, Int>()
        for (s in sents) {
            val words = WORD.findAll(s).map { it.value }.toList()
            var i = 1
            while (i < words.size) {
                val w = words[i]
                if (w[0].isUpperCase() && w.lowercase() !in STOP && w.length > 2) {
                    var j = i
                    while (j + 1 < words.size && words[j + 1][0].isUpperCase() && words[j + 1].lowercase() !in STOP) j++
                    val e = words.subList(i, j + 1).joinToString(" ")
                    c[e] = (c[e] ?: 0) + 1
                    i = j + 1
                } else i++
            }
        }
        return c.filter { it.value >= 2 }.entries.sortedByDescending { it.value }.map { it.key }
    }

    /** Frecuencias de documento del libro (cada capítulo es un documento). */
    class BookStats(texts: List<String>) {
        val n = texts.size
        val df = HashMap<String, Int>().also { df -> texts.forEach { t -> content(toks(t)).toSet().forEach { df[it] = (df[it] ?: 0) + 1 } } }
    }

    fun distinctive(text: String, stats: BookStats, k: Int = 12): List<String> {
        val tf = content(toks(text)).groupingBy { it }.eachCount()
        val total = tf.values.sum().coerceAtLeast(1)
        return tf.filter { it.value >= 2 }
            .mapValues { (w, c) -> (c.toDouble() / total) * ln((stats.n + 1.0) / ((stats.df[w] ?: 0) + 0.5)) }
            .entries.sortedByDescending { it.value }.take(k).map { it.key }
    }

    fun textrank(bags: List<Set<String>>, iters: Int = 30, d: Double = 0.85): DoubleArray {
        val n = bags.size
        val index = HashMap<String, MutableList<Int>>()
        bags.forEachIndexed { i, b -> b.forEach { index.getOrPut(it) { mutableListOf() }.add(i) } }
        val nbrs = Array(n) { mutableListOf<Pair<Int, Double>>() }
        bags.forEachIndexed { i, b ->
            if (b.size < 2) return@forEachIndexed
            val overlap = HashMap<Int, Int>()
            b.forEach { w -> index[w]!!.forEach { j -> if (j != i) overlap[j] = (overlap[j] ?: 0) + 1 } }
            overlap.forEach { (j, o) ->
                if (bags[j].size >= 2) nbrs[i] += j to o / (ln(b.size + 1.0) + ln(bags[j].size + 1.0))
            }
        }
        val outW = DoubleArray(n) { i -> nbrs[i].sumOf { it.second }.takeIf { it > 0 } ?: 1.0 }
        var score = DoubleArray(n) { 1.0 }
        repeat(iters) {
            val prev = score
            score = DoubleArray(n) { i -> (1 - d) + d * nbrs[i].sumOf { (j, w) -> prev[j] * w / outW[j] } }
        }
        return score
    }

    /** Recorta a ≤40 palabras por la última pausa natural para que la cita no quede mutilada. */
    fun trim(s: String, maxWords: Int = 40): String {
        val clean = s.split(Regex("\\s+")).joinToString(" ").trim(' ', '—', '-', '«', '»', '"')
        val words = clean.split(' ')
        if (words.size <= maxWords) return clean
        val cut = words.take(maxWords).joinToString(" ")
        val m = maxOf(cut.lastIndexOf(','), cut.lastIndexOf(';'), cut.lastIndexOf(':'))
        return (if (m > cut.length / 2) cut.substring(0, m) else cut).trimEnd(',', ';', ':', ' ') + "…"
    }

    /** La cita y las frases que la rodean (1 antes, 2 después): material real para una leyenda más larga. */
    fun passage(text: String, quote: String, before: Int = 1, after: Int = 2): String {
        val sents = sentences(text)
        val probe = quote.trimEnd('…').trim().take(40)
        val i = sents.indexOfFirst { probe.isNotEmpty() && probe in it }
        return if (i < 0) quote else sents.subList(maxOf(0, i - before), minOf(sents.size, i + after + 1)).joinToString(" ")
    }

    data class Result(val niebla: String, val pista: String, val peligro: String, val scene: Scene, val keywords: List<String>)

    fun captions(text: String, stats: BookStats): Result {
        val sents = sentences(text)
        val n = sents.size
        val bags = sents.map { content(toks(it)).toSet() }
        val tr = textrank(bags)
        val maxTr = tr.maxOrNull()?.takeIf { it > 0 } ?: 1.0
        val ents = entities(sents)
        val dist = distinctive(text, stats)
        fun ok(i: Int) = sents[i].split(' ').size in 8..70 && !sents[i].startsWith("—") && !sents[i].startsWith("-") && !sents[i].startsWith("«")
        fun window(a: Double, b: Double) = ((n * a).toInt() until (n * b).toInt().coerceAtMost(n)).filter(::ok)
        val used = mutableSetOf<Int>()

        /** MMR: relevancia menos parecido con lo ya elegido. */
        fun pick(cands: List<Pair<Int, Double>>): String? {
            val best = cands.filter { it.first !in used }.maxByOrNull { (i, sc) ->
                val sim = used.maxOfOrNull { j ->
                    val inter = (bags[i] intersect bags[j]).size.toDouble()
                    inter / ((bags[i] union bags[j]).size.coerceAtLeast(1))
                } ?: 0.0
                0.7 * sc - 0.3 * sim * max(1.0, sc)
            } ?: return null
            used += best.first
            return trim(sents[best.first])
        }

        val niebla = pick(window(0.0, 0.5).filter { !sents[it].endsWith("?") }.map { i ->
            val ws = toks(sents[i])
            i to (ws.count { it in ATMOS } / ws.size.toDouble().pow(0.5) - 0.6 * ents.count { it in sents[i] } + 0.1 * tr[i] / maxTr)
        })
        val top = dist.take(6).toSet()
        val pista = pick(window(0.0, 0.67).map { i ->
            i to (tr[i] / maxTr + 0.8 * (top intersect bags[i]).size + 0.5 * ents.take(3).count { it in sents[i] })
        })
        val peligro = pick(window(0.25, 0.75).map { i ->
            i to (toks(sents[i]).count { it in TENSION } * 1.5 + sents[i].count { it == '!' } * 0.5 + 0.3 * tr[i] / maxTr)
        })
        val fallback = sents.firstOrNull { it.split(' ').size >= 8 }?.let(::trim) ?: "…"

        // candidatos dibujables: términos distintivos con pinta de sustantivo y, después, nombres propios
        val nouns = dist.filter { w -> VERBISH.none { w.endsWith(it) } && !(w.length > 5 && (w.endsWith("ar") || w.endsWith("er") || w.endsWith("ir"))) }
        val elements = nouns.take(5).map { Element("objeto", it, "") } + ents.take(3).map { Element("personaje", it, "") }
        val scene = Scene(nudo = sents.take(2).joinToString(" ").let(::trim), desenlace = "", elementos = elements)
        return Result(niebla ?: fallback, pista ?: fallback, peligro ?: fallback, scene, nouns + dist)
    }
}
