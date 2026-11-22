---
name: porting-proyek
description: Salin fitur, layar UI, atau fungsi dari folder proyek lain ke proyek saat ini sambil mengikuti struktur proyek saat ini. Pakai saat pengguna ingin menyalin, memindahkan, atau memakai ulang sesuatu dari proyek A ke proyek B.
---

# Porting dari Proyek Lain

1. Minta atau temukan path proyek sumber. Baca hanya bagian yang relevan.
2. Pelajari struktur proyek tujuan: penamaan, paket, pola arsitektur, dependensi.
3. Daftar semua yang harus ikut: kode, resource, aset, dependensi, izin, konfigurasi.
4. Tulis ulang mengikuti gaya proyek tujuan (jangan salin mentah bila konvensinya beda). Hindari menimpa file yang sudah ada tanpa membacanya.
5. Tambahkan dependensi dan registrasi yang dibutuhkan (manifest, navigasi, DI).
6. Rangkum apa yang dipindahkan, apa yang diubah, dan apa yang perlu diuji pengguna.
