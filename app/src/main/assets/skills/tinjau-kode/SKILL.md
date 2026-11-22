---
name: tinjau-kode
description: Tinjau perubahan kode atau satu file untuk mencari bug, regresi, celah keamanan, dan masalah keterbacaan. Pakai saat pengguna minta review, audit, atau pemeriksaan ulang kode.
---

# Tinjau Kode

1. Tentukan cakupan: file yang disebut pengguna, atau perubahan terbaru (`git diff` bila tersedia).
2. Baca kode lengkap beserta pemakainya (cari dengan `grep`), jangan hanya potongan yang berubah.
3. Periksa berurutan: kebenaran logika, kasus tepi (null, kosong, batas), penanganan error, keamanan (input tidak tepercaya, rahasia di kode), konkurensi, lalu keterbacaan.
4. Laporkan temuan berurut dari yang paling parah. Format tiap temuan: `file:baris` - masalah - saran perbaikan singkat.
5. Jangan mengubah kode kecuali diminta. Bila tidak ada masalah, katakan jelas apa yang sudah diperiksa.
