package com.bookwidget.app.data

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.text.Normalizer

/**
 * Dibujos SIN IA: iconos de game-icons.net (CC BY 3.0, ver assets/icons/ICONS_CREDITS.txt) rasterizados
 * a 32×32 una sola vez (poc/build_icons.py). En el móvil se elige el icono que encaja con las palabras
 * distintivas del capítulo y se colorea con la paleta del tema (contorno, sombra, base, luz y brillo).
 */
object IconSprites {
    @Serializable
    private data class Icon(val name: String, val author: String = "", val es: List<String> = emptyList())

    /** `family`: tamaño de la familia del icono (card-10-clubs, card-2-hearts… son 52 naipes). */
    private class Lib(val icons: List<Icon>, val masks: ByteArray, val index: Map<String, List<Int>>, val family: IntArray)

    @Volatile private var lib: Lib? = null

    /** Iconos de reserva por género, si ninguna palabra del capítulo tiene icono. */
    private val GENRE_FALLBACK = mapOf(
        "terror" to listOf("skull", "bat", "haunting", "candle-light", "spider-web", "coffin"),
        "fantasia" to listOf("crystal-ball", "dragon-head", "magic-swirl", "unicorn", "spell-book"),
        "ciencia_ficcion" to listOf("rocket", "robot-golem", "ringed-planet", "ufo", "satellite"),
        "romance" to listOf("rose", "heart-key", "love-letter", "candlebright", "diamond-ring"),
        "misterio" to listOf("magnifying-glass", "pocket-watch", "key", "letter-bomb", "revolver"),
        "aventura" to listOf("sailboat", "treasure-map", "compass", "palm-tree", "pirate-flag"),
        "infantil" to listOf("teddy-bear", "kite", "rabbit", "balloons", "castle"),
        "historico" to listOf("castle", "crown", "scroll-unfurled", "sword", "quill-ink"),
    )

    private fun norm(s: String): String {
        var w = Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")
        w = w.split(' ').first() // núcleo del sintagma: «reloj de bolsillo» -> «reloj»
        // singular aproximado: «relojes» -> «reloj», «velas» -> «vela»
        if (w.length > 4 && w.endsWith("es") && w[w.length - 3] !in "aeiou") w = w.dropLast(2)
        else if (w.length > 3 && w.endsWith("s")) w = w.dropLast(1)
        return w
    }

    private fun load(ctx: Context): Lib = lib ?: synchronized(this) {
        lib ?: run {
            val icons = Json { ignoreUnknownKeys = true }.decodeFromString<List<Icon>>(
                ctx.assets.open("icons/icons.json").bufferedReader().readText(),
            )
            val masks = ctx.assets.open("icons/masks.bin").readBytes()
            val index = HashMap<String, MutableList<Int>>()
            icons.forEachIndexed { i, ic -> ic.es.forEach { k -> index.getOrPut(norm(k)) { mutableListOf() }.add(i) } }
            val prefix = icons.map { it.name.substringBefore('-') }
            val sizes = prefix.groupingBy { it }.eachCount()
            Lib(icons, masks, index, IntArray(icons.size) { sizes[prefix[it]] ?: 1 }).also { lib = it }
        }
    }

    private fun mask(l: Lib, k: Int): Array<BooleanArray> = Array(32) { y ->
        val o = k * 128 + y * 4
        val row = ((l.masks[o].toInt() and 0xff) shl 24) or ((l.masks[o + 1].toInt() and 0xff) shl 16) or
            ((l.masks[o + 2].toInt() and 0xff) shl 8) or (l.masks[o + 3].toInt() and 0xff)
        BooleanArray(32) { x -> (row ushr (31 - x)) and 1 == 1 }
    }

    private fun hash(s: String) = s.fold(7L) { h, c -> h * 31 + c.code }.let { (it xor (it ushr 17)).toInt() and 0x7fffffff }

    /** Elige icono para las palabras del capítulo, evitando los ya usados en el libro. */
    fun draw(ctx: Context, book: Book, chapter: Chapter, keywords: List<String>): SpriteJson {
        val l = load(ctx)
        val used = book.chapters.mapNotNull { it.sprite?.subject }.toSet()
        var chosen: Pair<Int, String>? = null
        for (w in keywords) {
            val key = norm(w)
            val hits = l.index[key].orEmpty().filter { l.icons[it].name !in used }
            if (hits.isEmpty()) continue
            // mejor: la palabra es el significado principal del icono, en pocas palabras, y no es de una familia
            // enorme de variantes (los 52 naipes no deben ganar a «carta» de correo)
            fun cost(i: Int): Int {
                val es = l.icons[i].es
                val primary = es.firstOrNull()?.let(::norm) == key
                return (if (primary) 0 else 2) + (if (es.firstOrNull()?.contains(' ') == true) 1 else 0) + (if (l.family[i] > 8) 3 else 0)
            }
            val best = hits.minOf(::cost)
            val top = hits.filter { cost(it) == best }
            chosen = top[hash(book.id + chapter.n) % top.size] to w
            break
        }
        if (chosen == null) {
            val names = GENRE_FALLBACK[book.genre].orEmpty().filter { it !in used }.ifEmpty { GENRE_FALLBACK["misterio"]!! }
            val byName = l.icons.withIndex().associate { it.value.name to it.index }
            val k = names.mapNotNull { byName[it] }.let { if (it.isEmpty()) null else it[hash(book.id + chapter.n) % it.size] }
                ?: (hash(book.id + chapter.n) % l.icons.size)
            chosen = k to (l.icons[k].es.firstOrNull() ?: l.icons[k].name.replace('-', ' '))
        }
        val (k, word) = chosen
        return colorize(mask(l, k), book.theme, l.icons[k].name, word.replaceFirstChar { it.uppercase() }, hash(book.id + chapter.n))
    }

    private fun shade(hex: String, f: Float): String {
        val c = android.graphics.Color.parseColor(hex)
        fun ch(v: Int) = (if (f < 0) v * (1 + f) else v + (255 - v) * f).toInt().coerceIn(0, 255)
        return "#%02x%02x%02x".format(ch(android.graphics.Color.red(c)), ch(android.graphics.Color.green(c)), ch(android.graphics.Color.blue(c)))
    }

    private fun colorize(m: Array<BooleanArray>, theme: Theme, iconName: String, name: String, seed: Int): SpriteJson {
        val base = theme.basePalette.drop(2).dropLast(1).let { it[seed % it.size] } // ni el más oscuro ni el acento
        // rampa: contorno, sombra, base, luz, brillo + acento del tema
        val palette = listOf(shade(base, -0.7f), shade(base, -0.35f), base, shade(base, 0.3f), shade(base, 0.6f), theme.accent)
        fun on(x: Int, y: Int) = x in 0..31 && y in 0..31 && m[y][x]
        val rows = (0 until 32).map { y ->
            String(CharArray(32) { x ->
                when {
                    !m[y][x] -> '.'
                    !(on(x - 1, y) && on(x + 1, y) && on(x, y - 1) && on(x, y + 1)) -> '0' // contorno
                    !on(x - 1, y - 1) || !on(x - 2, y - 2) -> '4'                        // luz arriba-izquierda
                    !on(x + 1, y + 1) || !on(x + 2, y + 2) -> '1'                        // sombra abajo-derecha
                    (x + y) % 7 == 0 -> '2'                                              // textura
                    else -> '3'
                }
            })
        }
        // animación idle: un destello del acento junto al dibujo y un brillo que recorre la diagonal
        val top = (0 until 32).first { y -> (0 until 32).any { m[y][it] } }
        val right = (31 downTo 0).first { x -> (0 until 32).any { m[it][x] } }
        val sx = (right + 1).coerceAtMost(30)
        val sy = (top + 1).coerceAtLeast(1)
        val sparkle = listOf(listOf(sx, sy, 5), listOf(sx - 1, sy, 5), listOf(sx + 1, sy, 5), listOf(sx, sy - 1, 5), listOf(sx, sy + 1, 5))
            .filter { (x, y, _) -> x in 0..31 && y in 0..31 && !m[y][x] }
        val shimmer = (0 until 32).flatMap { y -> (0 until 32).filter { x -> rows[y][x] == '3' && (x + y) % 9 == seed % 9 }.map { x -> listOf(x, y, 4) } }.take(40)
        return SpriteJson(name = name.take(24), subject = iconName, palette = palette, pixels = rows, frames = listOf(sparkle, shimmer))
    }
}
