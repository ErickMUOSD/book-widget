package com.bookwidget.app.data

import android.graphics.Bitmap
import android.graphics.Color
import android.util.LruCache

/**
 * Miniaturas generadas en el propio móvil (sin descargar portadas ni pedirlas a un servidor):
 * una «portada» pixel art determinista a partir del id del libro y la paleta de su género.
 */
object Thumbnails {
    private const val W = 24
    private const val H = 32
    private val cache = LruCache<String, Bitmap>(64)

    /** FNV-1a: mismo id -> misma portada en cualquier dispositivo. */
    private fun hash(s: String): Long {
        var h = 0xcbf29ce484222325uL.toLong()
        for (c in s) {
            h = h xor c.code.toLong()
            h *= 0x100000001b3L
        }
        return h
    }

    fun cover(id: String, genre: String, scale: Int = 4): Bitmap = cache.get("$id|$genre|$scale") ?: run {
        val pal = themeFor(genre).basePalette.map { Color.parseColor(it) }
        var seed = hash(id)
        fun next(): Int {
            seed = seed xor (seed shl 13); seed = seed xor (seed ushr 7); seed = seed xor (seed shl 17)
            return (seed ushr 1).toInt() and 0x7fffffff
        }
        val dark = pal[0]
        val cover = pal[1 + next() % 3]
        val light = pal[3 + next() % (pal.size - 3)]
        val accent = pal[(4 + next() % (pal.size - 4)).coerceAtMost(pal.lastIndex)]
        val bmp = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
        for (y in 0 until H) for (x in 0 until W) {
            val c = when {
                x == 0 || y == 0 || x == W - 1 || y == H - 1 -> dark // contorno
                x in 1..3 -> if (y % 4 == 0) light else dark        // lomo con costuras
                y == 4 || y == H - 5 -> light                       // filetes
                else -> cover
            }
            bmp.setPixel(x, y, c)
        }
        // emblema simétrico 7×7 (tipo identicon) en el centro de la tapa
        val ox = 9
        val oy = 11
        for (yy in 0 until 7) for (xx in 0 until 4) {
            if (next() % 5 < 2) continue
            val c = if (next() % 4 == 0) accent else light
            bmp.setPixel(ox + xx, oy + yy, c)
            bmp.setPixel(ox + 6 - xx, oy + yy, c)
        }
        Bitmap.createScaledBitmap(bmp, W * scale, H * scale, false).also { cache.put("$id|$genre|$scale", it) }
    }

    /** Miniatura del libro: el sprite del capítulo en curso si ya existe; si no, su portada. */
    fun forBook(book: Book): Bitmap = book.sprite?.let { Sprites.bitmap(it, 0, 3) } ?: cover(book.id, book.genre)
}
