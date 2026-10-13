package com.bookwidget.app.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.sharp.Add
import androidx.compose.material.icons.sharp.Close
import androidx.compose.material.icons.sharp.ExpandLess
import androidx.compose.material.icons.sharp.ExpandMore
import androidx.compose.material.icons.sharp.Save
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bookwidget.app.data.Book
import com.bookwidget.app.data.Element
import com.bookwidget.app.data.Groq
import com.bookwidget.app.data.Scene
import com.bookwidget.app.data.SearchResult
import com.bookwidget.app.data.Sprites

// Lo que hacía el panel web, ahora dentro de la app.

private fun words(s: String) = s.split(Regex("\\s+")).count { it.isNotBlank() }

@Composable
fun ConfirmDeleteDialog(title: String, onConfirm: () -> Unit, onClose: () -> Unit) {
    RetroDialog("¿Eliminar libro?", onClose, confirm = {
        RetroButton("Eliminar", { onClose(); onConfirm() }, color = Retro.Danger)
    }) {
        Text("Se borrarán «$title», sus leyendas y sus dibujos de este móvil.", color = Retro.Text, fontSize = 14.sp)
    }
}

/** Historial de personajes del libro (los dibujos de los capítulos ya preparados). */
@Composable
fun SpriteHistory(b: Book, onPick: (Int) -> Unit) {
    val done = b.chapters.filter { it.sprite != null && it.n <= b.progress.current + 1 }
    if (done.isEmpty()) return
    val bitmaps: List<Pair<Int, Bitmap>> = remember(done.map { it.sprite }) { done.map { it.n to Sprites.bitmap(it.sprite!!, 0, 3) } }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        PixelText("HISTORIAL DE PERSONAJES", size = 8.sp, color = Retro.Accent2)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            bitmaps.forEach { (n, bmp) ->
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.clickable { onPick(n) }) {
                    Box(Modifier.size(64.dp).background(Retro.Panel2).border(2.dp, if (n == b.progress.current + 1) Retro.Accent else Retro.Line), contentAlignment = Alignment.Center) {
                        Image(bmp.asImageBitmap(), null, filterQuality = FilterQuality.None, modifier = Modifier.size(52.dp))
                    }
                    PixelText("$n", size = 7.sp, color = Retro.Muted)
                }
            }
        }
    }
}

/** Editor de leyendas y fichas visuales por capítulo, como en el panel. */
@Composable
fun ChaptersScreen(vm: AppViewModel) {
    val b = vm.current
    Column(Modifier.fillMaxSize().padding(Retro.ScreenPadding), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        TopBar("Capítulos", { b?.let { vm.navigate(Screen.Detail(it.id)) } ?: vm.navigate(Screen.Books) })
        if (b == null) return@Column
        Text("Edita las leyendas (máx. ${Groq.MAX_WORDS} palabras) y la ficha que usa el dibujo. Tus cambios se marcan como «Editado».", color = Retro.Muted, fontSize = 12.sp)
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(b.chapters, key = { it.n }) { c ->
                var open by remember { mutableStateOf(false) }
                RetroPanel(Modifier.fillMaxWidth(), shadow = false, border = if (c.n == b.progress.current + 1) Retro.Accent else Retro.Line) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(Modifier.fillMaxWidth().clickable { open = !open }, verticalAlignment = Alignment.CenterVertically) {
                            PixelText("${c.n}", size = 8.sp, color = Retro.Accent2)
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text(c.title, color = Retro.Text, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold)
                                Text(
                                    when {
                                        c.model == null -> "Pendiente"; c.model == "citas" -> "Cita del libro"
                                        c.model == "editado" -> "Editado"; else -> "Narrado por Groq"
                                    },
                                    color = Retro.Muted, fontSize = 11.sp,
                                )
                            }
                            Icon(if (open) Icons.Sharp.ExpandLess else Icons.Sharp.ExpandMore, null, tint = Retro.Muted)
                        }
                        if (open) ChapterEditor(vm, c.n, c.niebla.orEmpty(), c.pista.orEmpty(), c.peligro.orEmpty(), c.escena ?: Scene())
                    }
                }
            }
        }
    }
}

@Composable
private fun ChapterEditor(vm: AppViewModel, n: Int, niebla0: String, pista0: String, peligro0: String, scene0: Scene) {
    var niebla by remember { mutableStateOf(niebla0) }
    var pista by remember { mutableStateOf(pista0) }
    var peligro by remember { mutableStateOf(peligro0) }
    var nudo by remember { mutableStateOf(scene0.nudo) }
    var desenlace by remember { mutableStateOf(scene0.desenlace) }
    // como en el panel: una línea por elemento, "tipo: nombre — detalle"
    var elementos by remember { mutableStateOf(scene0.elementos.joinToString("\n") { "${it.tipo}: ${it.nombre}" + if (it.detalle.isNotBlank()) " — ${it.detalle}" else "" }) }
    val tooLong = listOf(niebla, pista, peligro).any { words(it) > Groq.MAX_WORDS }
    listOf(Triple("Niebla", niebla) { v: String -> niebla = v }, Triple("Pista", pista) { v: String -> pista = v }, Triple("Peligro (opcional)", peligro) { v: String -> peligro = v })
        .forEach { (label, value, set) -> RetroArea(value, set, "$label · ${words(value)}/${Groq.MAX_WORDS}", Modifier.fillMaxWidth()) }
    PixelText("FICHA DEL DIBUJO", size = 7.sp, color = Retro.Muted)
    RetroArea(nudo, { nudo = it }, "Nudo", Modifier.fillMaxWidth(), minLines = 1)
    RetroArea(desenlace, { desenlace = it }, "Desenlace (no se muestra)", Modifier.fillMaxWidth(), minLines = 1)
    RetroArea(elementos, { elementos = it }, "Elementos: tipo: nombre — detalle", Modifier.fillMaxWidth(), minLines = 3)
    RetroButton("Guardar capítulo", {
        val els = elementos.lines().mapNotNull { line ->
            val l = line.trim().takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            val tipo = l.substringBefore(':', "objeto").trim().lowercase().ifEmpty { "objeto" }
            val rest = if (':' in l) l.substringAfter(':') else l
            Element(tipo, rest.substringBefore('—').trim(), rest.substringAfter('—', "").trim())
        }
        vm.saveChapter(n, niebla, pista, peligro, Scene(nudo.trim(), desenlace.trim(), els))
    }, icon = Icons.Sharp.Save, enabled = niebla.isNotBlank() && pista.isNotBlank() && !tooLong)
    if (tooLong) Text("Alguna leyenda supera ${Groq.MAX_WORDS} palabras.", color = Retro.Danger, fontSize = 12.sp)
}

