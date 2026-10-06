package com.bookwidget.app.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

@Serializable
data class Persisted(
    val state: WidgetState? = null,
    val busy: Boolean = false,
    val error: String? = null,
)

/** Lo que dibuja el widget: estado + frames ya decodificados. */
class Snapshot(
    val configured: Boolean,
    val state: WidgetState?,
    val busy: Boolean,
    val error: String?,
    val frames: List<Bitmap>,
    val animate: Boolean,
)

/** Estado del widget en disco (JSON + PNG de los frames) para que dibuje sin red. */
object WidgetStore {
    private val mutex = Mutex()
    private val json = Json { ignoreUnknownKeys = true }

    private fun file(ctx: Context) = File(ctx.filesDir, "widget_state.json")
    private fun framesDir(ctx: Context) = File(ctx.filesDir, "frames").also { it.mkdirs() }
    private fun frameFile(ctx: Context, spriteId: Long, i: Int) = File(framesDir(ctx), "${spriteId}_$i.png")

    private fun read(ctx: Context): Persisted =
        runCatching { json.decodeFromString<Persisted>(file(ctx).readText()) }.getOrDefault(Persisted())

    suspend fun update(ctx: Context, transform: (Persisted) -> Persisted) {
        mutex.withLock {
            withContext(Dispatchers.IO) {
                val tmp = File(ctx.filesDir, "widget_state.json.tmp")
                tmp.writeText(json.encodeToString(Persisted.serializer(), transform(read(ctx))))
                tmp.renameTo(file(ctx))
            }
        }
    }

    suspend fun snapshot(ctx: Context): Snapshot = withContext(Dispatchers.IO) {
        val p = mutex.withLock { read(ctx) }
        val frames = p.state?.sprite?.let { sp ->
            (0 until sp.frameCount).mapNotNull { i ->
                frameFile(ctx, sp.id, i).takeIf { it.exists() }?.let { BitmapFactory.decodeFile(it.path) }
            }
        }.orEmpty()
        Snapshot(
            configured = Prefs.config(ctx) != null,
            state = p.state,
            busy = p.busy,
            error = p.error,
            frames = frames,
            animate = Prefs.animate(ctx),
        )
    }

    /** Descarga los frames que falten y borra los de sprites antiguos. */
    suspend fun ensureFrames(ctx: Context, api: Api, sprite: SpriteInfo?) {
        if (sprite == null) return
        withContext(Dispatchers.IO) {
            sprite.frameUrls.forEachIndexed { i, url ->
                val f = frameFile(ctx, sprite.id, i)
                if (!f.exists()) {
                    val tmp = File(f.path + ".tmp")
                    tmp.writeBytes(api.image(url))
                    tmp.renameTo(f)
                }
            }
            framesDir(ctx).listFiles()?.filter { !it.name.startsWith("${sprite.id}_") }?.forEach { it.delete() }
        }
    }
}
