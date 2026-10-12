# Anikoto Watch

Aplikasi Android pribadi untuk membuka situs anime tanpa browser, dengan adblock bawaan.

## Unduh APK

Buka halaman **[Releases](../../releases/latest)**, unduh `AniView.apk`, lalu install di HP
(izinkan "Install unknown apps" kalau diminta).

## Fitur

- Langsung membuka situs, layar penuh tanpa address bar
- Blokir iklan berbasis domain (daftar [StevenBlack](https://github.com/StevenBlack/hosts) + daftar bawaan)
- Pop-up, tab baru, dan redirect ke situs lain diblokir
- Video fullscreen otomatis landscape
- Layar tidak mati selama aplikasi terbuka
- Tombol kembali: halaman sebelumnya, tekan 2x untuk keluar

## Ganti alamat situs

Kalau situsnya pindah domain, edit `start_url` di
[`app/src/main/res/values/strings.xml`](app/src/main/res/values/strings.xml) lalu commit.
GitHub Actions akan membuat APK baru secara otomatis di halaman Releases.

## Build

Setiap push ke `main` menjalankan workflow `.github/workflows/build.yml`, yang membuat APK
dan menerbitkannya di Releases. Bisa juga dijalankan manual dari tab **Actions → Build APK → Run workflow**.
