---
name: spike-prototipe
description: Jalankan prototipe sekali pakai untuk menguji kelayakan ide, membandingkan pendekatan, dan melaporkan vonis sebelum membangun sungguhan.
---

# Spike

Pakai saat pengguna ingin menguji ide sebelum berkomitmen ("coba dulu", "apakah mungkin", "bandingkan A dan B"). Jangan pakai bila membaca dokumentasi/kode sudah menjawab, atau pengguna jelas minta implementasi final.

## Alur
1. **Pertanyaan** - rumuskan satu pertanyaan kelayakan yang konkret.
2. **Riset** - baca secukupnya untuk memilih pendekatan yang masuk akal.
3. **Bangun** - buat artefak sekecil mungkin di `.tmp/spikes/<slug>/`.
4. **Uji tekan** - coba satu kasus tepi atau skenario gagal.
5. **Vonis** - `TERBUKTI`, `SEBAGIAN`, atau `GAGAL`.

## Format vonis
```
## Vonis: TERBUKTI | SEBAGIAN | GAGAL
Pertanyaan: ...
Bukti: perintah/keluaran/pengukuran nyata (atau "tidak bisa dijalankan di sini" + apa yang perlu dicek)
Yang berhasil: ...
Yang gagal atau mengejutkan: ...
Rekomendasi: lanjut / sesuaikan / hindari + langkah produksi berikutnya
```

Kode spike tidak boleh digabung ke produksi apa adanya; tulis ulang dengan benar.
