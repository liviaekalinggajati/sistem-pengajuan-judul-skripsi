CREATE DATABASE IF NOT EXISTS pendaftaran_skripsi
    CHARACTER SET utf8mb4
    COLLATE utf8mb4_unicode_ci;

USE pendaftaran_skripsi;

CREATE TABLE IF NOT EXISTS admin (
    username VARCHAR(100) NOT NULL,
    password_hash VARCHAR(255) NOT NULL,
    PRIMARY KEY (username)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS mahasiswa (
    nim VARCHAR(32) NOT NULL,
    nama VARCHAR(255) NOT NULL,
    program_studi VARCHAR(255) NOT NULL,
    semester INT NOT NULL,
    password_hash VARCHAR(255) NOT NULL,
    email VARCHAR(255) NOT NULL DEFAULT '',
    PRIMARY KEY (nim)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS data_mahasiswa (
    nim VARCHAR(32) NOT NULL,
    nama VARCHAR(255) NOT NULL,
    program_studi VARCHAR(255) NOT NULL,
    semester INT NOT NULL,
    nilai DOUBLE NULL,
    PRIMARY KEY (nim)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS pengajuan_skripsi (
    id CHAR(36) NOT NULL,
    nim VARCHAR(32) NOT NULL,
    nama VARCHAR(255) NOT NULL,
    program_studi VARCHAR(255) NOT NULL,
    email VARCHAR(255) NOT NULL DEFAULT '',
    semester VARCHAR(32) NOT NULL,
    judul TEXT NOT NULL,
    deskripsi TEXT NOT NULL,
    bidang VARCHAR(255) NOT NULL,
    tanggal VARCHAR(64) NOT NULL,
    status VARCHAR(32) NOT NULL,
    catatan VARCHAR(2000) NOT NULL DEFAULT '',
    similarity_score DOUBLE NULL,
    similarity_compared_title TEXT NULL,
    similarity_compared_nim VARCHAR(32) NULL,
    PRIMARY KEY (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS riwayat_status_pengajuan (
    history_id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    application_id CHAR(36) NOT NULL,
    nim VARCHAR(32) NOT NULL,
    previous_status VARCHAR(32) NOT NULL,
    new_status VARCHAR(32) NOT NULL,
    changed_at VARCHAR(64) NOT NULL,
    note VARCHAR(2000) NOT NULL DEFAULT '',
    PRIMARY KEY (history_id),
    INDEX idx_history_application (application_id),
    CONSTRAINT fk_history_application
        FOREIGN KEY (application_id) REFERENCES pengajuan_skripsi (id)
        ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS app_metadata (
    `key` VARCHAR(255) NOT NULL,
    `value` TEXT NOT NULL,
    PRIMARY KEY (`key`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
