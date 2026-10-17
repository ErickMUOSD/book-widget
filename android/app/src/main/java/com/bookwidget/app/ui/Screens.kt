package com.bookwidget.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.sharp.Add
import androidx.compose.material.icons.sharp.Casino
import androidx.compose.material.icons.sharp.Check
import androidx.compose.material.icons.sharp.Cloud
import androidx.compose.material.icons.sharp.LocalFireDepartment
import androidx.compose.material.icons.sharp.LockOpen
import androidx.compose.material.icons.sharp.Remove
import androidx.compose.material.icons.sharp.Settings
import androidx.compose.material.icons.sharp.Share
import androidx.compose.material.icons.sharp.VpnKey
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bookwidget.app.data.BookSummary
import com.bookwidget.app.data.Level
import com.bookwidget.app.data.Theme
import com.bookwidget.app.data.WidgetState
import com.bookwidget.app.data.scramble
import kotlinx.coroutines.delay

/** Icono Material «Sharp» para cada nivel de pre-spoiler. */
private fun levelIcon(level: Level): ImageVector = when (level) {
    Level.NIEBLA -> Icons.Sharp.Cloud
    Level.PISTA -> Icons.Sharp.VpnKey
    Level.PELIGRO -> Icons.Sharp.LocalFireDepartment
}

@Composable
fun App(vm: AppViewModel) {
    BackHandler(enabled = vm.screen != Screen.Books) { vm.screen = Screen.Books }
    Box(Modifier.fillMaxSize().background(Retro.Bg).safeDrawingPadding()) {
        when {
            !vm.loaded -> PixelLoader("cargando", Modifier.align(Alignment.Center))
            vm.config == null -> SetupScreen(vm)
            else -> when (vm.screen) {
                Screen.Books -> BooksScreen(vm)
                is Screen.Book -> BookScreen(vm)
                Screen.Settings -> SettingsScreen(vm)
            }
        }
    }
}

// ---------------------------------------------------------------- conexión
@Composable
fun SetupScreen(vm: AppViewModel) {
    var url by remember { mutableStateOf("") }
    var token by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(Retro.ScreenPadding),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Spacer(Modifier.size(8.dp))
        PixelText("BOOK WIDGET", size = 18.sp, color = Retro.Accent)
        Text("Conecta con tu servidor. El token se crea en el panel web → Ajustes → Dispositivos.", color = Retro.Muted, fontSize = 13.sp)
        RetroField(url, { url = it }, "URL del servidor", Modifier.fillMaxWidth(), keyboard = KeyboardOptions(keyboardType = KeyboardType.Uri))
        RetroField(token, { token = it }, "Token del dispositivo", Modifier.fillMaxWidth(), secret = true)
        error?.let { ErrorLine(it) }
        RetroButton(
            if (busy) "Conectando…" else "Conectar",
            enabled = !busy && url.isNotBlank() && token.isNotBlank(),
            onClick = {
                busy = true
                error = null
                vm.connect(url, token) { err -> busy = false; error = err }
            },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

// ---------------------------------------------------------------- libros
private fun statusLabel(b: BookSummary) = when (b.status) {
    "ready" -> "LISTO · cap. ${b.currentChapter}/${b.totalChapters}"
    "pending" -> "PREPARANDO…"
    "unknown" -> "NECESITA DATOS (usa el panel web)"
    else -> b.statusDetail.ifBlank { "ERROR" }.uppercase()
}

@Composable
fun BooksScreen(vm: AppViewModel) {
    var showAdd by remember { mutableStateOf(false) }
    val anyPending = vm.books.any { it.status == "pending" }
    LaunchedEffect(anyPending) {
        while (anyPending) {
            delay(6000)
            vm.refreshBooks()
        }
    }
    Column(Modifier.fillMaxSize().padding(Retro.ScreenPadding)) {
        Spacer(Modifier.size(8.dp))
        TopBar("Mis libros", onBack = null) {
            RetroIconButton(Icons.Sharp.Settings, "Ajustes", { vm.screen = Screen.Settings })
        }
        vm.error?.let { Box(Modifier.padding(vertical = 6.dp)) { ErrorLine(it) } }
        if (vm.books.isEmpty()) {
            Text("Aún no hay libros. Añade el primero.", color = Retro.Muted, modifier = Modifier.padding(vertical = 24.dp))
        }
        LazyColumn(Modifier.weight(1f).padding(top = 10.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            items(vm.books, key = { it.id }) { b -> BookRow(vm, b) }
        }
        RetroButton("Añadir libro", { showAdd = true }, Modifier.fillMaxWidth().padding(top = 12.dp), icon = Icons.Sharp.Add)
    }
    if (showAdd) AddBookDialog(vm) { showAdd = false }
}

@Composable
private fun BookRow(vm: AppViewModel, b: BookSummary) {
    val border = hex(b.theme.border, Retro.Line)
    RetroPanel(Modifier.fillMaxWidth().clickable { vm.openBook(b.id) }, border = border) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                PixelText(b.title, size = 9.sp, modifier = Modifier.weight(1f))
                if (b.isActive) Tag("EN EL WIDGET", color = Retro.Accent2)
            }
            Text("${b.author.ifBlank { "—" }} · ${b.theme.name}", fontSize = 12.sp, color = Retro.Muted)
            PixelText(statusLabel(b), size = 7.sp, color = if (b.status == "ready") Retro.Accent2 else Retro.Muted)
            if (b.status == "ready" && !b.isActive) {
                RetroButton("Usar en el widget", { vm.activate(b.id) }, outline = true, color = border)
            }
        }
    }
}

@Composable
private fun AddBookDialog(vm: AppViewModel, onClose: () -> Unit) {
    var title by remember { mutableStateOf("") }
    var author by remember { mutableStateOf("") }
    var extra by remember { mutableStateOf(false) }
    var total by remember { mutableStateOf("") }
    var synopsis by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    RetroDialog(
        title = "Añadir libro",
        onDismiss = { if (!busy) onClose() },
        confirm = {
            RetroButton(
                if (busy) "Enviando…" else "Dar de alta",
                enabled = !busy && title.isNotBlank(),
                onClick = {
                    busy = true
                    vm.addBook(title, author, synopsis, total.toIntOrNull()) { err ->
                        busy = false
                        if (err == null) onClose() else error = err
                    }
                },
            )
        },
    ) {
        RetroField(title, { title = it }, "Título", Modifier.fillMaxWidth())
        RetroField(author, { author = it }, "Autor (opcional)", Modifier.fillMaxWidth())
        Text(
            if (extra) "▾ Datos extra" else "▸ Datos extra (libros poco conocidos)",
            color = Retro.Accent2, fontSize = 13.sp,
            modifier = Modifier.clickable { extra = !extra }.padding(vertical = 2.dp),
        )
        if (extra) {
            RetroField(
                total, { total = it.filter(Char::isDigit).take(3) }, "Nº de capítulos", Modifier.fillMaxWidth(),
                keyboard = KeyboardOptions(keyboardType = KeyboardType.Number),
            )
            RetroArea(synopsis, { synopsis = it }, "Sinopsis breve", Modifier.fillMaxWidth())
        }
        Text("Se pregeneran los pre-spoilers de todos los capítulos una sola vez.", fontSize = 12.sp, color = Retro.Muted)
        error?.let { ErrorLine(it) }
    }
}

// ---------------------------------------------------------------- libro
@Composable
fun BookScreen(vm: AppViewModel) {
    val s = vm.current
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(Retro.ScreenPadding),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Spacer(Modifier.size(8.dp))
        TopBar(s?.title ?: "Libro", onBack = { vm.screen = Screen.Books })
        if (s == null) {
            vm.error?.let { ErrorLine(it) } ?: PixelLoader("cargando", Modifier.fillMaxWidth().padding(top = 24.dp))
            return@Column
        }
        if (s.status != "ready") {
            Text(if (s.status == "pending") "⏳ Preparando el libro…" else s.statusDetail.ifBlank { "Requiere atención en el panel web." }, color = Retro.Muted)
            return@Column
        }
        PreviewCard(vm, s)

        var chapter by remember(s.bookId, s.currentChapter) { mutableIntStateOf(s.currentChapter) }
        val titles = vm.chapters.associate { it.number to it.title }
        fun label(n: Int) = if (n == 0) "0 — Aún no empiezo" else "$n — ${titles[n]?.ifBlank { null } ?: "Capítulo $n"}"
        RetroPanel(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                PixelText("¿EN QUÉ CAPÍTULO VAS?", size = 9.sp, color = Retro.Accent2)
                Text("Terminé el capítulo", fontSize = 13.sp, color = Retro.Muted)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    RetroIconButton(Icons.Sharp.Remove, "Uno menos", { chapter = (chapter - 1).coerceAtLeast(0) })
                    PixelText("$chapter / ${s.totalChapters}", size = 13.sp)
                    RetroIconButton(Icons.Sharp.Add, "Uno más", { chapter = (chapter + 1).coerceAtMost(s.totalChapters) })
                }
                ChapterDropdown(
                    options = (0..s.totalChapters).toList(), selected = chapter, label = { label(it) },
                    onSelect = { chapter = it },
                )
                val finished = chapter >= s.totalChapters
                Text(
                    if (finished) "🎉 ¡Libro terminado! Se generará un dibujo de celebración."
                    else "Se generará para el capítulo ${chapter + 1}" +
                        (titles[chapter + 1]?.ifBlank { null }?.let { ": «$it»" } ?: "") + ".",
                    fontSize = 13.sp, color = Retro.Muted,
                )
                RetroButton(
                    if (finished) "Generar celebración" else "Generar para el cap. ${chapter + 1}",
                    enabled = vm.working == null,
                    onClick = { vm.saveProgress(chapter, Level.of(s.spoilerLevel)) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            RetroButton("Nuevo", { vm.regenerate() }, enabled = vm.working == null, icon = Icons.Sharp.Casino, outline = true)
            RetroButton("Sellada", { vm.share(true) }, enabled = vm.working == null, icon = Icons.Sharp.Share, outline = true)
            RetroButton("Revelada", { vm.share(false) }, enabled = vm.working == null, icon = Icons.Sharp.Share, outline = true)
        }
        vm.working?.let {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = Retro.Accent)
                Spacer(Modifier.width(10.dp))
                PixelText(it, size = 8.sp, color = Retro.Accent)
            }
        }
        vm.error?.let { ErrorLine(it) }
    }
}

/** Misma tarjeta que el widget: tema por género, sprite animado, leyenda sellada y botones. */
@Composable
private fun PreviewCard(vm: AppViewModel, s: WidgetState) {
    val t: Theme = s.theme
    val bg = hex(t.bg, Color(0xFF1F2937))
    val card = hex(t.card, Color(0xFF2B3646))
    val border = hex(t.border, Color(0xFF9CA3AF))
    val text = hex(t.text, Color(0xFFF3F4F6))
    val accent = hex(t.accent, Color(0xFFFACC15))
    Column(
        Modifier.fillMaxWidth().hardShadow().background(bg).border(4.dp, border).padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        PixelText(s.title.uppercase(), color = text, size = 11.sp, align = TextAlign.Center)
        PixelText("CAP. ${s.currentChapter}/${s.totalChapters}", color = accent, size = 8.sp)
        Box(Modifier.background(card).border(3.dp, border).padding(10.dp)) {
            SpriteView(vm.frames, vm.animate, 168.dp, s.sprite?.name)
        }
        s.sprite?.name?.let { PixelText(it, color = accent, size = 8.sp, align = TextAlign.Center) }

        when {
            s.finished -> Text("FIN · ¡Terminaste el libro!", color = text, textAlign = TextAlign.Center)
            s.caption == null -> Text("…", color = text)
            s.sealed -> {
                Text(scramble(s.caption), color = text.copy(alpha = 0.7f), fontFamily = FontFamily.Monospace, fontSize = 12.sp, textAlign = TextAlign.Center)
                RetroButton("Romper sello", { vm.setSealed(false) }, icon = Icons.Sharp.LockOpen, color = accent, content = bg)
            }
            else -> Text(s.caption, color = text, textAlign = TextAlign.Center, fontSize = 15.sp)
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Level.entries.forEach { l ->
                RetroChip(l.label, levelIcon(l), selected = s.spoilerLevel == l.id, onClick = { vm.setLevel(l) }, color = accent)
            }
        }
        RetroButton(
            "Terminé",
            enabled = vm.working == null && !s.finished,
            onClick = { vm.advance() },
            icon = Icons.Sharp.Check,
            color = accent,
            content = bg,
        )
    }
}

// ---------------------------------------------------------------- ajustes
@Composable
fun SettingsScreen(vm: AppViewModel) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(Retro.ScreenPadding),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Spacer(Modifier.size(8.dp))
        TopBar("Ajustes", onBack = { vm.screen = Screen.Books })
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Animación del personaje", color = Retro.Text)
                Text("Desactívala para ahorrar batería.", fontSize = 12.sp, color = Retro.Muted)
            }
            Switch(
                checked = vm.animate, onCheckedChange = { vm.toggleAnimate(it) },
                colors = SwitchDefaults.colors(checkedThumbColor = Retro.Accent, checkedTrackColor = Retro.Accent.copy(alpha = 0.4f)),
            )
        }
        Text("Servidor: ${vm.config?.baseUrl.orEmpty()}", fontSize = 13.sp, color = Retro.Muted)
        Text(
            "Para añadir el widget: mantén pulsada la pantalla de inicio → Widgets → Book Widget. " +
                "Se redimensiona: 2×2 muestra el personaje y «Terminé»; 4×2 añade la leyenda y los niveles.",
            fontSize = 13.sp, color = Retro.Muted,
        )
        RetroButton("Desconectar este dispositivo", { vm.disconnect() }, outline = true, color = Retro.Danger)
    }
}

@Composable
private fun ErrorLine(message: String) {
    Row(Modifier.border(1.dp, Retro.Danger).padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
        PixelText(message, size = 7.sp, color = Retro.Danger)
    }
}

// ---------------------------------------------------------------- selector de capítulo
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChapterDropdown(options: List<Int>, selected: Int, label: (Int) -> String, onSelect: (Int) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = label(selected), onValueChange = {}, readOnly = true, singleLine = true,
            label = { PixelText("ELEGIR CAPÍTULO", size = 7.sp, color = Retro.Muted) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            shape = RectangleShape,
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = Retro.Accent, unfocusedBorderColor = Retro.Line,
                focusedContainerColor = Retro.Panel, unfocusedContainerColor = Retro.Panel,
                focusedTextColor = Retro.Text, unfocusedTextColor = Retro.Text,
            ),
            modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable).fillMaxWidth(),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { n ->
                DropdownMenuItem(text = { Text(label(n)) }, onClick = { onSelect(n); expanded = false })
            }
        }
    }
}
