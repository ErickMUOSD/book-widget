package com.bookwidget.app.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.unit.Dp
import kotlinx.coroutines.delay

/** Sprite pixel art con animación idle: base, base, variación, base, base, variación… */
@Composable
fun SpriteView(frames: List<ImageBitmap>, animate: Boolean, size: Dp, name: String? = null) {
    var shown by remember(frames) { mutableIntStateOf(0) }
    LaunchedEffect(frames, animate) {
        shown = 0
        if (!animate || frames.size < 2) return@LaunchedEffect
        val sequence = buildList {
            for (f in 1 until frames.size) {
                add(0); add(0); add(f)
            }
        }
        var k = 0
        while (true) {
            delay(450)
            k = (k + 1) % sequence.size
            shown = sequence[k]
        }
    }
    if (frames.isEmpty()) {
        Box(Modifier.size(size))
    } else {
        Image(
            bitmap = frames[shown.coerceIn(0, frames.lastIndex)],
            contentDescription = name,
            filterQuality = FilterQuality.None, // píxeles nítidos, sin suavizado
            modifier = Modifier.size(size),
        )
    }
}
