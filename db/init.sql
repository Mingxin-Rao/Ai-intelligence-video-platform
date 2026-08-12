-- ==========================================================================
-- DoVideo AI — Database initialization script
-- --------------------------------------------------------------------------
-- Note: the official MySQL image auto-runs .sql files mounted under
--       /docker-entrypoint-initdb.d/ ONLY on the first (fresh) initialization.
-- If the data directory already exists, run this script manually, e.g.:
--   docker exec -i dovideo-mysql mysql -uroot -proot < db/init.sql
-- ==========================================================================

CREATE DATABASE IF NOT EXISTS media_db DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE media_db;

-- Users table
CREATE TABLE IF NOT EXISTS users (
    id          BIGINT       NOT NULL AUTO_INCREMENT COMMENT 'Primary key',
    email       VARCHAR(255) NOT NULL COMMENT 'Login email (unique)',
    password    VARCHAR(255) NOT NULL COMMENT 'Password',
    nickname    VARCHAR(100) DEFAULT NULL COMMENT 'Display name',
    avatar      VARCHAR(500) DEFAULT NULL COMMENT 'Avatar URL',
    role        VARCHAR(50)  DEFAULT 'USER' COMMENT 'Role',
    create_time DATETIME     DEFAULT CURRENT_TIMESTAMP COMMENT 'Created at',
    PRIMARY KEY (id),
    UNIQUE KEY uk_email (email)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = 'User accounts';

-- Media files table
CREATE TABLE IF NOT EXISTS media_files (
    id              BIGINT        NOT NULL AUTO_INCREMENT COMMENT 'Primary key',
    user_id         BIGINT        DEFAULT NULL COMMENT 'Owner user id',
    filename        VARCHAR(500)  DEFAULT NULL COMMENT 'File name',
    status          VARCHAR(50)   DEFAULT NULL COMMENT 'Status (COMPLETED / PROCESSING ...)',
    file_path       VARCHAR(1000) DEFAULT NULL COMMENT 'File URL/path',
    ai_summary      LONGTEXT      DEFAULT NULL COMMENT 'AI summary',
    transcript_text LONGTEXT      DEFAULT NULL COMMENT 'Transcript text',
    cover_url       VARCHAR(1000) DEFAULT NULL COMMENT 'Cover URL',
    video_md5       VARCHAR(32)   DEFAULT NULL COMMENT 'Content MD5 fingerprint (for dedup)',
    upload_time     DATETIME      DEFAULT CURRENT_TIMESTAMP COMMENT 'Uploaded at',
    PRIMARY KEY (id),
    KEY idx_user_id (user_id),
    KEY idx_user_md5 (user_id, video_md5)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = 'Uploaded media files';
