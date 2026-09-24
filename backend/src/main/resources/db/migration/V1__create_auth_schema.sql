-- Phase 2: authentication and authorization schema.
-- All timestamps are stored in UTC (DATETIME(6), written by the application with hibernate.jdbc.time_zone=UTC).

CREATE TABLE roles (
    id   INT         NOT NULL AUTO_INCREMENT,
    name VARCHAR(20) NOT NULL,
    CONSTRAINT pk_roles PRIMARY KEY (id),
    CONSTRAINT uk_roles_name UNIQUE (name),
    CONSTRAINT ck_roles_name CHECK (name IN ('ADMIN', 'MANAGER', 'USER'))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE users (
    id            BIGINT       NOT NULL AUTO_INCREMENT,
    -- Stored trimmed and lower-cased by the application; uniqueness is also case-insensitive via the collation.
    email         VARCHAR(254) NOT NULL,
    -- BCrypt hash (60 chars today); wider to allow a future algorithm-prefixed format.
    password_hash VARCHAR(100) NOT NULL,
    first_name    VARCHAR(100) NOT NULL,
    last_name     VARCHAR(100) NOT NULL,
    enabled       BIT(1)       NOT NULL DEFAULT b'1',
    created_at    DATETIME(6)  NOT NULL,
    updated_at    DATETIME(6)  NOT NULL,
    CONSTRAINT pk_users PRIMARY KEY (id),
    CONSTRAINT uk_users_email UNIQUE (email)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE user_roles (
    user_id BIGINT NOT NULL,
    role_id INT    NOT NULL,
    CONSTRAINT pk_user_roles PRIMARY KEY (user_id, role_id),
    CONSTRAINT fk_user_roles_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT fk_user_roles_role FOREIGN KEY (role_id) REFERENCES roles (id) ON DELETE RESTRICT
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE INDEX ix_user_roles_role_id ON user_roles (role_id);

CREATE TABLE refresh_tokens (
    id                 BIGINT      NOT NULL AUTO_INCREMENT,
    user_id            BIGINT      NOT NULL,
    -- SHA-256 (hex) of the raw token. The raw token is never stored.
    token_hash         VARCHAR(64) NOT NULL,
    -- All tokens produced by rotation from one login share a family; reuse of a rotated token revokes the family.
    family_id          VARCHAR(36) NOT NULL,
    created_at         DATETIME(6) NOT NULL,
    expires_at         DATETIME(6) NOT NULL,
    -- Absolute end of the login session; rotation can never extend a family past this instant.
    session_expires_at DATETIME(6) NOT NULL,
    revoked_at         DATETIME(6) NULL,
    revoked_reason     VARCHAR(20) NULL,
    CONSTRAINT pk_refresh_tokens PRIMARY KEY (id),
    CONSTRAINT uk_refresh_tokens_token_hash UNIQUE (token_hash),
    CONSTRAINT fk_refresh_tokens_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT ck_refresh_tokens_reason CHECK (revoked_reason IS NULL
        OR revoked_reason IN ('ROTATED', 'LOGOUT', 'REUSE_DETECTED', 'USER_DISABLED'))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE INDEX ix_refresh_tokens_user_id ON refresh_tokens (user_id);
CREATE INDEX ix_refresh_tokens_family_id ON refresh_tokens (family_id);
CREATE INDEX ix_refresh_tokens_expires_at ON refresh_tokens (expires_at);
