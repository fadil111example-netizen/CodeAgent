package id.codeagent.app

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

// ---------- komponen kecil ----------
@Composable
private fun Section(title: String, content: @Composable ColumnScope.() -> Unit) {
    Text(title, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold, fontSize = 16.sp,
        modifier = Modifier.padding(top = 22.dp, bottom = 8.dp))
    Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp), content = content)
    }
}

@Composable
private fun Hint(t: String) = Text(t, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)

@Composable
private fun SwitchRow(title: String, desc: String, checked: Boolean, warn: Boolean = false, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 16.sp)
            Text(desc, fontSize = 12.sp, color = if (warn) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked, onChange)
    }
}

@Composable
private fun Choice(options: List<Pair<String, String>>, sel: String, onSel: (String) -> Unit) {
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { (v, label) -> FilterChip(selected = sel == v, onClick = { onSel(v) }, label = { Text(label) }) }
    }
}

@Composable
private fun Field(label: String, value: String, onChange: (String) -> Unit, single: Boolean = true, mono: Boolean = false, lines: Int = 1) {
    OutlinedTextField(value, onChange, label = { Text(label) }, singleLine = single, minLines = if (single) 1 else lines,
        textStyle = if (mono) androidx.compose.ui.text.TextStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp) else androidx.compose.ui.text.TextStyle.Default,
        modifier = Modifier.fillMaxWidth())
}

@Composable
private fun FormDialog(title: String, onClose: () -> Unit, okEnabled: Boolean, onOk: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    AlertDialog(onDismissRequest = onClose, title = { Text(title) },
        text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp), content = content) },
        confirmButton = { TextButton(onOk, enabled = okEnabled) { Text("Simpan") } },
        dismissButton = { TextButton(onClose) { Text("Batal") } })
}

@Composable
private fun ItemRow(title: String, sub: String, onDelete: (() -> Unit)?, extra: @Composable RowScope.() -> Unit = {}) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.Medium)
            Text(sub, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        extra()
        if (onDelete != null) IconButton(onDelete) { Icon(Icons.Default.Delete, "Hapus", tint = MaterialTheme.colorScheme.error) }
    }
}

// ---------- status izin ----------
private fun notifOk(c: Context) = Build.VERSION.SDK_INT < 33 ||
    ContextCompat.checkSelfPermission(c, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
private fun batteryOk(c: Context) = c.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(c.packageName)
private fun filesOk() = if (Build.VERSION.SDK_INT >= 30) Environment.isExternalStorageManager() else true

@Composable
private fun PermRow(title: String, desc: String, ok: Boolean, action: String, onClick: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) { Text(title, fontSize = 16.sp); Hint(desc) }
        if (ok) Text("✓ Diizinkan", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
        else FilledTonalButton(onClick) { Text(action) }
    }
}

// ---------- layar ----------
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(vm: AppViewModel) {
    BackHandler { vm.showSettings = false }
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val tick = vm.permTick
    val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { vm.permTick++ }
    var provDialog by remember { mutableStateOf<Pair<Provider?, Preset?>?>(null) }
    var fallbackDialog by remember { mutableStateOf(false) }
    var mcpDialog by remember { mutableStateOf(false) }
    var ruleDialog by remember { mutableStateOf(false) }
    var cmdDialog by remember { mutableStateOf(false) }
    var skillDialog by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf("") }
    fun toast(t: String) = Toast.makeText(ctx, t, Toast.LENGTH_LONG).show()
    fun pkgUri() = Uri.parse("package:${ctx.packageName}")

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton({ vm.showSettings = false }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Kembali") }
            Text("Pengaturan", fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 40.dp)) {

            // --- Provider ---
            Section("Provider & kunci API") {
                Hint("Tambahkan beberapa provider sekaligus. Aktifkan provider yang ingin dipakai; kunci lain tetap tersimpan. Mendukung gaya OpenAI-compatible dan Anthropic. Satu provider boleh punya banyak kunci (satu per baris) — dipakai bergantian saat batas tercapai.")
                if (vm.providers.isEmpty()) Hint("Belum ada provider.")
                vm.providers.forEach { p ->
                    Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surface) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(Modifier.size(40.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer), contentAlignment = Alignment.Center) {
                                    Text(p.name.take(1).uppercase(), fontWeight = FontWeight.Bold)
                                }
                                Spacer(Modifier.width(10.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(p.name, fontWeight = FontWeight.SemiBold)
                                    Text("${if (p.style == "anthropic") "Anthropic" else "OpenAI-compatible"} · ${p.baseUrl}", fontSize = 11.sp,
                                        maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Text("${p.keys.size} kunci · ${p.models.size} model", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                IconButton({ provDialog = p to null }) { Icon(Icons.Default.Edit, "Ubah") }
                                IconButton({
                                    vm.ubahProviders(vm.providers.filter { it.id != p.id })
                                    vm.ubahFallbacks(vm.fallbacks.filter { it.providerId != p.id })
                                }) { Icon(Icons.Default.Delete, "Hapus", tint = MaterialTheme.colorScheme.error) }
                            }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Switch(p.enabled, { on -> vm.ubahProviders(vm.providers.map { if (it.id == p.id) it.copy(enabled = on) else it }) })
                                Text(if (p.enabled) "  AKTIF" else "  mati", fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)
                                Spacer(Modifier.weight(1f))
                                TextButton(enabled = busy.isEmpty(), onClick = {
                                    busy = p.id
                                    scope.launch {
                                        try {
                                            val ms = withContext(Dispatchers.IO) { Llm.fetchModels(p, p.keys.firstOrNull() ?: "") }
                                            vm.ubahProviders(vm.providers.map { if (it.id == p.id) it.copy(models = ms) else it })
                                            toast("${ms.size} model dimuat dari ${p.name}")
                                        } catch (e: Exception) { toast("Gagal ambil model: ${e.message?.take(200)}") }
                                        busy = ""
                                    }
                                }) {
                                    if (busy == p.id) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp) else Icon(Icons.Default.Refresh, null, Modifier.size(18.dp))
                                    Spacer(Modifier.width(6.dp)); Text("Ambil model")
                                }
                            }
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton({ provDialog = null to PRESETS.first() }) { Icon(Icons.Default.Add, null); Spacer(Modifier.width(6.dp)); Text("Tambah provider") }
                    OutlinedButton({ provDialog = null to null }) { Text("Endpoint kustom") }
                }
                Hint("Cadangan provider & kunci (berisi kunci API — simpan dengan aman).")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton({
                        (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("cadangan", Store.exportAll()))
                        toast("Cadangan disalin ke clipboard")
                    }) { Text("Salin cadangan") }
                    OutlinedButton({
                        try {
                            val t = (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).primaryClip?.getItemAt(0)?.text?.toString() ?: ""
                            val n = Store.importAll(t)
                            vm.ubahProviders(Store.providers); vm.ubahFallbacks(Store.fallbacks)
                            toast("$n provider diimpor")
                        } catch (e: Exception) { toast("Impor gagal: format clipboard tidak valid") }
                    }) { Text("Impor dari clipboard") }
                }
            }

            // --- Auto-switch ---
            Section("Beralih otomatis (saat batas tercapai)") {
                SwitchRow("Beralih otomatis saat batas tercapai",
                    "Semua kunci model terpilih dicoba dulu (kunci berikutnya saat satu habis). Lalu urutan fallback di bawah.",
                    vm.autoSwitch) { vm.ubahAutoSwitch(it) }
                Text("Urutan fallback", fontWeight = FontWeight.Medium)
                if (vm.fallbacks.isEmpty()) Hint("Belum ada fallback. Tambahkan provider + model.")
                vm.fallbacks.forEachIndexed { i, f ->
                    ItemRow("${i + 1}. ${vm.providers.firstOrNull { it.id == f.providerId }?.name ?: "?"}", f.model,
                        { vm.ubahFallbacks(vm.fallbacks.filterIndexed { j, _ -> j != i }) })
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton({ fallbackDialog = true }) { Icon(Icons.Default.Add, null); Spacer(Modifier.width(6.dp)); Text("Tambah fallback") }
                    TextButton({ Store.resetFlags(); toast("Penanda batas direset") }) { Text("Reset penanda batas") }
                }
                Hint("Jika semua kunci/provider mencapai batas, aplikasi berhenti dan memberi tahu. Penanda dihapus otomatis besok.")
            }

            // --- Izin ---
            Section("Izin") {
                SwitchRow("Setujui semua izin otomatis", "Berisiko: edit file dan perintah shell berjalan tanpa bertanya.",
                    vm.autoApprove, warn = true) { vm.ubahAutoApprove(it) }
            }

            // --- Latar belakang ---
            Section("Kerja di latar belakang & suara") {
                Hint("Izin ini membuat agen tetap bekerja saat layar terkunci atau aplikasi ditutup.")
                PermRow("Notifikasi", "Menampilkan notifikasi \"bekerja\" yang menjaga agen tetap hidup dan memberi tahu saat tugas selesai.",
                    notifOk(ctx), "Izinkan") { if (Build.VERSION.SDK_INT >= 33) notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) }
                PermRow("Jalan di latar belakang (baterai)", "Agen tetap coding saat layar mati. Pilih \"Izinkan\" / \"Tanpa batasan\".",
                    batteryOk(ctx), "Izinkan") {
                    ctx.startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, pkgUri()))
                }
                PermRow("Akses semua file", "Agen membaca dan menulis folder proyek di penyimpanan internal atau kartu SD.",
                    filesOk(), "Izinkan") {
                    try { ctx.startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, pkgUri())) }
                    catch (e: Exception) { ctx.startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)) }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Mulai otomatis / aktivitas latar", fontSize = 16.sp)
                        Hint("Di Xiaomi, Oppo, Vivo, Realme, Samsung dll., aktifkan Auto-start, atur Baterai ke \"Tanpa batasan\", dan jangan masukkan ke \"aplikasi tidur\". Tidak bisa dideteksi, cek sekali saja.")
                    }
                    FilledTonalButton({ ctx.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkgUri())) }) { Text("Buka") }
                }
                HorizontalDivider()
                SwitchRow("Pesan suara saat tugas selesai", "Mengucapkan \"Tugas selesai, Bos.\" saat agen selesai ketika aplikasi di latar belakang.",
                    vm.voice) { vm.ubahVoice(it) }
                TextButton({ vm.speak("Tugas selesai, Bos.") }) { Text("Tes suara") }
            }

            // --- Penalaran ---
            Section("Penalaran") {
                Text("Tingkat penalaran (default)", fontWeight = FontWeight.Medium)
                Choice(listOf("auto" to "Otomatis", "low" to "Rendah", "medium" to "Sedang", "high" to "Tinggi"), vm.effort) { vm.ubahEffort(it) }
                Hint("Berlaku untuk model penalaran gaya OpenAI-compatible (GPT-5/o-series, DeepSeek, Gemini). Ketik /effort low|medium|high di chat untuk mengubahnya hanya untuk sesi itu. Gaya Anthropic tidak memakai pengaturan ini.")
            }

            // --- MCP ---
            Section("Server MCP") {
                Hint("Server MCP jarak jauh (HTTP). Tool-nya otomatis tersedia untuk agen. Contoh: https://mcp.context7.com/mcp")
                vm.mcp.forEach { m ->
                    ItemRow(m.name, m.url, { vm.ubahMcp(vm.mcp.filter { it.name != m.name }) }) {
                        TextButton({
                            scope.launch {
                                val r = withContext(Dispatchers.IO) { McpRegistry.load(listOf(m.copy(enabled = true))) }
                                toast(if (r.errors.isEmpty()) "OK: ${r.specs.size} tool tersedia" else r.errors.first())
                            }
                        }) { Text("Tes") }
                        Switch(m.enabled, { on -> vm.ubahMcp(vm.mcp.map { if (it.name == m.name) it.copy(enabled = on) else it }) })
                    }
                }
                FilledTonalButton({ mcpDialog = true }) { Icon(Icons.Default.Add, null); Spacer(Modifier.width(6.dp)); Text("Tambah server MCP") }
            }

            // --- Aturan izin ---
            Section("Aturan izin") {
                Hint("Izinkan/tanya/tolak berdasarkan kelompok tool (read, edit, bash, web, mcp) dan pola isi (path/perintah/URL). * berlaku sebagai wildcard. Aturan terakhir yang cocok menang.")
                if (vm.rules.isEmpty()) Hint("Belum ada aturan.")
                vm.rules.forEachIndexed { i, r ->
                    ItemRow("${r.action.uppercase()}  ${r.tool}", r.pattern, { vm.ubahRules(vm.rules.filterIndexed { j, _ -> j != i }) })
                }
                FilledTonalButton({ ruleDialog = true }) { Icon(Icons.Default.Add, null); Spacer(Modifier.width(6.dp)); Text("Tambah aturan") }
            }

            // --- Perintah ---
            Section("Perintah kustom") {
                Hint("Slash command buatanmu. Ketik /nama di chat. Pakai \$ARGUMENTS atau \$1 \$2 di prompt. File .codeagent/commands/*.md di proyek ikut dimuat otomatis.")
                if (vm.commands.isEmpty()) Hint("Belum ada perintah kustom.")
                vm.commands.forEachIndexed { i, c -> ItemRow("/${c.name}", c.prompt, { vm.ubahCommands(vm.commands.filterIndexed { j, _ -> j != i }) }) }
                FilledTonalButton({ cmdDialog = true }) { Icon(Icons.Default.Add, null); Spacer(Modifier.width(6.dp)); Text("Tambah perintah") }
            }

            // --- Skill ---
            Section("Skill") {
                Hint("Skill = instruksi yang dimuat agen sendiri saat dibutuhkan (tool skill). File .codeagent/skills/<nama>/SKILL.md atau .claude/skills di proyek terdeteksi otomatis.")
                val builtin = remember { vm.allSkills(null).filter { s -> vm.userSkills.none { it.name == s.name } } }
                builtin.forEach { ItemRow(it.name, it.description, null) }
                vm.userSkills.forEachIndexed { i, s -> ItemRow(s.name, s.description, { vm.ubahUserSkills(vm.userSkills.filterIndexed { j, _ -> j != i }) }) }
                FilledTonalButton({ skillDialog = true }) { Icon(Icons.Default.Add, null); Spacer(Modifier.width(6.dp)); Text("Tambah skill") }
            }

            // --- Tampilan ---
            Section("Tampilan") {
                Text("Tema", fontWeight = FontWeight.Medium)
                Choice(listOf("system" to "Sistem", "light" to "Terang", "dark" to "Gelap"), vm.theme) { vm.ubahTheme(it) }
            }

            // --- Koneksi ---
            Section("Koneksi") {
                Hint("Mode"); Text("Di perangkat")
                Hint("Folder proyek terakhir"); Text(Store.lastFolder, fontFamily = FontFamily.Monospace, fontSize = 13.sp)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Akses penyimpanan", Modifier.weight(1f), fontSize = 16.sp)
                    Text(if (filesOk()) "AKTIF ✓" else "BELUM", color = if (filesOk()) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                        fontWeight = FontWeight.SemiBold)
                }
            }

            Section("Tentang") {
                Text("Code Agent v1.0.0", fontWeight = FontWeight.SemiBold)
                Hint("Agen coding AI di perangkat. Bawa kunci API sendiri: kunci dan percakapan disimpan lokal di ponsel dan hanya dikirim ke provider yang kamu pilih.")
            }
        }
    }

    // ---------- dialog ----------
    provDialog?.let { (edit, preset) -> ProviderDialog(edit, preset, { provDialog = null }) { p ->
        vm.ubahProviders(if (vm.providers.any { it.id == p.id }) vm.providers.map { if (it.id == p.id) p else it } else vm.providers + p)
        if (vm.selected.isBlank() && p.models.isNotEmpty()) vm.ubahSelected(p.id + "|" + p.models.first())
        provDialog = null
    } }
    if (fallbackDialog) FallbackDialog(vm, { fallbackDialog = false })
    if (mcpDialog) McpDialog({ mcpDialog = false }) { m -> vm.ubahMcp(vm.mcp.filter { it.name != m.name } + m); mcpDialog = false }
    if (ruleDialog) RuleDialog({ ruleDialog = false }) { r -> vm.ubahRules(vm.rules + r); ruleDialog = false }
    if (cmdDialog) {
        var n by remember { mutableStateOf("") }; var p by remember { mutableStateOf("") }
        FormDialog("Perintah baru", { cmdDialog = false }, n.isNotBlank() && p.isNotBlank(), {
            vm.ubahCommands(vm.commands.filter { it.name != n.trim() } + Command(n.trim().removePrefix("/").replace(" ", "-"), p)); cmdDialog = false
        }) {
            Field("Nama (tanpa /)", n, { n = it })
            Field("Prompt (boleh pakai \$ARGUMENTS, \$1, \$2)", p, { p = it }, single = false, lines = 4)
        }
    }
    if (skillDialog) {
        var n by remember { mutableStateOf("") }; var d by remember { mutableStateOf("") }; var b by remember { mutableStateOf("") }
        FormDialog("Skill baru", { skillDialog = false }, n.isNotBlank() && d.isNotBlank() && b.isNotBlank(), {
            vm.ubahUserSkills(vm.userSkills.filter { it.name != n.trim() } + SkillDef(n.trim().lowercase().replace(" ", "-"), d.trim(), b.trim())); skillDialog = false
        }) {
            Field("Nama (huruf kecil-tanda-hubung)", n, { n = it })
            Field("Deskripsi pemicu (kapan dipakai)", d, { d = it }, single = false, lines = 2)
            Field("Instruksi (Markdown)", b, { b = it }, single = false, lines = 6)
        }
    }
}

@Composable
private fun ProviderDialog(edit: Provider?, preset: Preset?, onClose: () -> Unit, onSave: (Provider) -> Unit) {
    var name by remember { mutableStateOf(edit?.name ?: preset?.name ?: "") }
    var url by remember { mutableStateOf(edit?.baseUrl ?: preset?.baseUrl ?: "") }
    var style by remember { mutableStateOf(edit?.style ?: preset?.style ?: "openai") }
    var models by remember { mutableStateOf((edit?.models ?: preset?.models?.split(",")?.filter { it.isNotBlank() } ?: emptyList()).joinToString(",")) }
    var keys by remember { mutableStateOf(edit?.keys?.joinToString("\n") ?: "") }
    var show by remember { mutableStateOf(false) }
    val ctx = LocalContext.current
    FormDialog(if (edit == null) "Provider baru" else "Ubah provider", onClose, name.isNotBlank() && url.isNotBlank(), {
        onSave(Provider(edit?.id ?: UUID.randomUUID().toString().take(8), name.trim(), style, url.trim(),
            keys.lines().map { it.trim() }.filter { it.isNotEmpty() }, models.split(",").map { it.trim() }.filter { it.isNotEmpty() }, edit?.enabled ?: true))
    }) {
        if (edit == null) {
            Text("Preset", fontSize = 12.sp)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                PRESETS.forEach { p -> AssistChip({ name = p.name; url = p.baseUrl; style = p.style; models = p.models }, { Text(p.name) }) }
            }
        }
        Field("Nama", name, { name = it })
        Field("Base URL", url, { url = it })
        Text("Gaya API", fontSize = 12.sp)
        Choice(listOf("openai" to "OpenAI-compatible", "anthropic" to "Anthropic"), style) { style = it }
        Field("ID model (pisahkan koma)", models, { models = it }, single = false, lines = 2)
        Field("Kunci API (satu per baris; kosongkan untuk lokal)", keys, { keys = it }, single = false, mono = true, lines = 3)
        Row {
            TextButton({ show = !show }) { Text(if (show) "Sembunyikan" else "Lihat jumlah") }
            TextButton({
                val t = (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).primaryClip?.getItemAt(0)?.text?.toString()?.trim() ?: ""
                if (t.isNotEmpty()) keys = if (keys.isBlank()) t else keys.trimEnd() + "\n" + t
            }) { Text("Tempel kunci") }
        }
        if (show) Hint("${keys.lines().count { it.isNotBlank() }} kunci terisi")
    }
}

@Composable
private fun FallbackDialog(vm: AppViewModel, onClose: () -> Unit) {
    var sel by remember { mutableStateOf("") }
    val all = vm.allModels()
    AlertDialog(onDismissRequest = onClose, title = { Text("Tambah fallback") },
        text = {
            if (all.isEmpty()) Text("Belum ada model. Tambahkan provider dan ambil modelnya dulu.")
            else Column(Modifier.verticalScroll(rememberScrollState())) {
                all.forEach { (id, label) ->
                    Row(Modifier.fillMaxWidth().clickable { sel = id }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(sel == id, { sel = id }); Text(label, fontSize = 14.sp)
                    }
                }
            }
        },
        confirmButton = {
            TextButton({
                val (pid, m) = sel.split("|", limit = 2).let { it[0] to it.getOrElse(1) { "" } }
                vm.ubahFallbacks(vm.fallbacks + Fallback(pid, m)); onClose()
            }, enabled = sel.isNotEmpty()) { Text("Tambah") }
        },
        dismissButton = { TextButton(onClose) { Text("Batal") } })
}

@Composable
private fun McpDialog(onClose: () -> Unit, onSave: (McpServer) -> Unit) {
    var n by remember { mutableStateOf("") }; var u by remember { mutableStateOf("https://") }; var h by remember { mutableStateOf("") }
    FormDialog("Server MCP", onClose, n.isNotBlank() && u.startsWith("http"), { onSave(McpServer(n.trim(), u.trim(), h.trim(), true)) }) {
        Field("Nama (mis. context7)", n, { n = it })
        Field("URL", u, { u = it })
        Field("Header (opsional, satu per baris: Nama: nilai)", h, { h = it }, single = false, mono = true, lines = 3)
    }
}

@Composable
private fun RuleDialog(onClose: () -> Unit, onSave: (PermRule) -> Unit) {
    var tool by remember { mutableStateOf("bash") }; var pat by remember { mutableStateOf("*") }; var act by remember { mutableStateOf("allow") }
    FormDialog("Aturan izin", onClose, pat.isNotBlank(), { onSave(PermRule(tool, pat.trim(), act)) }) {
        Text("Kelompok tool", fontSize = 12.sp)
        Choice(listOf("read" to "read", "edit" to "edit", "bash" to "bash", "web" to "web", "mcp" to "mcp", "*" to "semua"), tool) { tool = it }
        Field("Pola (path / perintah / URL, * = wildcard)", pat, { pat = it }, mono = true)
        Text("Tindakan", fontSize = 12.sp)
        Choice(listOf("allow" to "Izinkan", "ask" to "Tanya", "deny" to "Tolak"), act) { act = it }
    }
}
