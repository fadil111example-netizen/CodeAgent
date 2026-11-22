---
name: debug-sistematis
description: Cari akar masalah bug, crash, keluaran salah, atau perilaku gagal sebelum mengubah kode. Pakai saat ada yang rusak dan penyebabnya belum jelas.
---

# Debug Sistematis

1. **Reproduksi** - pastikan gejala bisa dipicu; catat pesan error dan langkahnya.
2. **Persempit** - telusuri dari titik gejala ke belakang (stack trace, `grep` nama fungsi, baca pemanggilnya).
3. **Hipotesis** - tulis 1-3 dugaan penyebab beserta bukti yang akan membenarkan atau menyangkalnya.
4. **Buktikan** - uji dugaan paling mungkin dengan log, pembacaan kode, atau perintah. Jangan menebak lalu menambal.
5. **Perbaiki akar masalah** - perubahan sekecil mungkin yang menghilangkan penyebab, bukan gejala.
6. **Verifikasi** - ulangi reproduksi dan periksa efek samping di tempat lain.
