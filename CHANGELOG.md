# Catatan perubahan

## Tahap 1 (dari analisis dekompilasi Clozed)
- Tool baru: `patch` (multi-file), `diagnostics`, `question` (dialog tanya-jawab), `todowrite` terstruktur (panel rencana di chat)
- `read`: saran "Did you mean", deteksi file biner, paginasi folder; `glob`/`grep`: opsi hidden, limit, literal, caseSensitive, pola `{a,b}`
- `edit`: pencocokan toleran (kutip/dash/spasi tipografis, spasi akhir baris), CRLF dipertahankan, catatan "Syntax check" otomatis
- `bash`: workdir, timeout (ms), background + notifikasi saat selesai
- `webfetch`: format text/markdown/html; `skill` memakai parameter `id`
- Loop agen: deteksi loop (peringatan lalu berhenti), batas langkah dengan jawaban teks terakhir, perbaikan otomatis argumen tool yang salah tipe
- Error provider: kuota habis (tandai sampai besok) vs batas sementara (tunggu 20 dtk lalu coba lagi), tunggu jaringan saat offline, mode keluaran kecil saat balasan macet
- Anthropic: prompt caching (`cache_control`) untuk api.anthropic.com
- Log crash di Pengaturan

## Tahap 2 (dari kode fallback LocalBackend/Llm hasil jadx)
- Loop agen disamakan: batas 60 langkah (langkah terakhir tanpa tool), deteksi loop (pola berulang dan bolak-balik, riwayat 30 panggilan), jawaban teks wajib saat macet
- Subagen: tool `task` (explore = baca saja, general = kerja penuh)
- Mode Plan/Build (chip di atas chat, `/plan`, `/build`): Plan hanya baca dan hanya boleh menulis rencana di `.codeagent/plan/`
- `/undo`: mengembalikan file yang diubah pada giliran terakhir dan menghapus giliran itu (teks pesan dikembalikan ke kolom input)
- `/port <folder sumber> [fitur]`: folder sumber read-only, kode ditulis ulang mengikuti struktur proyek tujuan
- Tool `execute` (JavaScript/Rhino): memanggil banyak tool dalam satu langkah
- Konteks: output lama dipangkas pada 60% jendela konteks, percakapan diringkas otomatis pada 75% (jendela konteks bisa dipilih di Pengaturan)
- Balasan terpotong (batas token) dikenali: tool call tidak dijalankan dan model diminta mencoba lebih kecil
- Judul sesi dibuat otomatis oleh model; balasan kosong dan tool tidak dikenal ditangani seperti aslinya
- Prompt sistem diperkaya (disiplin eksekusi, blok `<env>`, folder sumber)
