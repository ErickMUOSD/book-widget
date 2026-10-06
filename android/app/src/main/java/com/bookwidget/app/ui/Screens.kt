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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bookwidget.app.data.BookSummary
import com.bookwidget.app.data.Level
import com.bookwidget.app.data.Theme
import com.bookwidget.app.data.WidgetState
import com.bookwidget.app.data.scramble
import kotlinx.coroutines.delay

private fun themeColor(hex: String, fallback: Long): Color =
    runCatching { Color(android.graphics.Color.parseColor(hex)) }.getOrDefault(Color(fallback))

@Composable
fun App(vm: AppViewModel) {
    BackHandler(enabled = vm.screen != Screen.Books) { vm.screen = Screen.Books }
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).safeDrawingPadding()) {
        when {
            !vm.loaded -> CircularProgressIndicator(Modifier.align(Alignment.Center))
            vm.config == null -> SetupScreen(vm)
            else -> when (val s = vm.screen) {
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
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("📖 Book Widget", fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 22.sp)
        Text("Conecta con tu servidor. El token se crea en el panel web → Ajustes → Dispositivos.")
        OutlinedTextField(
            url, { url = it }, label = { Text("URL del servidor") }, placeholder = { Text("http://192.168.1.50:8000") },
            singleLine = true, modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
        )
        OutlinedTextField(
            token, { token = it }, label = { Text("Token del dispositivo") }, singleLine = true,
            visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth(),
        )
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Button(
            enabled = !busy && url.isNotBlank() && token.isNotBlank(),
            onClick = {
                busy = true
                error = null
                vm.connect(url, token) { err -> busy = false; error = err }
            },
        ) { Text(if (busy) "Conectando…" else "Conectar") }
    }
}

// ---------------------------------------------------------------- libros
private fun statusLabel(b: BookSummary) = when (b.status) {
    "ready" -> "✓ Listo · cap. ${b.currentChapter}/${b.totalChapters}"
    "pending" -> "⏳ Preparando…"
    "unknown" -> "? Necesita datos (usa el panel web)"
    else -> "⚠ ${b.statusDetail.ifBlank { "Error" }}"
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
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("📖 Mis libros", fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 18.sp)
            Spacer(Modifier.weight(1f))
            TextButton(onClick = { vm.screen = Screen.Settings }) { Text("Ajustes") }
        }
        vm.error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(vertical = 6.dp)) }
        if (vm.books.isEmpty()) {
            Text("Aún no hay libros. Añade el primero.", modifier = Modifier.padding(vertical = 24.dp))
        }
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(vm.books, key = { it.id }) { b ->
                val border = themeColor(b.theme.border, 0xFF9CA3AF)
                Card(
                    Modifier.fillMaxWidth().border(2.dp, border, RoundedCornerShape(12.dp))
                        .clickable { vm.openBook(b.id) },
                ) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(b.title, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                            if (b.isActive) Text("en el widget", fontSize = 11.sp, color = border)
                        }
                        Text("${b.author.ifBlank { "—" }} · ${b.theme.name}", fontSize = 12.sp, color = Color.Gray)
                        Text(statusLabel(b), fontSize = 13.sp)
                        if (b.status == "ready" && !b.isActive) {
                            TextButton(onClick = { vm.activate(b.id) }) { Text("Usar en el widget") }
                        }
                    }
                }
            }
        }
        Button(onClick = { showAdd = true }, modifier = Modifier.fillMaxWidth().padding(top = 10.dp)) {
            Text("Añadir libro")
        }
    }
    if (showAdd) AddBookDialog(vm) { showAdd = false }
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
    AlertDialog(
        onDismissRequest = { if (!busy) onClose() },
        title = { Text("Añadir libro") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(title, { title = it }, label = { Text("Título") }, singleLine = true)
                OutlinedTextField(author, { author = it }, label = { Text("Autor (opcional)") }, singleLine = true)
                TextButton(onClick = { extra = !extra }) {
                    Text(if (extra) "▾ Datos extra" else "▸ Datos extra (libros poco conocidos)")
                }
                if (extra) {
                    OutlinedTextField(
                        total, { total = it.filter(Char::isDigit).take(3) }, label = { Text("Nº de capítulos") },
                        singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    )
                    OutlinedTextField(synopsis, { synopsis = it }, label = { Text("Sinopsis breve") }, minLines = 2)
                }
                Text("Se pregeneran los pre-spoilers de todos los capítulos una sola vez.", fontSize = 12.sp, color = Color.Gray)
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            Button(
                enabled = !busy && title.isNotBlank(),
                onClick = {
                    busy = true
                    vm.addBook(title, author, synopsis, total.toIntOrNull()) { err ->
                        busy = false
                        if (err == null) onClose() else error = err
                    }
                },
            ) { Text(if (busy) "Enviando…" else "Dar de alta") }
        },
        dismissButton = { TextButton(enabled = !busy, onClick = onClose) { Text("Cancelar") } },
    )
}

// ---------------------------------------------------------------- libro
@Composable
fun BookScreen(vm: AppViewModel) {
    val s = vm.current
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        TextButton(onClick = { vm.screen = Screen.Books }) { Text("← Libros") }
        if (s == null) {
            vm.error?.let { Text(it, color = MaterialTheme.colorScheme.error) } ?: CircularProgressIndicator()
            return@Column
        }
        if (s.status != "ready") {
            Text(s.title, fontWeight = FontWeight.Bold, fontSize = 18.sp)
            Text(if (s.status == "pending") "⏳ Preparando el libro…" else s.statusDetail.ifBlank { "Requiere atención en el panel web." })
            return@Column
        }
        PreviewCard(vm, s)

        var chapter by remember(s.bookId, s.currentChapter) { mutableIntStateOf(s.currentChapter) }
        val titles = vm.chapters.associate { it.number to it.title }
        fun label(n: Int) = if (n == 0) "0 — Aún no empiezo" else "$n — ${titles[n]?.ifBlank { null } ?: "Capítulo $n"}"
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("¿En qué capítulo vas?", fontWeight = FontWeight.Bold)
                Text("Terminé el capítulo", fontSize = 13.sp)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(onClick = { chapter = (chapter - 1).coerceAtLeast(0) }) { Text("−") }
                    Text("$chapter / ${s.totalChapters}", fontFamily = FontFamily.Monospace, fontSize = 18.sp)
                    OutlinedButton(onClick = { chapter = (chapter + 1).coerceAtMost(s.totalChapters) }) { Text("+") }
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
                    fontSize = 13.sp,
                )
                Button(enabled = vm.working == null, onClick = { vm.saveProgress(chapter, Level.of(s.spoilerLevel)) }) {
                    Text(if (finished) "Generar celebración" else "Generar para el cap. ${chapter + 1}")
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(enabled = vm.working == null, onClick = { vm.regenerate() }) { Text("🎲 Nuevo personaje") }
            OutlinedButton(enabled = vm.working == null, onClick = { vm.share(true) }) { Text("📤 Sellada") }
            OutlinedButton(enabled = vm.working == null, onClick = { vm.share(false) }) { Text("📤 Revelada") }
        }
        vm.working?.let { Row(verticalAlignment = Alignment.CenterVertically) { CircularProgressIndicator(Modifier.height(20.dp).width(20.dp), strokeWidth = 2.dp); Text("  $it") } }
        vm.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    }
}

/** Misma tarjeta que el widget: tema por género, sprite animado, leyenda sellada y botones. */
@Composable
private fun PreviewCard(vm: AppViewModel, s: WidgetState) {
    val t: Theme = s.theme
    val bg = themeColor(t.bg, 0xFF1F2937)
    val card = themeColor(t.card, 0xFF2B3646)
    val border = themeColor(t.border, 0xFF9CA3AF)
    val text = themeColor(t.text, 0xFFF3F4F6)
    val accent = themeColor(t.accent, 0xFFFACC15)
    Column(
        Modifier.fillMaxWidth().background(bg).border(4.dp, border).padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(s.title.uppercase(), color = text, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, fontSize = 13.sp)
        Text("CAP. ${s.currentChapter}/${s.totalChapters}", color = accent, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
        Box(Modifier.background(card).border(3.dp, border).padding(10.dp)) {
            SpriteView(vm.frames, vm.animate, 168.dp, s.sprite?.name)
        }
        Text(s.sprite?.name.orEmpty(), color = accent, fontFamily = FontFamily.Monospace, fontSize = 11.sp)

        when {
            s.finished -> Text("FIN 📖 ¡Terminaste el libro!", color = text, textAlign = TextAlign.Center)
            s.caption == null -> Text("…", color = text)
            s.sealed -> {
                Text(scramble(s.caption), color = text.copy(alpha = 0.7f), fontFamily = FontFamily.Monospace, fontSize = 11.sp, textAlign = TextAlign.Center)
                Button(
                    onClick = { vm.setSealed(false) },
                    colors = ButtonDefaults.buttonColors(containerColor = accent, contentColor = bg),
                ) { Text("🔏 Romper sello", fontWeight = FontWeight.Bold) }
            }
            else -> Text(s.caption, color = text, textAlign = TextAlign.Center, fontSize = 15.sp)
        }

        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Level.entries.forEach { l ->
                FilterChip(selected = s.spoilerLevel == l.id, onClick = { vm.setLevel(l) }, label = { Text("${l.icon} ${l.label}") })
            }
        }
        Button(
            enabled = vm.working == null && !s.finished,
            onClick = { vm.advance() },
            colors = ButtonDefaults.buttonColors(containerColor = accent, contentColor = bg),
        ) { Text("Terminé ✓", fontWeight = FontWeight.Bold) }
    }
}

// ---------------------------------------------------------------- ajustes
@Composable
fun SettingsScreen(vm: AppViewModel) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        TextButton(onClick = { vm.screen = Screen.Books }) { Text("← Libros") }
        Text("Ajustes", fontWeight = FontWeight.Bold, fontSize = 18.sp)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Animación del personaje")
                Text("Desactívala para ahorrar batería.", fontSize = 12.sp, color = Color.Gray)
            }
            Switch(checked = vm.animate, onCheckedChange = { vm.setAnimate(it) })
        }
        Text("Servidor: ${vm.config?.baseUrl.orEmpty()}", fontSize = 13.sp)
        Text(
            "Para añadir el widget: mantén pulsada la pantalla de inicio → Widgets → Book Widget. " +
                "Se redimensiona: 2×2 muestra el personaje y «Terminé ✓»; 4×2 añade la leyenda sellada.",
            fontSize = 13.sp, color = Color.Gray,
        )
        OutlinedButton(onClick = { vm.disconnect() }) { Text("Desconectar este dispositivo") }
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
            label = { Text("Elegir capítulo") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable).fillMaxWidth(),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { n ->
                DropdownMenuItem(text = { Text(label(n)) }, onClick = { onSelect(n); expanded = false })
            }
        }
    }
}
