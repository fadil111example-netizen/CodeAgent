# Code Agent

Agen coding AI **di dalam ponsel Android** (on-device). Kamu memilih folder proyek, mengobrol dengan model AI, dan agen membaca/menulis/mengedit file, mencari kode, menjalankan perintah shell, mengambil halaman web, memakai server MCP, dan memuat *skill* — semuanya dari HP.

Seluruh tampilan dan teks berbahasa Indonesia. Kode ditulis sendiri dari nol (Kotlin + Jetpack Compose), terinspirasi dari alur kerja aplikasi agen coding mobile.

## Fitur

| Area | Isi |
|---|---|
| Provider | **OpenAI-compatible** dan **Anthropic** (streaming + tool calling). Preset: OpenRouter, Anthropic, OpenAI, DeepSeek, Groq, Gemini, Mistral, Cerebras, GitHub Models, NVIDIA NIM, Ollama, LM Studio, plus endpoint kustom |
| Kunci API | Banyak kunci per provider, dipakai bergantian saat batas/kuota habis |
| Beralih otomatis | Kunci berikutnya → urutan fallback (provider + model). Penanda batas hilang otomatis besok |
| Tool agen | `read`, `write`, `edit`, `list`, `glob`, `grep`, `bash`, `webfetch`, `websearch`, `todowrite`, `skill` + semua tool dari server MCP |
| Izin | Tanya per tool, aturan allow/ask/deny dengan wildcard (aturan terakhir menang), opsi setujui semua, tombol "Selalu izinkan" |
| MCP | Server MCP HTTP (JSON atau SSE), header kustom, tombol Tes |
| Skill | 6 skill bawaan (bahasa Indonesia) + skill sendiri + `.codeagent/skills/*/SKILL.md` & `.claude/skills` di proyek |
| Perintah | `/init`, `/model`, `/effort`, `/compact`, `/clear`, `/bantuan`, perintah kustom (`$ARGUMENTS`, `$1`…) + `.codeagent/commands/*.md` |
| Latar belakang | Foreground service + wake lock, notifikasi selesai, pesan suara "Tugas selesai, Bos." |
| Proyek | `AGENTS.md` / `CODEAGENT.md` / `CLAUDE.md` otomatis dibaca; salin dari proyek lain saat membuat sesi |
| Ekstra | Pencarian web, ekspor percakapan, cadangan/impor provider via clipboard, saran perintah slash, tema Sistem/Terang/Gelap |

## Build lewat GitHub Actions

1. Buat repo **private** di GitHub, unggah seluruh isi folder ini (termasuk `.github/`).
2. Tab **Actions** → workflow **Build APK** berjalan otomatis saat push ke `main` (atau klik *Run workflow*).
3. Setelah hijau, unduh artefak **CodeAgent-debug-apk** → ekstrak → pasang `app-debug.apk`.

Workflow memakai Gradle 8.9 + JDK 17 langsung dari `setup-gradle`, jadi **tidak perlu** `gradlew`/`gradle-wrapper.jar`.
Bila build gagal, salin log error dari langkah *Build debug APK* — biasanya perbaikannya satu-dua baris.

## Cara pakai

1. Buka **Pengaturan → Provider & kunci API → Tambah provider**, pilih preset, isi kunci, simpan, lalu **Ambil model**.
2. Beri izin **Akses semua file**, **Notifikasi**, dan **Baterai: tanpa batasan** (Pengaturan → Kerja di latar belakang).
3. Kembali → **Sesi baru** → pilih folder proyek → mulai mengobrol. Ketik `/init` untuk membuat `AGENTS.md`.
4. Untuk Claude: pilih preset **Anthropic** (gaya API *Anthropic*). Gateway pihak ketiga yang meniru API Anthropic juga bisa: isi Base URL-nya dan pilih gaya *Anthropic*.

> Catatan: `bash` berjalan di shell Android (sh/toybox). Compiler dan build APK tidak tersedia di ponsel; agen akan menjelaskan perintah yang perlu dijalankan di tempat lain.

## Daftar situs API gratis / berkuota gratis

Dicek dari beberapa kurasi publik per pertengahan 2026. **Batas gratis sering berubah** — selalu cek halaman resmi sebelum mengandalkannya. Semua di bawah bisa dipakai lewat preset OpenAI-compatible (kecuali dicatat).

| Penyedia | Isi | Base URL | Ambil kunci |
|---|---|---|---|
| **OpenRouter** | Puluhan model berakhiran `:free` (DeepSeek, Qwen, Gemma, Llama, dll.), batas sekitar 20 req/menit & ~50 req/hari tanpa kredit; model gratis sering berganti | `https://openrouter.ai/api/v1` | openrouter.ai/keys |
| **Groq** | Inferensi sangat cepat, batas harian per model | `https://api.groq.com/openai/v1` | console.groq.com/keys |
| **Google AI Studio (Gemini)** | Gemini + Gemma gratis pada model tertentu, konteks besar | `https://generativelanguage.googleapis.com/v1beta/openai` | aistudio.google.com/apikey |
| **Mistral La Plateforme** | Tier gratis (butuh verifikasi nomor telepon) | `https://api.mistral.ai/v1` | console.mistral.ai |
| **Cerebras** | Tier gratis, throughput tinggi | `https://api.cerebras.ai/v1` | cloud.cerebras.ai |
| **GitHub Models** | Gratis (pratinjau) dengan akun GitHub: GPT, Llama, Phi, DeepSeek, Mistral, dll. | `https://models.github.ai/inference` | token GitHub (github.com/settings/tokens) |
| **DeepSeek Platform** | Kredit gratis untuk pengguna baru; model DeepSeek V4 | `https://api.deepseek.com/v1` | platform.deepseek.com/api_keys |
| **NVIDIA NIM** | Kredit/akses gratis untuk berbagai model terbuka | `https://integrate.api.nvidia.com/v1` | build.nvidia.com |
| **Cloudflare Workers AI** | ~10.000 Neurons gratis per hari | lihat dokumentasi Cloudflare | dash.cloudflare.com |
| **OpenCode Zen** | Gateway model untuk agen coding (ada model gratis tertentu) | lihat opencode.ai | opencode.ai/auth |
| **Nebius AI Studio** | Kredit gratis untuk pengguna baru | lihat dokumentasi Nebius | studio.nebius.com |
| **Hyperbolic / Novita AI** | Kredit uji coba untuk model terbuka (Llama, Qwen, DeepSeek) | lihat dokumentasi masing-masing | situs resmi |
| **TokenHarbor** | Gateway yang kamu pakai (mis. `deepseek-v4.1-flash:free`) | lihat dasbor tokenharbor | dasbor tokenharbor |
| **Ollama / LM Studio** | Lokal, 100% gratis, tanpa kunci | `http://127.0.0.1:11434/v1` / `:1234/v1` | — |

Kurasi yang rutin diperbarui:

- https://github.com/cheahjs/free-llm-api-resources
- https://github.com/open-free-llm-api/awesome-freellm-apis (data diperbarui harian di freellm.net)
- https://openrouter.ai/models?max_price=0 (daftar model gratis OpenRouter)

**Tips:** daftarkan 2–3 provider gratis lalu atur *Beralih otomatis* + urutan fallback, supaya saat satu kuota habis agen pindah sendiri.

## Struktur proyek

```
app/src/main/
├── assets/prompts/system.txt      prompt sistem
├── assets/skills/*/SKILL.md       skill bawaan
└── java/id/codeagent/app/
    ├── Llm.kt          klien streaming OpenAI-compatible + Anthropic
    ├── Tools.kt        tool agen (file, grep, bash, web)
    ├── Mcp.kt          klien MCP
    ├── Agent.kt        loop agen, izin, fallback kunci/provider
    ├── AppViewModel.kt state, perintah slash, skill, prompt
    ├── Session.kt      sesi & penyimpanan percakapan
    ├── Store.kt        pengaturan (SharedPreferences)
    ├── Notify.kt       notifikasi + AgentService (foreground)
    └── Screens.kt / Settings.kt / Markdown.kt / Theme.kt   UI Compose
```

## Keamanan

- Kunci API dan percakapan disimpan lokal di ponsel (tanpa server perantara).
- Jangan mengaktifkan "Setujui semua izin otomatis" bila belum percaya penuh pada model/provider yang dipakai: agen bisa mengedit file dan menjalankan perintah shell.
- Jangan membagikan hasil *Salin cadangan* — isinya kunci API.
