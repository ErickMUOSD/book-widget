package com.bookwidget.app.widget

import android.graphics.Bitmap
import android.graphics.Color
import android.util.LruCache

/**
 * Iconos pixel art para el widget (Glance no puede usar los iconos ni la fuente de la app).
 * Cada icono es una máscara pequeña que se pinta del color pedido y se escala sin suavizado.
 */
object PixelIcons {
    val CLOUD = listOf(
        "...##.....",
        "..####.##.",
        ".#########",
        "##########",
        "##########",
        ".########.",
    )
    val KEY = listOf(
        ".###......",
        "#...#.....",
        "#...######",
        "#...#.#.#.",
        ".###......",
    )
    val FIRE = listOf(
        "...#....",
        "...##...",
        "..###.#.",
        "..#####.",
        ".######.",
        ".##.###.",
        "##..####",
        "##...###",
        ".######.",
    )
    val LOCK = listOf(
        "..####..",
        ".#....#.",
        ".#....#.",
        "########",
        "###..###",
        "###..###",
        "########",
    )
    val PLAY = listOf(
        "#.....",
        "###...",
        "#####.",
        "######",
        "#####.",
        "###...",
        "#.....",
    )

    private val cache = LruCache<String, Bitmap>(32)

    fun bitmap(mask: List<String>, color: Int, scale: Int = 6): Bitmap {
        val key = "${mask.hashCode()}|$color|$scale"
        cache.get(key)?.let { return it }
        val w = mask.maxOf { it.length }
        val h = mask.size
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        mask.forEachIndexed { y, row -> row.forEachIndexed { x, c -> bmp.setPixel(x, y, if (c == '#') color else Color.TRANSPARENT) } }
        return Bitmap.createScaledBitmap(bmp, w * scale, h * scale, false).also { cache.put(key, it) }
    }
}
