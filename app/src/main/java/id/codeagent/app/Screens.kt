package id.codeagent.app

import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.DateFormat
import java.util.Date

fun treeToPath(u: Uri): String {
    val id = DocumentsContract.getTreeDocumentId(u)
    val vol = id.substringBefore(':')
    val rest = id.substringAfter(':', "")
    val base = if (vol == "primary") "/storage/emulated/0" else "/storage/$vol"
    return if (rest.isEmpty()) base else "$base/$rest"
}

@Composable
fun AppRoot(vm: AppViewModel) {
    val ctx = LocalContext.current
    LaunchedEffect(vm.toast) { vm.toast?.let { Toast.makeText(ctx, it, Toast.LENGTH_LONG).show(); vm.toast = null } }
    Box(Modifier.fillMaxSize().systemBarsPadding().imePadding()) {
        val cur = vm.current
        when {
            vm.showSettings -> SettingsScreen(vm)
            cur != null -> ChatScreen(vm, cur)
            else -> HomeScreen(vm)
        }
    }
    if (vm.showModelSheet) ModelSheet(vm)
    vm.pendingAsk?.let { PermDialog(vm, it) }
}

// ---------------------------------------------------------------- Beranda
@Composable
fun HomeScreen(vm: AppViewModel) {
    var showNew by rememberSaveable { mutableStateOf(false) }
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.End) {
                IconButton({ vm.loadSessions() }) { Icon(Icons.Default.Refresh, "Muat ulang") }
                IconButton({ vm.showSettings = true }) { Icon(Icons.Default.Settings, "Pengaturan") }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Logo(44)
                Spacer(Modifier.width(12.dp))
                Text("Code Agent", fontSize = 30.sp, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.height(14.dp))
            AssistChip(onClick = {}, label = { Text("aktif · ${android.os.Build.MODEL}") },
                leadingIcon = { Box(Modifier.size(9.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary)) })
            Spacer(Modifier.height(16.dp))
            if (vm.sessions.isEmpty()) {
                Column(Modifier.fillMaxSize().padding(bottom = 80.dp), verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Belum ada sesi", fontWeight = FontWeight.SemiBold, fontSize = 18.sp)
                    Spacer(Modifier.height(6.dp))
                    Text("Ketuk \"Sesi baru\" di bawah untuk mulai.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(bottom = 90.dp)) {
                items(vm.sessions, key = { it.id }) { s ->
                    Card(Modifier.fillMaxWidth().clickable { vm.currentId = s.id },
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                        Row(Modifier.padding(start = 16.dp, top = 12.dp, bottom = 12.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(s.title, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(s.root, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(s.updated)),
                                    fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            IconButton({ vm.deleteSession(s) }) { Icon(Icons.Default.Delete, "Hapus") }
                        }
                    }
                }
            }
        }
        ExtendedFloatingActionButton(onClick = { showNew = true }, modifier = Modifier.align(Alignment.BottomEnd).padding(20.dp),
            icon = { Icon(Icons.Default.Add, null) }, text = { Text("Sesi baru") })
    }
    if (showNew) NewSessionDialog(vm) { showNew = false }
}

@Composable
fun Logo(size: Int) {
    Box(Modifier.size(size.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer), contentAlignment = Alignment.Center) {
        Text("</>", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace,
            fontSize = (size * 0.36).sp)
    }
}

@Composable
fun NewSessionDialog(vm: AppViewModel, onClose: () -> Unit) {
    var folder by remember { mutableStateOf(Store.lastFolder) }
    var name by remember { mutableStateOf("") }
    var copy by remember { mutableStateOf("") }
    var showCopy by remember { mutableStateOf(false) }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { u -> if (u != null) folder = treeToPath(u) }
    val pickCopy = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { u -> if (u != null) copy = treeToPath(u) }
    AlertDialog(onDismissRequest = onClose, title = { Text("Sesi baru") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                FilledTonalButton({ pick.launch(null) }, Modifier.fillMaxWidth()) { Text("Pilih folder proyek") }
                OutlinedTextField(folder, { folder = it }, label = { Text("Folder proyek") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(name, { name = it }, label = { Text("Nama proyek (opsional)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                TextButton({ showCopy = !showCopy }) { Text("Salin dari proyek lain (opsional)") }
                if (showCopy) {
                    OutlinedTextField(copy, { copy = it }, label = { Text("Folder sumber") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    TextButton({ pickCopy.launch(null) }) { Text("Pilih folder sumber") }
                }
            }
        },
        confirmButton = { TextButton({ if (folder.isNotBlank()) vm.newSession(folder, name, copy)?.let { onClose() } }) { Text("Mulai") } },
        dismissButton = { TextButton(onClose) { Text("Batal") } })
}

// ---------------------------------------------------------------- Chat
private val suggestions = listOf("Jelaskan proyek ini", "Cari dan perbaiki bug", "Tulis tes untuk kode ini", "/init")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(vm: AppViewModel, s: ChatSession) {
    BackHandler { vm.currentId = null }
    val ctx = LocalContext.current
    var input by remember { mutableStateOf("") }
    var menu by remember { mutableStateOf(false) }
    val list = rememberLazyListState()
    val lastLen = (s.items.lastOrNull() as? ChatItem.Assistant)?.text?.length ?: 0
    LaunchedEffect(s.items.size, lastLen) { if (s.items.isNotEmpty()) list.scrollToItem(s.items.size - 1) }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton({ vm.currentId = null }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Kembali") }
            Column(Modifier.weight(1f)) {
                Text(s.title, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                OutlinedButton({ vm.showModelSheet = true }, contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                    modifier = Modifier.height(32.dp)) {
                    Text(vm.modelLabel(s.model), fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, false))
                    Icon(Icons.Default.ArrowDropDown, null)
                }
            }
            Box {
                IconButton({ menu = true }) { Icon(Icons.Default.MoreVert, "Menu") }
                DropdownMenu(menu, { menu = false }) {
                    DropdownMenuItem(text = { Text("Ekspor percakapan") }, leadingIcon = { Icon(Icons.Default.Share, null) }, onClick = {
                        menu = false
                        ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"; putExtra(Intent.EXTRA_TEXT, s.toMarkdown())
                        }, "Ekspor"))
                    })
                    DropdownMenuItem(text = { Text("Bersihkan percakapan") }, leadingIcon = { Icon(Icons.Default.Delete, null) },
                        onClick = { menu = false; vm.send(s, "/clear") })
                    DropdownMenuItem(text = { Text("Pengaturan") }, leadingIcon = { Icon(Icons.Default.Settings, null) },
                        onClick = { menu = false; vm.showSettings = true })
                }
            }
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (s.items.isEmpty()) Welcome(vm, s)
            else LazyColumn(state = list, contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(s.items, key = { it.key }) { ItemView(it) }
                if (s.running) item { LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 4.dp)) }
            }
        }
        val cmds = remember(input, vm.commands, s.root) {
            if (input.startsWith("/") && !input.contains(' '))
                (listOf("init", "model", "effort", "compact", "clear", "bantuan") + vm.commands.map { it.name } + vm.projectCommands(s.root).map { it.name })
                    .filter { it.startsWith(input.drop(1)) }.take(8) else emptyList()
        }
        if (cmds.isNotEmpty()) Row(Modifier.padding(horizontal = 8.dp)) {
            cmds.forEach { c -> AssistChip({ input = "/$c " }, { Text("/$c") }, modifier = Modifier.padding(end = 6.dp)) }
        }
        Surface(Modifier.fillMaxWidth().padding(10.dp), shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.surfaceVariant,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)) {
            Row(Modifier.padding(horizontal = 6.dp, vertical = 4.dp), verticalAlignment = Alignment.Bottom) {
                var plus by remember { mutableStateOf(false) }
                Box {
                    IconButton({ plus = true }) { Icon(Icons.Default.Add, "Perintah cepat") }
                    DropdownMenu(plus, { plus = false }) {
                        listOf("/init" to "Buat AGENTS.md", "/compact" to "Padatkan percakapan", "/model" to "Ganti model", "/bantuan" to "Bantuan").forEach { (c, d) ->
                            DropdownMenuItem(text = { Text("$c  –  $d") }, onClick = { plus = false; input = "$c " })
                        }
                    }
                }
                TextField(input, { input = it }, Modifier.weight(1f), placeholder = { Text("Ketik pesan…") }, maxLines = 6,
                    colors = TextFieldDefaults.colors(focusedContainerColor = Color0, unfocusedContainerColor = Color0,
                        focusedIndicatorColor = Color0, unfocusedIndicatorColor = Color0))
                if (s.running) FilledTonalIconButton({ s.stop() }) { Icon(Icons.Default.Close, "Hentikan") }
                else FilledIconButton({ vm.send(s, input); input = "" }, enabled = input.isNotBlank()) { Icon(Icons.AutoMirrored.Filled.Send, "Kirim") }
            }
        }
    }
}

private val Color0 = androidx.compose.ui.graphics.Color.Transparent

@Composable
fun Welcome(vm: AppViewModel, s: ChatSession) {
    Column(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Logo(110)
        Spacer(Modifier.height(18.dp))
        Text("Apa yang ingin kamu bangun hari ini?", fontSize = 22.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
        Text("Ketik di bawah atau pilih saran", color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(18.dp))
        suggestions.forEach { t ->
            OutlinedCard(Modifier.fillMaxWidth().padding(vertical = 5.dp).clickable { vm.send(s, t) }) {
                Text(t, Modifier.padding(16.dp))
            }
        }
    }
}

@Composable
fun ItemView(it: ChatItem) {
    when (it) {
        is ChatItem.User -> Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
            Surface(shape = RoundedCornerShape(18.dp, 18.dp, 4.dp, 18.dp), color = MaterialTheme.colorScheme.primaryContainer,
                modifier = Modifier.widthIn(max = 320.dp)) {
                SelectionContainer { Text(it.text, Modifier.padding(12.dp), color = MaterialTheme.colorScheme.onPrimaryContainer) }
            }
        }
        is ChatItem.Assistant -> Column(Modifier.fillMaxWidth()) {
            if (it.reasoning.isNotEmpty()) {
                var open by remember { mutableStateOf(false) }
                Text(if (open) "▾ Proses berpikir" else "▸ Berpikir…", fontSize = 12.sp, color = MaterialTheme.colorScheme.secondary,
                    modifier = Modifier.clickable { open = !open }.padding(vertical = 2.dp))
                if (open) Text(it.reasoning.takeLast(3000), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 6.dp))
            }
            if (it.text.isNotEmpty()) SelectionContainer { Md(it.text) }
        }
        is ChatItem.Tool -> {
            var open by remember { mutableStateOf(false) }
            val c = when (it.status) {
                "gagal", "ditolak" -> MaterialTheme.colorScheme.error
                "jalan" -> MaterialTheme.colorScheme.secondary
                else -> MaterialTheme.colorScheme.primary
            }
            Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.clickable { open = !open }.padding(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (it.status == "jalan") CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                        else Icon(if (it.status == "selesai") Icons.Default.Check else Icons.Default.Warning, null, Modifier.size(16.dp), tint = c)
                        Spacer(Modifier.width(8.dp))
                        Text(it.title, fontFamily = FontFamily.Monospace, fontSize = 12.sp, maxLines = if (open) 6 else 1, overflow = TextOverflow.Ellipsis)
                    }
                    if (open && it.output.isNotEmpty()) {
                        Spacer(Modifier.height(6.dp))
                        SelectionContainer { Text(it.output.take(2500), fontFamily = FontFamily.Monospace, fontSize = 11.sp, lineHeight = 15.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    }
                }
            }
        }
        is ChatItem.Note -> Text(it.text, Modifier.fillMaxWidth(), textAlign = TextAlign.Center, fontSize = 12.sp,
            color = if (it.isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

// ---------------------------------------------------------------- Dialog & sheet
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelSheet(vm: AppViewModel) {
    var q by remember { mutableStateOf("") }
    ModalBottomSheet(onDismissRequest = { vm.showModelSheet = false }) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 24.dp)) {
            Text("Pilih model", fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(q, { q = it }, placeholder = { Text("Cari…") }, leadingIcon = { Icon(Icons.Default.Search, null) },
                singleLine = true, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(28.dp))
            val all = vm.allModels().filter { it.second.contains(q, ignoreCase = true) }
            if (all.isEmpty()) Text("Belum ada model. Tambahkan provider lalu \"Ambil model\" di Pengaturan.",
                Modifier.padding(vertical = 20.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
            LazyColumn(Modifier.heightIn(max = 420.dp)) {
                items(all, key = { it.first }) { (id, label) ->
                    Column(Modifier.fillMaxWidth().clickable { vm.chooseModel(vm.current, id); vm.showModelSheet = false }.padding(vertical = 12.dp)) {
                        Text(label.substringAfter(" · "), fontSize = 16.sp)
                        Text(label.substringBefore(" · "), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    HorizontalDivider()
                }
            }
        }
    }
}

@Composable
fun PermDialog(vm: AppViewModel, a: PermAsk) {
    AlertDialog(onDismissRequest = { vm.answerAsk(false, false) },
        title = { Text("Izin dibutuhkan") },
        text = {
            Column {
                Text("Agen ingin menjalankan tool `${a.tool}` (${a.group}):")
                Spacer(Modifier.height(8.dp))
                Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(8.dp)) {
                    Text(a.detail.take(600), Modifier.padding(10.dp), fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                }
            }
        },
        confirmButton = {
            Row {
                TextButton({ vm.answerAsk(false, false) }) { Text("Tolak") }
                TextButton({ vm.answerAsk(true, false) }) { Text("Izinkan") }
            }
        },
        dismissButton = { TextButton({ vm.answerAsk(true, true) }) { Text("Selalu izinkan ${a.group}") } })
}
