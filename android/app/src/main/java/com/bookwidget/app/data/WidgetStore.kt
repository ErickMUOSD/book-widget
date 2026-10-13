package com.bookwidget.app.data

import android.content.Context
import android.graphics.Bitmap

/** Lo que dibuja el widget: el libro activo + los frames ya renderizados. */
class Snapshot(
    val configured: Boolean,
    val book: Book?,
    val busy: Boolean,
    val frames: List<Bitmap>,
    val animate: Boolean,
)

object WidgetStore {
    /** Frames ya recortados por sprite: el widget se repinta a menudo y el dibujo casi nunca cambia. */
    private val cache = android.util.LruCache<SpriteJson, List<Bitmap>>(8)

    suspend fun snapshot(ctx: Context): Snapshot {
        val book = Library.active(ctx)
        return Snapshot(
            configured = Prefs.groqKey(ctx).isNotBlank(),
            book = book,
            busy = book != null && book.error == null && book.next?.ready == false,
            frames = book?.sprite?.let { sp -> cache.get(sp) ?: crop(Sprites.bitmaps(sp)).also { cache.put(sp, it) } }.orEmpty(),
            animate = Prefs.animate(ctx),
        )
    }

    /** Recorta el margen transparente común a todos los frames (cuadrado), para que el dibujo llene su caja. */
    private fun crop(frames: List<Bitmap>): List<Bitmap> {
        if (frames.isEmpty()) return frames
        val w = frames[0].width
        val h = frames[0].height
        var l = w; var t = h; var r = -1; var b = -1
        val px = IntArray(w * h)
        for (f in frames) {
            f.getPixels(px, 0, w, 0, 0, w, h) // de una vez: getPixel uno a uno era lento
            for (i in px.indices) if ((px[i] ushr 24) != 0) {
                val x = i % w; val y = i / w
                if (x < l) l = x; if (x > r) r = x; if (y < t) t = y; if (y > b) b = y
            }
        }
        if (r < 0) return frames
        val pad = w / 32 // 1 píxel del dibujo de margen
        val side = maxOf(r - l, b - t) + 1 + 2 * pad
        val cx = (l + r) / 2
        val cy = (t + b) / 2
        val x0 = (cx - side / 2).coerceIn(0, maxOf(0, w - side))
        val y0 = (cy - side / 2).coerceIn(0, maxOf(0, h - side))
        val s = minOf(side, w, h)
        return frames.map { Bitmap.createBitmap(it, x0, y0, s, s) }
    }
}
