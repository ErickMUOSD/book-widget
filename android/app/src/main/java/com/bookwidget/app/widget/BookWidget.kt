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
import androidx.glance.action.clickable
import androidx.glance.appwidget.AndroidRemoteViews
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.action.actionStartActivity
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
import com.bookwidget.app.data.Snapshot
import com.bookwidget.app.data.Theme
import com.bookwidget.app.data.WidgetState
import com.bookwidget.app.data.WidgetStore
import com.bookwidget.app.data.scramble

class BookWidget : GlanceAppWidget() {
    // 2×2: personaje + «Terminé ✓» · 4×2: + leyenda sellada y «Romper sello» · grande: personaje mayor
    override val sizeMode = SizeMode.Responsive(setOf(SMALL, WIDE, BIG))

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val snap = WidgetStore.snapshot(context)
        provideContent { WidgetContent(snap) }
    }

    companion object {
        val SMALL = DpSize(110.dp, 110.dp)
        val WIDE = DpSize(250.dp, 110.dp)
        val BIG = DpSize(250.dp, 250.dp)
    }
}

class BookWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = BookWidget()

    override fun onEnabled(context: Context) {
        super.onEnabled(context)
        Work.schedulePeriodic(context)
        Work.enqueue(context, Work.OP_REFRESH, unique = "refresh-now")
    }

    override fun onDisabled(context: Context) {
        super.onDisabled(context)
        Work.cancelPeriodic(context)
    }
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
private fun WidgetContent(snap: Snapshot) {
    val pal = Palette(snap.state?.theme ?: Theme())
    val open = actionStartActivity<MainActivity>()
    Box(
        modifier = GlanceModifier.fillMaxSize().background(ColorProvider(pal.border)).cornerRadius(16.dp).padding(2.dp),
    ) {
        Box(
            modifier = GlanceModifier.fillMaxSize().background(ColorProvider(pal.bg)).cornerRadius(14.dp).padding(8.dp),
        ) {
            val s = snap.state
            when {
                !snap.configured -> Message("Abre Book Widget y conecta tu servidor", pal, open)
                s == null -> Message(snap.error ?: "Sin libro activo. Abre la app y elige uno.", pal, open)
                s.status != "ready" -> Message("${s.title}\n⏳ Preparando el libro…", pal, open)
                else -> Ready(snap, s, pal, open)
            }
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
private fun Ready(snap: Snapshot, s: WidgetState, pal: Palette, open: Action) {
    val size = LocalSize.current
    val wide = size.width >= 250.dp && size.height < 200.dp
    val big = size.width >= 250.dp && size.height >= 200.dp
    val small = !wide && !big
    val spriteSize = when {
        big -> 130.dp
        wide -> 92.dp
        else -> 58.dp
    }

    @Composable
    fun SpriteBox() {
        Box(
            modifier = GlanceModifier.size(spriteSize).background(ColorProvider(pal.card)).clickable(open),
            contentAlignment = Alignment.Center,
        ) { Sprite(snap, s, spriteSize) }
    }

    @Composable
    fun CaptionText() {
        val cap = when {
            s.finished -> "FIN 📖 ¡Terminaste el libro!"
            s.caption == null -> "…"
            s.sealed -> "🔏 " + scramble(s.caption)
            else -> s.caption
        }
        Text(
            cap,
            maxLines = if (big) 6 else 3,
            style = TextStyle(
                color = ColorProvider(if (s.sealed && !s.finished) pal.text.copy(alpha = 0.7f) else pal.text),
                fontSize = 10.sp,
                fontFamily = if (s.sealed && !s.finished) FontFamily.Monospace else null,
            ),
        )
    }

    @Composable
    fun Buttons() {
        when {
            snap.busy -> Text(
                "invocando…",
                style = TextStyle(color = ColorProvider(pal.accent), fontSize = 11.sp, fontWeight = FontWeight.Bold),
            )
            else -> Row(verticalAlignment = Alignment.Vertical.CenterVertically) {
                if (!small && s.sealed && !s.finished && s.caption != null) {
                    PixelButton("Romper sello", pal, actionRunCallback<BreakSealAction>())
                    Spacer(GlanceModifier.width(6.dp))
                }
                if (!s.finished) PixelButton("Terminé ✓", pal, actionRunCallback<AdvanceChapterAction>())
            }
        }
    }

    val header = "${s.title}  ·  ${s.currentChapter}/${s.totalChapters}"
    when {
        wide -> Row(modifier = GlanceModifier.fillMaxSize(), verticalAlignment = Alignment.Vertical.CenterVertically) {
            SpriteBox()
            Spacer(GlanceModifier.width(8.dp))
            Column(modifier = GlanceModifier.fillMaxSize()) {
                Title(header, pal)
                Spacer(GlanceModifier.height(3.dp))
                CaptionText()
                Spacer(GlanceModifier.height(4.dp))
                Buttons()
            }
        }
        else -> Column(
            modifier = GlanceModifier.fillMaxSize(),
            horizontalAlignment = Alignment.Horizontal.CenterHorizontally,
        ) {
            if (big) {
                Title(header, pal)
                Spacer(GlanceModifier.height(4.dp))
            }
            SpriteBox()
            Spacer(GlanceModifier.height(4.dp))
            if (big) {
                CaptionText()
                Spacer(GlanceModifier.height(6.dp))
            }
            Buttons()
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

@Composable
private fun PixelButton(label: String, pal: Palette, onClick: Action) {
    Box(
        modifier = GlanceModifier
            .background(ColorProvider(pal.accent))
            .cornerRadius(6.dp)
            .padding(horizontal = 8.dp, vertical = 5.dp)
            .clickable(onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, style = TextStyle(color = ColorProvider(pal.bg), fontSize = 10.sp, fontWeight = FontWeight.Bold))
    }
}

/** Sprite animado: un ViewFlipper (RemoteViews) recorre base, base, variación… sin gastar CPU de la app. */
@Composable
private fun Sprite(snap: Snapshot, s: WidgetState, dim: androidx.compose.ui.unit.Dp) {
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
