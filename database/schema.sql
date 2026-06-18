-- ============================================================
-- SNT Sing - Esquema do Banco de Dados (MySQL)
-- Versão: 1.0
-- ============================================================

CREATE DATABASE IF NOT EXISTS snt_sing
  CHARACTER SET utf8mb4
  COLLATE utf8mb4_unicode_ci;

USE snt_sing;

-- -----------------------------------------------------------
-- Tabela: users (usuários do aplicativo)
-- -----------------------------------------------------------
CREATE TABLE IF NOT EXISTS users (
    id          INT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
    name        VARCHAR(100)    NOT NULL,
    email       VARCHAR(255)    NOT NULL UNIQUE,
    password    VARCHAR(255)    NOT NULL,               -- hash bcrypt
    avatar_url  VARCHAR(500)    DEFAULT NULL,            -- URL da foto do perfil
    created_at  TIMESTAMP       DEFAULT CURRENT_TIMESTAMP,
    updated_at  TIMESTAMP       DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_users_email (email)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- -----------------------------------------------------------
-- Tabela: artists (artistas / bandas)
-- -----------------------------------------------------------
CREATE TABLE IF NOT EXISTS artists (
    id          INT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
    name        VARCHAR(200)    NOT NULL,
    bio         TEXT            DEFAULT NULL,            -- biografia curta
    image_url   VARCHAR(500)    DEFAULT NULL,            -- URL da foto do artista
    created_at  TIMESTAMP       DEFAULT CURRENT_TIMESTAMP,
    updated_at  TIMESTAMP       DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_artists_name (name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- -----------------------------------------------------------
-- Tabela: songs (músicas do catálogo)
-- -----------------------------------------------------------
CREATE TABLE IF NOT EXISTS songs (
    id              INT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
    artist_id       INT UNSIGNED    NOT NULL,
    title           VARCHAR(300)    NOT NULL,
    genre           VARCHAR(100)    DEFAULT NULL,           -- ex: "Pop", "Sertanejo", "Rock"
    duration_secs   INT UNSIGNED    DEFAULT 0,              -- duração em segundos
    instrumental_url VARCHAR(500)   NOT NULL,                -- URL do vídeo instrumental (YouTube / Vimeo)
    cover_url       VARCHAR(500)    DEFAULT NULL,            -- URL da capa/thumbnail da música
    lyrics          TEXT            DEFAULT NULL,            -- letra da música (opcional, para sincronia futura)
    is_active       TINYINT(1)      DEFAULT 1,               -- 1 = disponível, 0 = oculta
    created_at      TIMESTAMP       DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMP       DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,

    FOREIGN KEY (artist_id) REFERENCES artists(id) ON DELETE CASCADE,
    INDEX idx_songs_active (is_active, title),
    INDEX idx_songs_artist (artist_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- -----------------------------------------------------------
-- Dados de exemplo (opcional)
-- -----------------------------------------------------------
INSERT INTO artists (name, bio) VALUES
('Artista Exemplo', 'Banda/artista de exemplo para testes');

INSERT INTO songs (artist_id, title, genre, duration_secs, instrumental_url, cover_url, lyrics) VALUES
(1, 'Música Exemplo 1', 'Pop', 240,
 'https://www.youtube.com/watch?v=EXEMPLO1',
 'https://i.imgur.com/capa1.jpg',
 'Letra da música exemplo 1...'),
(1, 'Música Exemplo 2', 'Sertanejo', 200,
 'https://www.youtube.com/watch?v=EXEMPLO2',
 'https://i.imgur.com/capa2.jpg',
 'Letra da música exemplo 2...');
