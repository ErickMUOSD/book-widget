package com.bookwidget.app.ui

import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.sharp.OpenInNew
import androidx.compose.material.icons.sharp.Add
import androidx.compose.material.icons.sharp.Animation
import androidx.compose.material.icons.sharp.AutoAwesome
import androidx.compose.material.icons.sharp.Casino
import androidx.compose.material.icons.sharp.Check
import androidx.compose.material.icons.sharp.CheckCircle
import androidx.compose.material.icons.sharp.Cloud
import androidx.compose.material.icons.sharp.Delete
import androidx.compose.material.icons.sharp.EditNote
import androidx.compose.material.icons.sharp.FileOpen
import androidx.compose.material.icons.sharp.FormatQuote
import androidx.compose.material.icons.sharp.Language
import androidx.compose.material.icons.sharp.LocalFireDepartment
import androidx.compose.material.icons.sharp.Lock
import androidx.compose.material.icons.sharp.LockOpen
import androidx.compose.material.icons.sharp.Refresh
import androidx.compose.material.icons.sharp.Save
import androidx.compose.material.icons.sharp.Search
import androidx.compose.material.icons.sharp.Settings
import androidx.compose.material.icons.sharp.Translate
import androidx.compose.material.icons.sharp.VpnKey
import androidx.compose.material.icons.sharp.Warning
import androidx.compose.material.icons.sharp.Widgets
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bookwidget.app.data.Book
import com.bookwidget.app.data.Groq
import com.bookwidget.app.data.Level
import com.bookwidget.app.data.SearchResult
import com.bookwidget.app.data.Thumbnails
import com.bookwidget.app.data.scramble

private fun levelIcon(l: Level): ImageVector = when (l) {
    Level.NIEBLA -> Icons.Sharp.Cloud
    Level.PISTA -> Icons.Sharp.VpnKey
    Level.PELIGRO -> Icons.Sharp.LocalFireDepartment
}

@Composable
fun App(vm: AppViewModel) {
    BackHandler(enabled = vm.screen != Screen.Books) { vm.navigate(Screen.Books) }
    Box(Modifier.fillMaxSize().background(Retro.Bg).safeDrawingPadding()) {
        when {
            !vm.loaded -> PixelLoader("Cargando", Modifier.align(Alignment.Center))
            vm.groqKey.isBlank() -> SetupScreen(vm)
            else -> when (vm.screen) {
                Screen.Books -> BooksScreen(vm)
                Screen.Search -> SearchScreen(vm)
                is Screen.Detail -> BookScreen(vm)
                is Screen.Chapters -> ChaptersScreen(vm)
                Screen.Settings -> SettingsScreen(vm)
            }
        }
        vm.blocking?.let { BlockingOverlay(it.title, it.step, it.steps) }
    }
}

@Composable
private fun Thumb(bitmap: Bitmap, width: Dp, height: Dp, border: Color) {
    Box(Modifier.size(width, height).background(Retro.Panel2).border(2.dp, border), contentAlignment = Alignment.Center) {
        Image(bitmap.asImageBitmap(), null, filterQuality = FilterQuality.None, modifier = Modifier.size(width - 8.dp, height - 8.dp))
    }
}

@Composable
private fun ErrorLine(text: String) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 4.dp)) {
        Icon(Icons.Sharp.Warning, null, tint = Retro.Danger, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(8.dp))
        Text(text, color = Retro.Danger, fontSize = 13.sp)
    }
}

/** Barra de progreso de lectura en bloques. */
@Composable
private fun BlockBar(done: Int, total: Int, color: Color, blocks: Int = 12) {
    val lit = if (total == 0) 0 else (done * blocks + total - 1) / total
    Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        repeat(blocks) { i -> Box(Modifier.size(8.dp, 6.dp).background(if (i < lit) color else Retro.Line)) }
    }
}

// ---------------------------------------------------------------- configuración inicial: Groq
/** Clave de Groq: crear (abre la consola de Groq), pegar y probar con un susurro de ejemplo. */
@Composable
private fun GroqKeyForm(vm: AppViewModel) {
    val ctx = LocalContext.current
    var key by remember { mutableStateOf("") }
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        RetroPanel(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                PixelText("1  CREA TU CLAVE", size = 8.sp, color = Retro.Accent2)
                Text("Entra en Groq con tu cuenta (Google sirve), pulsa «Create API Key» y cópiala con su botón de copiar. Es gratis.", color = Retro.Muted, fontSize = 13.sp)
                PixelText("2  PÉGALA Y PRUEBA", size = 8.sp, color = Retro.Accent2)
                Text("Se guarda solo en este móvil.", color = Retro.Muted, fontSize = 13.sp)
            }
        }
        RetroButton("Abrir Groq", { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(Groq.KEY_URL))) },
            icon = Icons.AutoMirrored.Sharp.OpenInNew, outline = true, color = Retro.Accent2)
        RetroField(key, { key = it }, "Clave de Groq (gsk_…)", Modifier.fillMaxWidth(), secret = true)
        if (vm.testing) PixelLoader("Probando la clave", Modifier.fillMaxWidth())
        else RetroButton("Guardar y probar", { vm.saveGroqKey(key) }, icon = Icons.Sharp.Check, enabled = key.isNotBlank())
        vm.groqTest?.let { Text(it, color = if (it.startsWith("✓")) Retro.Accent2 else Retro.Danger, fontSize = 13.sp) }
    }
}

@Composable
fun SetupScreen(vm: AppViewModel) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Spacer(Modifier.height(24.dp))
        PixelText("BOOK\nWIDGET", size = 22.sp, color = Retro.Accent)
        PixelText("PRESS START", size = 8.sp, color = Retro.Accent2)
        Text(
            "Book Widget lee el capítulo que vas a empezar, elige sus frases clave sin destripar nada y Groq " +
                "las convierte en un susurro misterioso para tu pantalla de inicio.",
            color = Retro.Text, fontSize = 15.sp,
        )
        GroqKeyForm(vm)
    }
}

// ---------------------------------------------------------------- biblioteca
@Composable
fun BooksScreen(vm: AppViewModel) {
    Column(Modifier.fillMaxSize().padding(Retro.ScreenPadding)) {
        TopBar("Mis libros", null) { RetroIconButton(Icons.Sharp.Settings, "Ajustes", { vm.navigate(Screen.Settings) }) }
        (vm.notice ?: vm.groqTest?.takeIf { it.startsWith("✓") }?.let { "Groq listo. Ejemplo: ${it.removePrefix("✓ ")}" })?.let {
            Text(it, color = Retro.Accent2, fontSize = 13.sp, modifier = Modifier.padding(vertical = 6.dp))
        }
        if (vm.books.isEmpty()) {
            RetroPanel(Modifier.fillMaxWidth().padding(vertical = 16.dp)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    PixelText("INSERT BOOK", color = Retro.Accent2, size = 10.sp)
                    Text("Busca un clásico en Gutenberg o Wikisource, o importa tu EPUB.", color = Retro.Muted, fontSize = 14.sp)
                }
            }
        }
        LazyColumn(Modifier.weight(1f).padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            items(vm.books, key = { it.id }) { b -> BookRow(vm, b) }
        }
        RetroButton("Añadir libro", { vm.navigate(Screen.Search) }, Modifier.fillMaxWidth().padding(top = 8.dp), icon = Icons.Sharp.Add)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BookRow(vm: AppViewModel, b: Book) {
    val border = hex(b.theme.border, Retro.Line)
    val accent = hex(b.theme.accent, Retro.Accent)
    val active = b.id == vm.activeId
    RetroPanel(Modifier.fillMaxWidth().clickable { vm.navigate(Screen.Detail(b.id)) }, border = if (active) accent else Retro.Line) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Thumb(vm.thumbnail(b), 64.dp, 80.dp, border)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(b.title, color = Retro.Text, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(b.author.ifBlank { "—" }, color = Retro.Muted, fontSize = 13.sp, maxLines = 1)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (active) Tag("En el widget", accent, Icons.Sharp.Widgets)
                    Tag(b.sources.label, Retro.Muted)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    BlockBar(b.progress.current, b.total, accent)
                    Spacer(Modifier.width(8.dp))
                    PixelText("${b.progress.current}/${b.total}", size = 7.sp, color = Retro.Muted)
                }
                val work = vm.status[b.id]
                when {
                    work != null -> PixelText("▶ ${work.label}", size = 7.sp, color = accent)
                    b.error != null -> ErrorLine(b.error)
                }
            }
        }
    }
}

// ---------------------------------------------------------------- búsqueda
@Composable
fun SearchScreen(vm: AppViewModel) {
    var query by remember { mutableStateOf("") }
    var spanish by remember { mutableStateOf(true) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(vm::importEpub) }
    val go = { vm.search(query, spanish) }
    Column(Modifier.fillMaxSize().padding(Retro.ScreenPadding), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        TopBar("Añadir libro", { vm.navigate(Screen.Books) })
        RetroField(
            query, { query = it }, "Título o autor", Modifier.fillMaxWidth(),
            keyboard = KeyboardOptions(imeAction = ImeAction.Search), actions = KeyboardActions(onSearch = { go() }),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            RetroChip("Español", Icons.Sharp.Translate, spanish, { spanish = true })
            RetroChip("Todos", Icons.Sharp.Language, !spanish, { spanish = false })
            Spacer(Modifier.weight(1f))
            RetroButton("Buscar", go, icon = Icons.Sharp.Search, enabled = !vm.searching && query.isNotBlank())
        }
        RetroButton(
            "Importar mi EPUB", { picker.launch(arrayOf("application/epub+zip", "application/octet-stream")) },
            Modifier.fillMaxWidth(), icon = Icons.Sharp.FileOpen, outline = true, color = Retro.Accent2,
        )
        Text("Bibliotecas abiertas: Project Gutenberg y Wikisource (dominio público). También tus EPUB sin DRM.", color = Retro.Muted, fontSize = 12.sp)
        vm.error?.let { ErrorLine(it) }
        if (vm.searching) {
            Box(Modifier.fillMaxWidth().padding(top = 40.dp), contentAlignment = Alignment.Center) { PixelLoader("Buscando") }
            return@Column
        }
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (vm.searched && vm.results.isEmpty()) item { Text("Sin resultados. Prueba otro título o «Todos».", color = Retro.Muted) }
            items(vm.results, key = { it.id }) { r -> ResultRow(vm, r) }
            items(vm.searchNotes) { Text(it, color = Retro.Muted, fontSize = 12.sp) }
        }
    }
}

@Composable
private fun ResultRow(vm: AppViewModel, r: SearchResult) {
    val have = vm.books.any { it.id == r.id }
    RetroPanel(Modifier.fillMaxWidth(), shadow = false) {
        Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Thumb(remember(r.id) { Thumbnails.cover(r.id, r.genre, 2) }, 40.dp, 52.dp, Retro.Line)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(r.title, color = Retro.Text, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (r.author.isNotBlank()) Text(r.author, color = Retro.Muted, fontSize = 12.sp, maxLines = 1)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Tag(r.source.label, Retro.Accent2)
                    Text(r.detail, color = Retro.Muted, fontSize = 11.sp, maxLines = 1)
                }
            }
            if (have) Icon(Icons.Sharp.CheckCircle, "Ya está en tu biblioteca", tint = Retro.Accent2, modifier = Modifier.padding(10.dp).size(22.dp))
            else RetroIconButton(Icons.Sharp.Add, "Añadir", { vm.add(r) }, tint = Retro.Accent)
        }
    }
}

// ---------------------------------------------------------------- libro
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun BookScreen(vm: AppViewModel) {
    val b = vm.current
    var confirmDelete by remember { mutableStateOf(false) }
    if (b != null && confirmDelete) ConfirmDeleteDialog(b.title, { vm.delete(b.id) }) { confirmDelete = false }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(Retro.ScreenPadding), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        TopBar(b?.title ?: "", { vm.navigate(Screen.Books) }) {
            if (b != null) {
                RetroIconButton(Icons.Sharp.Refresh, "Rehacer el siguiente capítulo", { vm.redoNext() }, enabled = !vm.waitingNext(b) && !b.finished, tint = Retro.Muted)
                RetroIconButton(Icons.Sharp.Delete, "Borrar libro", { confirmDelete = true }, tint = Retro.Muted)
            }
        }
        if (b == null) {
            PixelLoader("Cargando")
            return@Column
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Tag(b.sources.label, Retro.Muted)
            Tag(b.theme.name, hex(b.theme.accent, Retro.Accent))
            when (b.next?.model) {
                "groq" -> Tag("Narrado por Groq", Retro.Accent, Icons.Sharp.AutoAwesome)
                "citas" -> Tag("Cita del libro", Retro.Accent2, Icons.Sharp.FormatQuote)
                "editado" -> Tag("Editado", Retro.Accent2, Icons.Sharp.EditNote)
            }
        }
        b.error?.let {
            RetroPanel(Modifier.fillMaxWidth(), border = Retro.Danger, shadow = false) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    ErrorLine(it)
                    RetroButton("Reintentar", { vm.retry(b.id) }, icon = Icons.Sharp.Refresh, outline = true, color = Retro.Danger)
                }
            }
        }
        PreviewCard(vm, b)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            RetroButton("Nuevo dibujo", { vm.newSprite() }, Modifier.weight(1f), icon = Icons.Sharp.Casino, outline = true, color = Retro.Accent,
                enabled = !vm.waitingNext(b) && !b.finished)
            RetroButton("Capítulos", { vm.navigate(Screen.Chapters(b.id)) }, Modifier.weight(1f), icon = Icons.Sharp.EditNote, outline = true, color = Retro.Accent)
        }
        SpriteHistory(b) { }
        ChapterCard(vm, b)
        if (b.id != vm.activeId) {
            RetroButton("Usar en el widget", { vm.activate(b.id) }, Modifier.fillMaxWidth(), icon = Icons.Sharp.Widgets, outline = true, color = Retro.Accent2)
        }
    }
}

/** Misma tarjeta que el widget: tema por género, sprite animado, leyenda sellada y botones. */
@Composable
private fun PreviewCard(vm: AppViewModel, b: Book) {
    val t = b.theme
    val bg = hex(t.bg, Retro.Panel)
    val card = hex(t.card, Retro.Panel2)
    val border = hex(t.border, Retro.Line)
    val text = hex(t.text, Retro.Text)
    val accent = hex(t.accent, Retro.Accent)
    val caption = b.caption
    val waiting = vm.waitingNext(b)
    val work = vm.generatingNext(b)
    RetroPanel(Modifier.fillMaxWidth(), border = border, bg = bg) {
        Column(Modifier.fillMaxWidth().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            PixelText(b.title.uppercase(), color = text, size = 10.sp, align = TextAlign.Center, maxLines = 2)
            PixelText(
                "CAP ${b.progress.current}/${b.total}" + (b.next?.title?.let { "  ▶ $it" } ?: ""),
                color = accent, size = 7.sp, align = TextAlign.Center, maxLines = 2,
            )
            Box(Modifier.size(196.dp).background(card).border(3.dp, border), contentAlignment = Alignment.Center) {
                when {
                    b.next?.sprite != null || (b.finished && vm.frames.isNotEmpty()) -> SpriteView(vm.frames, vm.animate, 168.dp, b.sprite?.name)
                    waiting -> PixelLoader(work?.label ?: "En cola", color = accent, spriteSize = 64.dp)
                    vm.frames.isNotEmpty() -> SpriteView(vm.frames, vm.animate, 168.dp, b.sprite?.name)
                }
            }
            if (b.next?.sprite != null) PixelText(b.sprite?.name.orEmpty(), color = accent, size = 8.sp)

            when {
                b.finished -> PixelText("FIN · ¡TERMINASTE EL LIBRO!", color = text, size = 9.sp, align = TextAlign.Center)
                caption == null -> Text(
                    if (waiting) "El narrador está leyendo el capítulo…" else "Sin leyenda todavía.",
                    color = text.copy(alpha = 0.7f), fontSize = 14.sp, textAlign = TextAlign.Center,
                )
                b.progress.sealed -> {
                    Text(scramble(caption), color = text.copy(alpha = 0.55f), fontFamily = Retro.Pixel, fontSize = 8.sp, lineHeight = 14.sp, textAlign = TextAlign.Center)
                    RetroButton("Romper sello", { vm.setSealed(false) }, icon = Icons.Sharp.LockOpen, color = accent, content = bg)
                }
                else -> {
                    Text(caption, color = text, textAlign = TextAlign.Center, fontSize = 16.sp, lineHeight = 23.sp)
                    Row(Modifier.clickable { vm.setSealed(true) }.padding(6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Sharp.Lock, null, tint = text.copy(alpha = 0.6f), modifier = Modifier.size(14.dp))
                        Spacer(Modifier.width(6.dp))
                        PixelText("SELLAR", color = text.copy(alpha = 0.6f), size = 7.sp)
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Level.entries.forEach { l -> RetroChip(l.label, levelIcon(l), b.progress.level == l.id, { vm.setLevel(l) }, accent) }
            }
            RetroButton(
                if (waiting) "Preparando…" else "Terminé", { vm.advance() },
                icon = Icons.Sharp.Check, enabled = !b.finished && !waiting, color = accent, content = bg,
            )
        }
    }
}

@Composable
private fun ChapterCard(vm: AppViewModel, b: Book) {
    val waiting = vm.waitingNext(b)
    var chapter by remember(b.id, b.progress.current) { mutableIntStateOf(b.progress.current) }
    var open by remember { mutableStateOf(false) }
    fun label(n: Int) = if (n == 0) "0 · Aún no empiezo" else "$n · ${b.chapters[n - 1].title}"
    RetroPanel(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            PixelText("¿QUÉ CAPÍTULO TERMINASTE?", size = 8.sp, color = Retro.Accent2)
            Box {
                Row(
                    Modifier.fillMaxWidth().border(2.dp, Retro.Line).clickable(enabled = !waiting) { open = true }.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(label(chapter), color = if (waiting) Retro.Muted else Retro.Text, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    PixelText("▼", size = 8.sp, color = Retro.Muted)
                }
                DropdownMenu(open, { open = false }, Modifier.background(Retro.Panel2)) {
                    (0..b.total).forEach { n ->
                        DropdownMenuItem(
                            text = { Text(label(n), color = if (n == chapter) Retro.Accent else Retro.Text, maxLines = 2) },
                            onClick = { chapter = n; open = false },
                        )
                    }
                }
            }
            RetroButton(
                "Guardar", { vm.setChapter(chapter) }, icon = Icons.Sharp.Save,
                enabled = !waiting && chapter != b.progress.current, outline = true, color = Retro.Accent2,
            )
            if (waiting) Text("Espera a que termine de preparar el capítulo actual.", color = Retro.Muted, fontSize = 12.sp)
        }
    }
}

// ---------------------------------------------------------------- ajustes
@Composable
fun SettingsScreen(vm: AppViewModel) {
    var changeKey by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(Retro.ScreenPadding), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        TopBar("Ajustes", { vm.navigate(Screen.Books) })
        SettingRow(Icons.Sharp.AutoAwesome, "Groq", "Convierte en susurros las citas que eligen los algoritmos. Si falla o no hay red, se ven las citas.") {}
        if (changeKey) GroqKeyForm(vm)
        else RetroButton("Cambiar clave de Groq", { changeKey = true }, icon = Icons.Sharp.VpnKey, outline = true, color = Retro.Accent2)
        SettingRow(Icons.Sharp.Animation, "Animación del personaje", "Desactívala para ahorrar batería.") {
            Switch(
                checked = vm.animate, onCheckedChange = { vm.changeAnimate(it) },
                colors = SwitchDefaults.colors(checkedTrackColor = Retro.Accent, checkedThumbColor = Retro.Bg),
            )
        }
        SettingRow(Icons.Sharp.Widgets, "Añadir el widget", "Mantén pulsada la pantalla de inicio → Widgets → Book Widget. 2×2: personaje y «Terminé»; 4×2: más la leyenda sellada.") {}
        Text(
            "Libros: Project Gutenberg (Gutendex) y Wikisource. Las citas se eligen en el móvil con algoritmos clásicos " +
                "(TF-IDF, TextRank) y Groq las narra. Tus libros y tu progreso se guardan solo aquí.\n\nIconos: game-icons.net " +
                "(Lorc, Delapouite y otros autores), licencia CC BY 3.0, rasterizados y coloreados. Fuente: Press Start 2P (SIL OFL 1.1).",
            color = Retro.Muted, fontSize = 12.sp,
        )
    }
}

@Composable
private fun SettingRow(icon: ImageVector, title: String, hint: String, trailing: @Composable () -> Unit) {
    RetroPanel(Modifier.fillMaxWidth(), shadow = false) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = Retro.Accent2, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, color = Retro.Text, fontSize = 15.sp)
                Text(hint, color = Retro.Muted, fontSize = 12.sp)
            }
            trailing()
        }
    }
}
