# L-TRONIK Updater (Android)

Aplikasi untuk memperbarui firmware L-TRONIK DSP lewat Bluetooth LE. Tanpa pustaka tambahan.
Protokolnya sama dengan `ltronik_dsp_update.html` dan firmware v1.9.6 ke atas.

## Membuat APK lewat GitHub (tanpa Android Studio)
1. Buat repositori baru di GitHub, lalu unggah seluruh isi folder ini ke akar repositori, termasuk folder `.github`.
   Kalau folder tersembunyi itu tidak ikut terunggah, pilih Add file > Create new file, ketik `.github/workflows/build-apk.yml`, lalu tempel isi file dengan nama sama.
2. Buka tab Actions > Build APK > Run workflow. Build selesai dalam beberapa menit.
3. Unduh APK dari bagian Artifacts di halaman run itu (berbentuk zip berisi `LtronikUpdater.apk`).
4. Cara yang lebih mudah di HP: buat tag `v1.0` (Releases > Draft a new release > ketik tag `v1.0` > Publish). Workflow otomatis menempelkan `LtronikUpdater.apk` di halaman rilis, jadi bisa diunduh langsung tanpa zip.
5. APK yang dihasilkan bertipe debug (ditandatangani kunci debug), cukup untuk dipasang di HP sendiri.

## Membuat APK lewat Android Studio
1. Buka Android Studio (versi Koala atau lebih baru), pilih File > Open, lalu pilih folder ini.
2. Tunggu Gradle sync selesai. Kalau diminta, setujui saran upgrade Android Gradle Plugin.
3. Build > Build Bundle(s) / APK(s) > Build APK(s).
4. File APK ada di `app/build/outputs/apk/debug/app-debug.apk`. Salin ke HP dan pasang (izinkan pasang dari sumber ini).

Android Studio memakai Gradle dari `gradle/wrapper/gradle-wrapper.properties`, jadi `gradle-wrapper.jar` tidak diperlukan.
Untuk build lewat terminal jalankan dulu `gradle wrapper` satu kali.

## Cara pakai
1. Di Arduino IDE: Sketch > Export Compiled Binary. Ambil file berakhiran `.ino.bin`.
2. Buka aplikasi, izinkan Bluetooth, tekan Hubungkan (DSP akan dicari otomatis).
3. Pilih file `.ino.bin`, cek sandi (bawaan `ltronik`, sama dengan `OTA_PASS` di firmware), tekan Mulai update.
4. Biarkan layar menyala sampai DSP restart sendiri.

Android 11 ke bawah: nyalakan Lokasi agar pemindaian Bluetooth jalan.

## Protokol (karakteristik 6e400004-b5a3-f393-e0a9-e50e24dcca9e)
- HP ke DSP (write with response): `B` + ukuran (4 byte LE) + CRC32 (4 byte LE) + sandi, lalu `D` + data (maks 200 byte) berulang, lalu `E`. `X` untuk batal.
- DSP ke HP (notify, 1 byte): 0x01 siap, 0x02 selesai, 0x81 sandi salah, 0x82 gagal mulai, 0x83 gagal tulis, 0x84 CRC salah, 0x85 gagal pasang, 0x86 waktu habis, 0x87 urutan salah.
