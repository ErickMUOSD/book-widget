package com.bookwidget.app.data

import android.graphics.Bitmap
import android.graphics.Color

/** Validación, render y sprite de reserva (portado de backend/app/services/sprite.py y render.py). */
object Sprites {
    const val SIZE = 32
    const val MAX_COLORS = 16
    const val MAX_FRAME_DIFFS = 3
    private const val MAX_CHANGES_PER_DIFF = 60
    private const val MIN_FILLED_PIXELS = 40
    const val INDEX_CHARS = "0123456789abcdef"
    const val TRANSPARENT = '.'
    private val HEX = Regex("^#[0-9a-fA-F]{6}$")

    class SpriteError(msg: String) : IllegalArgumentException(msg)

    /** Lanza SpriteError con un mensaje útil (se reenvía al modelo en el reintento). */
    fun validate(raw: SpriteJson): SpriteJson {
        val palette = raw.palette
        if (palette.size !in 2..MAX_COLORS) throw SpriteError("palette debe tener entre 2 y $MAX_COLORS colores")
        if (!palette.all { HEX.matches(it) }) throw SpriteError("palette solo admite colores '#RRGGBB'")
        val rows = raw.pixels
        if (rows.size != SIZE) throw SpriteError("pixels debe tener exactamente $SIZE filas")
        val allowed = TRANSPARENT + INDEX_CHARS.take(palette.size)
        var filled = 0
        rows.forEachIndexed { i, row ->
            if (row.length != SIZE) throw SpriteError("la fila $i debe tener exactamente $SIZE caracteres")
            val bad = row.toSet() - allowed.toSet()
            if (bad.isNotEmpty()) throw SpriteError("la fila $i usa caracteres inválidos $bad; solo '$allowed'")
            filled += row.count { it != TRANSPARENT }
        }
        if (filled < MIN_FILLED_PIXELS) throw SpriteError("el dibujo está casi vacío; dibuja un sujeto completo")
        if (raw.frames.size > MAX_FRAME_DIFFS) throw SpriteError("frames admite como máximo $MAX_FRAME_DIFFS diffs")
        raw.frames.forEachIndexed { fi, diff ->
            if (diff.size > MAX_CHANGES_PER_DIFF) throw SpriteError("el diff $fi supera $MAX_CHANGES_PER_DIFF cambios")
            diff.forEach { ch ->
                if (ch.size != 3) throw SpriteError("el diff $fi debe contener triples [x, y, color]")
                val (x, y, c) = ch
                if (x !in 0 until SIZE || y !in 0 until SIZE) throw SpriteError("el diff $fi tiene coordenadas fuera de 0..31")
                if (c !in -1 until palette.size) throw SpriteError("el diff $fi usa un color fuera de la paleta")
            }
        }
        return raw.copy(name = raw.name.trim().take(40).ifBlank { "Sin nombre" }, palette = palette.map { it.lowercase() })
    }

    fun frameCount(s: SpriteJson) = 1 + s.frames.size

    private fun grid(s: SpriteJson, frame: Int): Array<IntArray> {
        val g = Array(SIZE) { y -> IntArray(SIZE) { x -> s.pixels[y][x].let { if (it == TRANSPARENT) -1 else INDEX_CHARS.indexOf(it) } } }
        if (frame > 0) s.frames[frame - 1].forEach { (x, y, c) -> g[y][x] = c }
        return g
    }

    /** PNG nítido (nearest-neighbor) de un frame. */
    fun bitmap(s: SpriteJson, frame: Int, scale: Int = 8): Bitmap {
        val colors = s.palette.map { Color.parseColor(it) }
        val bmp = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
        grid(s, frame).forEachIndexed { y, row ->
            row.forEachIndexed { x, idx -> bmp.setPixel(x, y, if (idx >= 0) colors[idx] else Color.TRANSPARENT) }
        }
        return Bitmap.createScaledBitmap(bmp, SIZE * scale, SIZE * scale, false)
    }

    fun bitmaps(s: SpriteJson): List<Bitmap> = (0 until frameCount(s)).map { bitmap(s, it) }

    /** Librito con cara y dos frames (parpadeo / saltito) para cuando el modelo falla. */
    fun fallback(): SpriteJson {
        val g = Array(SIZE) { CharArray(SIZE) { TRANSPARENT } }
        fun rect(x0: Int, y0: Int, x1: Int, y1: Int, c: Int) {
            for (y in y0..y1) for (x in x0..x1) g[y][x] = INDEX_CHARS[c]
        }
        rect(7, 6, 24, 27, 0); rect(8, 7, 23, 26, 1); rect(9, 8, 22, 25, 2); rect(8, 7, 8, 26, 0)
        rect(11, 10, 20, 21, 3); rect(13, 13, 14, 15, 0); rect(17, 13, 18, 15, 0)
        rect(14, 18, 17, 18, 5); rect(13, 17, 13, 17, 5); rect(18, 17, 18, 17, 5)
        rect(11, 28, 14, 29, 0); rect(17, 28, 20, 29, 0); rect(12, 3, 19, 5, 4); rect(15, 2, 16, 2, 4)
        val blink = listOf(13 to 14, 13 to 15, 14 to 14, 14 to 15, 17 to 14, 17 to 15, 18 to 14, 18 to 15).map { listOf(it.first, it.second, 3) }
        val sparkle = listOf(listOf(15, 2, -1), listOf(16, 2, -1), listOf(12, 3, -1), listOf(19, 3, -1), listOf(15, 1, 4), listOf(16, 1, 4))
        return SpriteJson(
            name = "Librito", subject = "", fallback = true,
            palette = listOf("#1c1230", "#6c4bd1", "#9b8cff", "#fff4d6", "#ffd166", "#ff6b9d"),
            pixels = g.map { String(it) }, frames = listOf(blink, sparkle),
        )
    }
}
