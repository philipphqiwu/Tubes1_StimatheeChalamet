# Tubes 1 IF2211 - Battlecode 2025

README ini menjelaskan tata cara penggunaan proyek, ringkasan heuristik greedy tiap bot,
analisis efisiensi/efektivitas singkat, requirement, langkah build, dan identitas pembuat.

## Daftar Bot

- Main bot: `himothee2`
- Alternatif 1: `thee`
- Alternatif 2: `chamalet`

## Penjelasan Singkat Algoritma Greedy Tiap Bot

### 1) `himothee2` (Main Bot)

Pendekatan utama: hierarchical state-based greedy dengan komunikasi aktif antar unit.

- Communication:
    - Broadcast simetri (`SYM_UPDATE`) untuk proyeksi posisi lawan.
    - Broadcast `ENEMY_TOWER` untuk sinkron target serang.
    - Broadcast `SAVE_CHIPS` untuk koordinasi penahanan chip saat ada ruin prioritas.
- Pathfinding:
    - Kombinasi `bug0` (gerak cepat) dan `bug2` (tracing obstacle).
    - Tile scoring dipakai agar gerak tetap progresif dan tidak mudah terjebak.
- Micro:
    - Retreat threshold adaptif per unit (berbasis ID/unit type).
    - Mopper bisa membatalkan retreat saat peluang combat/lifesteal menguntungkan.
- Macro:
    - Rotasi unit berbasis skala map (`50*scale`, `100*scale`, `200*scale`).
    - Prioritas ekonomi: ekspansi tower lebih dulu, upgrade saat ekonomi matang.
- Soldier:
    - Role dipisah (SRP specialist dan regular soldier).
    - SRP specialist fokus pola SRP 5x5 untuk dukungan ekonomi late game.
- Mopper:
    - Prioritas combat tinggi (swing multi-hit atau target paint terbesar).
    - Dapat transfer paint dan masuk mode bantu-bangun jika ruin ally kotor.
- Splasher:
    - Push mendalam pakai intel simetri + memori/broadcast tower lawan.
    - Splash saat skor area tinggi, lalu kite jika terlalu dekat tower musuh.
- Tower:
    - Menjaga informasi global tim, spawn adaptif, upgrade selektif.

### 2) `thee` (Alternatif 1)

Pendekatan utama: greedy sederhana berbasis delegasi tugas (Soldier menandai, Mopper mengeksekusi).

- Communication:
    - Mopper berperan sebagai messenger ke menara (`SAVING`).
    - Pelaporan jumlah musuh dilakukan periodik dalam jangkauan lokal.
- Pathfinding:
    - `bug2` dengan state minimalis; saat target berubah, tracing lama direset.
- Micro:
    - Soldier menggunakan mark `ALLY_SECONDARY` untuk mengunci titik pembersihan.
    - Mopper mengejar mark, bersihkan area, lalu remove mark saat aman.
- Macro:
    - Tower dapat menahan produksi (`skip turns`) saat menerima sinyal saving.
    - Produksi unit dan upgrade dijalankan konservatif.
- Soldier:
    - Fokus ruin kosong terdekat, refill ketat, dan penandaan area kotor.
- Mopper:
    - Messenger + cleaner utama area bertanda.
- Splasher:
    - Bukan fokus utama varian ini.
- Tower:
    - Menjaga buffer chips agar completion ruin lebih stabil.

### 3) `chamalet` (Alternatif 2)

Pendekatan utama: greedy modular dengan micro agresif.

- Communication:
    - Soldier mengirim `MSG_SAVE_CHIPS` ke tower terdekat saat ada ruin buildable.
- Pathfinding:
    - `bug2` sebagai backbone, dibantu scoring lokal saat bergerak bebas.
    - Eksplorasi cenderung menyebar dari kepadatan ally.
- Micro:
    - Kiting proaktif terhadap tower lawan.
    - Targeting unit HP terendah untuk eliminasi cepat.
- Macro:
    - Rotasi spawn: early `S-S-M`, mid-late `S-S-Sp-Sp-M-M-M`.
    - Chip floor dijaga agar completion tower tidak tertunda.
- Soldier:
    - Alur prioritas tegas: refill -> klaim ruin -> micro tower -> eksplorasi/paint.
- Mopper:
    - Fokus pembersihan ruin, swing oportunistik, dan transfer paint.
- Splasher:
    - Scoring area tembak dengan blacklist pattern ruin ally.
- Tower:
    - Menjadi koordinator buffering chips dan finishing musuh HP rendah.

## Analisis Efisiensi dan Efektivitas

### Himothee

- Efisiensi:
    - Komputasi lebih berat (simetri, SRP, tracking tower), tetapi manajemen resource in-game sangat optimal.
    - Retreat dinamis dan lifesteal mengurangi downtime refill.
- Efektivitas:
    - Sangat kuat untuk tekanan ofensif berlapis.
    - Kombinasi bug0/bug2, memori target, dan deduksi simetri membuat invasi lebih terarah.

### Chamalet

- Efisiensi:
    - Ringan secara komputasi karena logika scoring lokal sederhana.
    - Namun biaya turn in-game bisa membesar jika unit terlalu sering bolak-balik refill.
- Efektivitas:
    - Kuat untuk micro skirmish awal.
    - Kiting Soldier, mop swing Mopper, dan splash blacklist ally-pattern menjaga kestabilan formasi.

### Thee

- Efisiensi:
    - Cukup efisien dengan delegasi tugas yang jelas (Soldier menandai, Mopper mengeksekusi).
    - Mekanisme skip turns menekan pemborosan chips saat momen build penting.
- Efektivitas:
    - Stabil untuk ekspansi infrastruktur.
    - Potensi bottleneck: beban messenger terlalu terpusat pada unit Mopper.


## Requirement dan Instalasi

### Requirement

- Java Development Kit (JDK) 17 atau versi yang kompatibel dengan Battlecode 2025.
- Gradle Wrapper (sudah tersedia di repo: `gradlew` dan `gradlew.bat`).
- Koneksi internet saat pertama kali download dependency Gradle/client.

### Instalasi

1. Clone repository ini.
2. Pastikan JDK terpasang dan `java -version` berjalan.
3. Tidak perlu install Gradle global, karena proyek memakai Gradle Wrapper.

## Cara Compile / Build / Run

Jalankan perintah dari root project.

### Windows (PowerShell / CMD)

```powershell
.\gradlew.bat build
.\gradlew.bat run
.\gradlew.bat zipForSubmit
.\gradlew.bat tasks
```

### Linux / macOS

```bash
./gradlew build
./gradlew run
./gradlew zipForSubmit
./gradlew tasks
```

Keterangan:

- `build`: compile semua source code bot.
- `run`: menjalankan match sesuai konfigurasi di `gradle.properties`.
- `zipForSubmit`: membuat file zip siap submit.
- `tasks`: melihat daftar task Gradle yang tersedia.

## Struktur Folder Penting

- `src/himothee2`: bot utama.
- `src/thee`: bot alternatif 1.
- `src/chamalet`: bot alternatif 2.
- `matches/`: output replay hasil pertandingan.
- `gradle.properties`: konfigurasi match dan runtime.

## Author (Identitas Pembuat)

- Philipp Hamara - 13524101
- Emilio Justin - 13524043
- Vincent Rionarlie - 13524031

## Link Youtube

https://www.youtube.com/@vinr6074