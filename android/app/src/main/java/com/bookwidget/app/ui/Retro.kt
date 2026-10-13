package com.bookwidget.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.sharp.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.bookwidget.app.R
import com.bookwidget.app.data.Sprites
import kotlinx.coroutines.delay

/** Estética: app moderna (Material, tipografía limpia) con detalles de consola retro. */
object Retro {
    val Pixel = FontFamily(Font(R.font.press_start_2p))
    val Bg = Color(0xFF0D0E13)
    val Panel = Color(0xFF161822)
    val Panel2 = Color(0xFF1D2030)
    val Line = Color(0xFF2E3245)
    val Text = Color(0xFFE8EAF2)
    val Muted = Color(0xFF8B91A6)
    val Accent = Color(0xFF8B7BFF)
    val Accent2 = Color(0xFF5EEAD4)
    val Danger = Color(0xFFFF5D73)
    val Shadow = Color(0xFF050608)
    /** Márgenes de cada pantalla: poco arriba (la barra de estado ya separa), más a los lados y abajo. */
    val ScreenPadding = androidx.compose.foundation.layout.PaddingValues(start = 16.dp, end = 16.dp, top = 2.dp, bottom = 16.dp)
}

fun hex(hex: String, fallback: Color): Color =
    runCatching { Color(android.graphics.Color.parseColor(hex)) }.getOrDefault(fallback)

/** Sombra dura desplazada (sin difuminar), como los paneles de una consola. */
fun Modifier.hardShadow(offset: Dp = 4.dp, color: Color = Retro.Shadow) = drawBehind {
    val o = offset.toPx()
    drawRect(color, topLeft = Offset(o, o), size = size)
}

@Composable
fun PixelText(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = Retro.Text,
    size: TextUnit = 10.sp,
    align: TextAlign? = null,
    maxLines: Int = Int.MAX_VALUE,
) {
    Text(
        text, modifier = modifier, color = color, maxLines = maxLines, textAlign = align,
        style = TextStyle(fontFamily = Retro.Pixel, fontSize = size, lineHeight = size * 1.6f),
    )
}

@Composable
fun RetroPanel(
    modifier: Modifier = Modifier,
    border: Color = Retro.Line,
    bg: Color = Retro.Panel,
    shadow: Boolean = true,
    content: @Composable () -> Unit,
) {
    Box(
        modifier
            .then(if (shadow) Modifier.hardShadow() else Modifier)
            .background(bg)
            .border(2.dp, border),
    ) { content() }
}

/** Botón de consola: se «hunde» sobre su sombra al pulsarlo. */
@Composable
fun RetroButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    color: Color = Retro.Accent,
    content: Color = Retro.Bg,
    outline: Boolean = false,
) {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val depth = if (pressed && enabled) 0.dp else 3.dp
    val fg = when {
        !enabled -> Retro.Muted
        outline -> color
        else -> content
    }
    Box(
        modifier
            .offset(3.dp - depth, 3.dp - depth)
            .then(if (enabled) Modifier.hardShadow(depth) else Modifier)
            .background(if (outline || !enabled) Retro.Panel2 else color)
            .border(2.dp, if (enabled) (if (outline) color else Retro.Shadow) else Retro.Line)
            .clickable(interactionSource = source, indication = null, enabled = enabled, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 11.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
            if (icon != null) {
                Icon(icon, null, tint = fg, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(8.dp))
            }
            PixelText(text.uppercase(), color = fg, size = 9.sp)
        }
    }
}

@Composable
fun RetroIconButton(icon: ImageVector, description: String, onClick: () -> Unit, enabled: Boolean = true, tint: Color = Retro.Text) {
    Box(
        Modifier.size(44.dp).clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, description, tint = if (enabled) tint else Retro.Muted, modifier = Modifier.size(22.dp)) }
}

@Composable
fun RetroChip(text: String, icon: ImageVector, selected: Boolean, onClick: () -> Unit, color: Color = Retro.Accent) {
    Row(
        Modifier
            .background(if (selected) color.copy(alpha = 0.18f) else Color.Transparent)
            .border(2.dp, if (selected) color else Retro.Line)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = if (selected) color else Retro.Muted, modifier = Modifier.size(15.dp))
        Spacer(Modifier.width(6.dp))
        PixelText(text.uppercase(), color = if (selected) color else Retro.Muted, size = 7.sp)
    }
}

@Composable
fun Tag(text: String, color: Color = Retro.Accent2, icon: ImageVector? = null) {
    Row(
        Modifier.border(1.dp, color).padding(horizontal = 6.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, null, tint = color, modifier = Modifier.size(11.dp))
            Spacer(Modifier.width(4.dp))
        }
        PixelText(text.uppercase(), color = color, size = 6.sp)
    }
}

@Composable
fun TopBar(title: String, onBack: (() -> Unit)?, actions: @Composable () -> Unit = {}) {
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onBack != null) RetroIconButton(Icons.AutoMirrored.Sharp.ArrowBack, "Volver", onBack)
        else Box(Modifier.size(8.dp).background(Retro.Accent))
        Spacer(Modifier.width(if (onBack != null) 4.dp else 10.dp))
        PixelText(title.uppercase(), size = 12.sp, maxLines = 1, modifier = Modifier.weight(1f))
        actions()
    }
}

@Composable
fun RetroField(
    value: String,
    onChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    secret: Boolean = false,
    keyboard: androidx.compose.foundation.text.KeyboardOptions = androidx.compose.foundation.text.KeyboardOptions.Default,
    actions: androidx.compose.foundation.text.KeyboardActions = androidx.compose.foundation.text.KeyboardActions.Default,
) {
    OutlinedTextField(
        value, onChange, singleLine = true, modifier = modifier,
        label = { PixelText(label.uppercase(), size = 7.sp, color = Retro.Muted) },
        shape = RectangleShape,
        visualTransformation = if (secret) androidx.compose.ui.text.input.PasswordVisualTransformation() else VisualTransformation.None,
        keyboardOptions = keyboard, keyboardActions = actions,
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = Retro.Accent, unfocusedBorderColor = Retro.Line,
            focusedContainerColor = Retro.Panel, unfocusedContainerColor = Retro.Panel,
            cursorColor = Retro.Accent2, focusedTextColor = Retro.Text, unfocusedTextColor = Retro.Text,
        ),
    )
}

// ---------------------------------------------------------------- carga
private val LOADER_FRAMES by lazy { Sprites.bitmaps(Sprites.fallback()).map { it.asImageBitmap() } }

/** Librito pixel animado + barra de bloques + cursor parpadeante. */
@Composable
fun PixelLoader(label: String, modifier: Modifier = Modifier, color: Color = Retro.Accent, spriteSize: Dp = 72.dp) {
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(140)
            tick++
        }
    }
    val frame = listOf(0, 0, 0, 1, 0, 0, 2, 0)[(tick / 3) % 8]
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Image(
            LOADER_FRAMES[frame], null, filterQuality = FilterQuality.None,
            modifier = Modifier.size(spriteSize).offset(y = if ((tick / 3) % 2 == 0) 0.dp else (-2).dp),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            val head = tick % 14
            repeat(10) { i ->
                val lit = i <= head && i > head - 4
                Box(Modifier.size(width = 10.dp, height = 8.dp).background(if (lit) color else Retro.Line))
            }
        }
        PixelText(label + if (tick / 4 % 2 == 0) "_" else " ", color = color, size = 8.sp, align = TextAlign.Center)
    }
}

/** Bloquea toda la pantalla (toques y «atrás») mientras dura una operación que no debe interrumpirse. */
@Composable
fun BlockingOverlay(title: String, step: String, steps: List<String>) {
    BackHandler(enabled = true) {}
    Dialog(onDismissRequest = {}, properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false)) {
        Box(
            Modifier.fillMaxWidth().pointerInput(Unit) {
                awaitPointerEventScope { while (true) awaitPointerEvent().changes.forEach { it.consume() } }
            },
            contentAlignment = Alignment.Center,
        ) {
            RetroPanel(Modifier.widthIn(max = 340.dp).fillMaxWidth(), border = Retro.Accent) {
                Column(Modifier.padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    PixelText(title.uppercase(), size = 9.sp, align = TextAlign.Center, color = Retro.Muted)
                    PixelLoader(step)
                    if (steps.size > 1) {
                        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            val current = steps.indexOf(step)
                            steps.forEachIndexed { i, s ->
                                val (mark, c) = when {
                                    i < current -> "■" to Retro.Accent2
                                    i == current -> "▶" to Retro.Accent
                                    else -> "□" to Retro.Line
                                }
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(mark, color = c, fontSize = 11.sp)
                                    Spacer(Modifier.width(8.dp))
                                    Text(s, color = if (i <= current) Retro.Text else Retro.Muted, fontSize = 13.sp)
                                }
                            }
                        }
                    }
                    Spacer(Modifier.height(2.dp))
                }
            }
        }
    }
}

/** Diálogo con la estética de la app (panel con borde y botones de consola). */
@Composable
fun RetroDialog(title: String, onDismiss: () -> Unit, confirm: @Composable () -> Unit, content: @Composable () -> Unit) {
    Dialog(onDismissRequest = onDismiss) {
        RetroPanel(Modifier.fillMaxWidth(), border = Retro.Accent) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                PixelText(title.uppercase(), size = 10.sp, color = Retro.Accent2)
                content()
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End), verticalAlignment = Alignment.CenterVertically) {
                    Text("Cancelar", color = Retro.Muted, modifier = Modifier.clickable(onClick = onDismiss).padding(10.dp))
                    confirm()
                }
            }
        }
    }
}

/** Campo multilínea con la estética retro (leyendas, fichas, sinopsis). */
@Composable
fun RetroArea(value: String, onChange: (String) -> Unit, label: String, modifier: Modifier = Modifier, minLines: Int = 2) {
    OutlinedTextField(
        value, onChange, modifier = modifier, minLines = minLines,
        label = { PixelText(label.uppercase(), size = 7.sp, color = Retro.Muted) },
        shape = RectangleShape,
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = Retro.Accent, unfocusedBorderColor = Retro.Line,
            focusedContainerColor = Retro.Panel, unfocusedContainerColor = Retro.Panel,
            cursorColor = Retro.Accent2, focusedTextColor = Retro.Text, unfocusedTextColor = Retro.Text,
        ),
    )
}
