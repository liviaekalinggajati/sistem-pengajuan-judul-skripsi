# Database MySQL untuk aplikasi pendaftaran skripsi

Aplikasi Java terhubung ke MySQL atau MariaDB yang dapat dikelola melalui phpMyAdmin. Berkas SQLite lama (`data/pendaftaran-skripsi.sqlite`) tidak dihapus atau diubah.

## Menyiapkan database

1. Jalankan MySQL/MariaDB (misalnya melalui XAMPP), lalu buka phpMyAdmin.
2. Pilih **Import**, unggah `database-mysql.sql`, lalu jalankan. Skrip membuat database `pendaftaran_skripsi` beserta tabel `admin`, `mahasiswa`, `pengajuan_skripsi`, dan `app_metadata`.
3. Tambahkan MySQL Connector/J 9.3.0 ke classpath proyek. Pada konfigurasi proyek ini, simpan `mysql-connector-j-9.3.0.jar` di:
   `%USERPROFILE%\.m2\repository\com\mysql\mysql-connector-j\9.3.0\`
4. Jalankan aplikasi dari folder proyek. Nilai koneksi default adalah:
   - URL: `jdbc:mysql://localhost:3306/pendaftaran_skripsi?serverTimezone=UTC&allowPublicKeyRetrieval=true&sslMode=DISABLED`
   - Username: `root`
   - Password: kosong

Jika konfigurasi MySQL berbeda, atur environment variable `DB_URL`, `DB_USER`, dan `DB_PASSWORD` sebelum menjalankan aplikasi. Contoh PowerShell:

```powershell
$env:DB_URL = "jdbc:mysql://localhost:3306/pendaftaran_skripsi?serverTimezone=UTC&allowPublicKeyRetrieval=true&sslMode=DISABLED"
$env:DB_USER = "root"
$env:DB_PASSWORD = "password-mysql-anda"
```

Jalankan kembali VS Code setelah mengubah classpath agar Java Language Server memuat Connector/J.

## Akun demo

Saat aplikasi dimulai, profil dummy `DEMO-SI-001` sampai `DEMO-SI-020` dari `data-mahasiswa.dat` disiapkan di tabel `mahasiswa`. Akun yang belum ada ditambahkan dengan password sementara `Mahasiswa123`. Password disimpan sebagai hash PBKDF2-HMAC-SHA256 bersalt.

Tabel `admin` dibuat kosong oleh skrip. Jika ingin menggunakan login admin yang sudah ada, salin data akun admin ke tabel ini; kolom `password_hash` menyimpan hash PBKDF2 yang sama, bukan password teks biasa.

Skrip SQL hanya membuat struktur database baru dan tidak menghapus atau menyalin data SQLite lama. Buat cadangan dan pindahkan data lama secara terpisah jika ingin mempertahankannya di MySQL.
