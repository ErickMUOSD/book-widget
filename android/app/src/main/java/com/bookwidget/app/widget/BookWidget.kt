package com.bookwidget.app.widget

import android.content.Context
import android.graphics.Bitmap
import android.widget.RemoteViews
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalContext
import androidx.glance.LocalSize
import androidx.glance.action.Action
import androidx.glance.action.actionParametersOf
import androidx.glance.action.clickable
import androidx.compose.ui.graphics.toArgb
import androidx.glance.appwidget.AndroidRemoteViews
import androidx.glance.appwidget.CircularProgressIndicator
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.ContentScale
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.text.FontFamily
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextAlign
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.bookwidget.app.MainActivity
import com.bookwidget.app.R
import com.bookwidget.app.data.Book
import com.bookwidget.app.data.Snapshot
import com.bookwidget.app.data.Theme
import com.bookwidget.app.data.Library
import com.bookwidget.app.data.WidgetStore
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import kotlinx.coroutines.flow.debounce
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.bookwidget.app.data.scramble

class BookWidget : GlanceAppWidget() {
    // 2×2: personaje + «Terminé ✓» · 4×2: + leyenda sellada y «Romper sello» · grande: personaje mayor
    // Exact: el diseño se calcula con el tamaño real del widget (Responsive daba el de la plantilla y sobraba espacio)
    override val sizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val first = WidgetStore.snapshot(context)
        provideContent {
            // La sesión de Glance sigue viva entre actualizaciones: el estado se relee en cada cambio de la
            // biblioteca (antes se leía una sola vez y el widget mostraba datos viejos tras «Terminé»).
            // agrupadas: mientras se prepara un capítulo hay muchos cambios seguidos y cada repintado reenvía los bitmaps
            @OptIn(kotlinx.coroutines.FlowPreview::class)
            val revision by remember { Library.revision.debounce(150) }.collectAsState(Library.revision.value)
            val ui by WidgetFeedback.ui.collectAsState()
            LaunchedEffect(revision) { WidgetFeedback.reload(context) }
            WidgetContent(ui.snap ?: first, if (ui.busy) "busy" else null)
        }
    }

    companion object {
        val SMALL = DpSize(110.dp, 110.dp)
        val SMALL_TALL = DpSize(110.dp, 170.dp)
        val WIDE = DpSize(250.dp, 110.dp)
        val WIDE_TALL = DpSize(250.dp, 170.dp)
        val BIG = DpSize(250.dp, 250.dp)
    }
}

class BookWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = BookWidget()

}

private fun color(hex: String, fallback: Color): Color =
    runCatching { Color(android.graphics.Color.parseColor(hex)) }.getOrDefault(fallback)

private class Palette(t: Theme) {
    val bg = color(t.bg, Color(0xFF1F2937))
    val card = color(t.card, Color(0xFF2B3646))
    val border = color(t.border, Color(0xFF9CA3AF))
    val text = color(t.text, Color(0xFFF3F4F6))
    val accent = color(t.accent, Color(0xFFFACC15))
}

@Composable
private fun WidgetContent(snap: Snapshot, pending: String?) {
    val pal = Palette(snap.book?.theme ?: Theme())
    val open = actionStartActivity<MainActivity>()
    Box(
        modifier = GlanceModifier.fillMaxSize().background(ColorProvider(pal.bg)).cornerRadius(16.dp).padding(8.dp),
    ) {
        val b = snap.book
        when {
            !snap.configured -> Message("Abre Book Widget para configurarlo", pal, open)
            b == null -> Message("Sin libro activo. Abre la app y busca uno.", pal, open)
            else -> Ready(snap, b, pal, open, pending)
        }
    }
}

@Composable
private fun Message(text: String, pal: Palette, onClick: Action) {
    Box(
        modifier = GlanceModifier.fillMaxSize().clickable(onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = TextStyle(color = ColorProvider(pal.text), fontSize = 12.sp, textAlign = TextAlign.Center))
    }
}

@Composable
private fun Ready(snap: Snapshot, s: Book, pal: Palette, open: Action, pending: String?) {
    val size = LocalSize.current
    val small = size.width < 250.dp
    val tall = size.height >= 160.dp
    val big = !small && size.height >= 240.dp
    val caption = s.caption
    val sealed = s.progress.sealed && caption != null && !s.finished
    val header = "${s.title}  ·  ${s.progress.current}/${s.total}"

    @Composable
    fun SpriteBox(dim: androidx.compose.ui.unit.Dp) {
        Box(
            modifier = GlanceModifier.size(dim).background(ColorProvider(pal.card)).cornerRadius(4.dp).clickable(open),
            contentAlignment = Alignment.Center,
        ) { Sprite(snap, s, dim - 4.dp) }
    }

    val fontScale = LocalContext.current.resources.configuration.fontScale

    /** Leyenda con tamaño automático: la mayor letra con la que cabe entera en el hueco (ancho × alto en dp). */
    @Composable
    fun CaptionText(widthDp: Float, heightDp: Float) {
        val text = when {
            s.finished -> "■ FIN · ¡Terminaste el libro!"
            s.error != null && caption == null -> "! ${s.error}"
            caption == null -> "▶ invocando el próximo capítulo_"
            sealed -> scramble(caption)
            else -> caption
        }
        val (fontSize, lines) = autoSize(text, widthDp, heightDp - if (sealed) 16f else 0f, mono = sealed, fontScale = fontScale)
        Column(modifier = GlanceModifier.fillMaxWidth().then(if (sealed) GlanceModifier.clickable(actionRunCallback<BreakSealAction>()) else GlanceModifier)) {
            Text(
                text, maxLines = lines,
                style = TextStyle(
                    color = ColorProvider(if (sealed) pal.text.copy(alpha = 0.6f) else pal.text),
                    fontSize = fontSize.sp,
                    fontFamily = if (sealed) FontFamily.Monospace else null,
                ),
            )
            if (sealed) {
                Row(verticalAlignment = Alignment.Vertical.CenterVertically, modifier = GlanceModifier.padding(top = 3.dp)) {
                    Image(ImageProvider(PixelIcons.bitmap(PixelIcons.LOCK, pal.accent.toArgb(), 3)), null, GlanceModifier.size(10.dp))
                    Spacer(GlanceModifier.width(4.dp))
                    Text("toca para romper el sello", style = TextStyle(color = ColorProvider(pal.accent), fontSize = 9.sp, fontFamily = FontFamily.Monospace))
                }
            }
        }
    }

    /** Mientras se procesa un toque: indicador animado en lugar de los botones (no se puede pulsar dos veces). */
    @Composable
    fun Processing() {
        Row(modifier = GlanceModifier.fillMaxWidth().height(36.dp), verticalAlignment = Alignment.Vertical.CenterVertically) {
            CircularProgressIndicator(modifier = GlanceModifier.size(22.dp), color = ColorProvider(pal.accent))
            Spacer(GlanceModifier.width(10.dp))
            Text("PROCESANDO…", style = TextStyle(color = ColorProvider(pal.accent), fontSize = 13.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace))
        }
    }

    /** Niebla · Pista · Peligro con iconos pixel; el nivel elegido va resaltado con el acento del tema. */
    @Composable
    fun Levels(labels: Boolean) {
        Row(verticalAlignment = Alignment.Vertical.CenterVertically) {
            listOf("niebla" to PixelIcons.CLOUD, "pista" to PixelIcons.KEY, "peligro" to PixelIcons.FIRE).forEachIndexed { i, (lv, mask) ->
                if (i > 0) Spacer(GlanceModifier.width(6.dp))
                val on = s.progress.level == lv
                Row(
                    modifier = GlanceModifier
                        .background(ColorProvider(if (on) pal.accent else pal.card))
                        .cornerRadius(3.dp)
                        .padding(horizontal = if (labels && on) 10.dp else 12.dp, vertical = 8.dp)
                        .clickable(actionRunCallback<SetLevelAction>(actionParametersOf(SetLevelAction.LEVEL to lv))),
                    verticalAlignment = Alignment.Vertical.CenterVertically,
                ) {
                    Image(ImageProvider(PixelIcons.bitmap(mask, (if (on) pal.bg else pal.text).toArgb())), lv, GlanceModifier.size(18.dp))
                    if (labels && on) { // solo el elegido lleva nombre: así caben con «Terminé»
                        Spacer(GlanceModifier.width(5.dp))
                        Text(lv.uppercase(), style = TextStyle(color = ColorProvider(if (on) pal.bg else pal.text), fontSize = 11.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace))
                    }
                }
            }
        }
    }

    @Composable
    fun Done(compact: Boolean) {
        when {
            s.finished -> {}
            snap.busy && caption == null -> Text("▶ INVOCANDO_", style = TextStyle(color = ColorProvider(pal.accent), fontSize = 10.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace))
            else -> Row(
                modifier = GlanceModifier.background(ColorProvider(pal.accent)).cornerRadius(3.dp)
                    .padding(horizontal = if (compact) 12.dp else 16.dp, vertical = 9.dp)
                    .clickable(actionRunCallback<AdvanceChapterAction>()),
                verticalAlignment = Alignment.Vertical.CenterVertically,
            ) {
                Text("TERMINÉ", style = TextStyle(color = ColorProvider(pal.bg), fontSize = 13.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace))
                Spacer(GlanceModifier.width(6.dp))
                Image(ImageProvider(PixelIcons.bitmap(PixelIcons.PLAY, pal.bg.toArgb(), 3)), null, GlanceModifier.size(11.dp))
            }
        }
    }

    when {
        // 2×2: personaje y «Terminé»
        small -> Column(modifier = GlanceModifier.fillMaxSize(), horizontalAlignment = Alignment.Horizontal.CenterHorizontally, verticalAlignment = Alignment.Vertical.CenterVertically) {
            SpriteBox(if (size.height >= 150.dp) 84.dp else 60.dp)
            Spacer(GlanceModifier.height(6.dp))
            if (pending != null) CircularProgressIndicator(modifier = GlanceModifier.size(24.dp), color = ColorProvider(pal.accent)) else Done(compact = true)
        }
        // grande: título, personaje centrado, leyenda larga, niveles con nombre y «Terminé»
        big -> Column(modifier = GlanceModifier.fillMaxSize()) {
            Title(header, pal)
            Spacer(GlanceModifier.height(6.dp))
            Row(modifier = GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.Vertical.Top) {
                SpriteBox(96.dp)
                Spacer(GlanceModifier.width(10.dp))
                Column(modifier = GlanceModifier.defaultWeight()) {
                    CaptionText(size.width.value - 16f - 96f - 10f, size.height.value - 16f - 16f - 6f - 50f)
                }
            }
            Spacer(GlanceModifier.defaultWeight())
            if (pending != null) Processing() else {
                Row(modifier = GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.Vertical.CenterVertically) {
                    Levels(labels = true)
                    Spacer(GlanceModifier.defaultWeight())
                    Done(compact = false)
                }
            }
        }
        // 4×2 alto: personaje + título y leyenda arriba; botones a todo el ancho justo debajo
        tall -> {
            val sprite = (size.height - 16.dp - 42.dp - 8.dp).coerceIn(80.dp, 140.dp) // alto - márgenes - botones - separación
            Column(modifier = GlanceModifier.fillMaxSize()) {
                Row(modifier = GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.Vertical.Top) {
                    SpriteBox(sprite)
                    Spacer(GlanceModifier.width(10.dp))
                    Column(modifier = GlanceModifier.defaultWeight()) {
                        Title(header, pal)
                        Spacer(GlanceModifier.height(3.dp))
                        CaptionText(size.width.value - 16f - sprite.value - 10f, sprite.value - 19f)
                    }
                }
                Spacer(GlanceModifier.height(8.dp))
                if (pending != null) Processing() else {
                    Row(modifier = GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.Vertical.CenterVertically) {
                        Levels(labels = true)
                        Spacer(GlanceModifier.defaultWeight())
                        Done(compact = false)
                    }
                }
            }
        }
        // 4×2 bajo: personaje a la izquierda; título, leyenda corta y botones a la derecha
        else -> Row(modifier = GlanceModifier.fillMaxSize(), verticalAlignment = Alignment.Vertical.CenterVertically) {
            val sprite = (size.height - 16.dp).coerceIn(60.dp, 96.dp)
            SpriteBox(sprite)
            Spacer(GlanceModifier.width(10.dp))
            Column(modifier = GlanceModifier.fillMaxSize()) {
                Title(header, pal)
                Spacer(GlanceModifier.height(2.dp))
                Column(modifier = GlanceModifier.defaultWeight()) {
                    CaptionText(size.width.value - 16f - sprite.value - 10f, size.height.value - 16f - 16f - 2f - 36f)
                }
                if (pending != null) Processing() else {
                    Row(modifier = GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.Vertical.CenterVertically) {
                        Levels(labels = false)
                        Spacer(GlanceModifier.defaultWeight())
                        Done(compact = true)
                    }
                }
            }
        }
    }
}

@Composable
private fun Title(text: String, pal: Palette) {
    Text(
        text,
        maxLines = 1,
        style = TextStyle(color = ColorProvider(pal.accent), fontSize = 10.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace),
    )
}

/** Sprite animado: un ViewFlipper (RemoteViews) recorre base, base, variación… sin gastar CPU de la app. */
@Composable
private fun Sprite(snap: Snapshot, s: Book, dim: androidx.compose.ui.unit.Dp) {
    val ctx = LocalContext.current
    when {
        snap.frames.isEmpty() -> Text("…", style = TextStyle(color = ColorProvider(Color.White)))
        snap.animate && snap.frames.size > 1 ->
            AndroidRemoteViews(flipper(ctx, snap.frames), GlanceModifier.size(dim))
        else -> Image(
            provider = ImageProvider(snap.frames[0]),
            contentDescription = s.sprite?.name,
            modifier = GlanceModifier.size(dim),
            contentScale = ContentScale.Fit,
        )
    }
}

private fun flipper(ctx: Context, frames: List<Bitmap>): RemoteViews {
    val rv = RemoteViews(ctx.packageName, R.layout.sprite_flipper)
    val sequence = buildList {
        for (f in 1 until frames.size) {
            add(0); add(0); add(f)
        }
    }
    for (index in sequence) {
        val child = RemoteViews(ctx.packageName, R.layout.sprite_frame)
        child.setImageViewBitmap(R.id.frame, frames[index]) // el mismo Bitmap se deduplica al enviarlo
        rv.addView(R.id.flipper, child)
    }
    return rv
}

/**
 * «Autosize» para Glance (no lo trae): la mayor letra (10–18 sp) con la que el texto cabe entero en el hueco.
 * Simula el reparto en líneas palabra a palabra con el ancho medio de una letra y la escala de texto del sistema.
 */
private fun autoSize(text: String, widthDp: Float, heightDp: Float, mono: Boolean, fontScale: Float): Pair<Float, Int> {
    val charW = if (mono) 0.60f else 0.50f
    val lineH = 1.30f
    val words = text.split(Regex("\\s+")).filter { it.isNotEmpty() }
    fun linesFor(perLine: Int): Int {
        var lines = 1
        var used = 0
        for (w in words) {
            val need = if (used == 0) w.length else used + 1 + w.length
            if (need <= perLine) used = need else { lines++; used = w.length }
        }
        return lines
    }
    var f = 18f
    while (f >= 10f) {
        val px = f * fontScale
        val perLine = (widthDp / (px * charW)).toInt().coerceAtLeast(4)
        val lines = linesFor(perLine)
        if (lines * px * lineH <= heightDp) return f to lines
        f -= 0.5f
    }
    return 10f to (heightDp / (10f * fontScale * lineH)).toInt().coerceAtLeast(1)
}
