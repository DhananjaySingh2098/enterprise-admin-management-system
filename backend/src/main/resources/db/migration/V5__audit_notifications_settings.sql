-- Phase 5: audit trail, in-app notifications, personal preferences and organization settings. Purely additive.

-- Append-only audit trail. The application never updates or deletes rows (immutable entity, insert-only
-- repository). Deployments can additionally revoke UPDATE/DELETE on this table from the application user.
-- actor_user_id deliberately has no foreign key: the trail must stay intact whatever happens to accounts, and
-- actor_email / target_label are snapshots taken when the event happened.
CREATE TABLE audit_logs (
    id            BIGINT        NOT NULL AUTO_INCREMENT,
    created_at    DATETIME(6)   NOT NULL,
    actor_user_id BIGINT        NULL,
    actor_email   VARCHAR(254)  NULL,
    action        VARCHAR(60)   NOT NULL,
    entity_type   VARCHAR(40)   NULL,
    entity_id     VARCHAR(64)   NULL,
    target_label  VARCHAR(200)  NULL,
    outcome       VARCHAR(20)   NOT NULL,
    -- Small JSON object of redacted, non-secret facts (changed field names, from/to statuses, reasons).
    details       VARCHAR(2000) NULL,
    ip_address    VARCHAR(45)   NULL,
    request_id    VARCHAR(64)   NULL,
    CONSTRAINT pk_audit_logs PRIMARY KEY (id),
    CONSTRAINT ck_audit_logs_outcome CHECK (outcome IN ('SUCCESS', 'FAILURE'))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE INDEX ix_audit_logs_created_at ON audit_logs (created_at);
CREATE INDEX ix_audit_logs_action_created ON audit_logs (action, created_at);
CREATE INDEX ix_audit_logs_entity ON audit_logs (entity_type, entity_id);
CREATE INDEX ix_audit_logs_actor ON audit_logs (actor_user_id, created_at);
CREATE INDEX ix_audit_logs_outcome ON audit_logs (outcome);
CREATE INDEX ix_audit_logs_request_id ON audit_logs (request_id);

-- In-app notifications. Title and message are plain text composed by the server from fixed templates.
CREATE TABLE notifications (
    id                  BIGINT       NOT NULL AUTO_INCREMENT,
    user_id             BIGINT       NOT NULL,
    type                VARCHAR(40)  NOT NULL,
    title               VARCHAR(120) NOT NULL,
    message             VARCHAR(500) NOT NULL,
    related_entity_type VARCHAR(40)  NULL,
    related_entity_id   VARCHAR(64)  NULL,
    read_at             DATETIME(6)  NULL,
    created_at          DATETIME(6)  NOT NULL,
    CONSTRAINT pk_notifications PRIMARY KEY (id),
    CONSTRAINT fk_notifications_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE INDEX ix_notifications_user_created ON notifications (user_id, created_at);
CREATE INDEX ix_notifications_user_unread ON notifications (user_id, read_at);

-- One row per user, created on the first save; absent = defaults.
CREATE TABLE user_preferences (
    user_id      BIGINT      NOT NULL,
    theme_mode   VARCHAR(10) NOT NULL,
    theme_preset VARCHAR(12) NOT NULL,
    density      VARCHAR(12) NOT NULL,
    updated_at   DATETIME(6) NOT NULL,
    CONSTRAINT pk_user_preferences PRIMARY KEY (user_id),
    CONSTRAINT fk_user_preferences_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT ck_user_preferences_mode CHECK (theme_mode IN ('SYSTEM', 'LIGHT', 'DARK')),
    CONSTRAINT ck_user_preferences_preset CHECK (theme_preset IN ('AURORA', 'OBSIDIAN', 'PEARL', 'MIDNIGHT', 'EMERALD')),
    CONSTRAINT ck_user_preferences_density CHECK (density IN ('COMFORTABLE', 'COMPACT'))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- Exactly one row (id = 1), seeded with the values the application used until now.
CREATE TABLE organization_settings (
    id                      INT          NOT NULL,
    organization_name       VARCHAR(120) NOT NULL,
    recent_hire_window_days INT          NOT NULL,
    version                 BIGINT       NOT NULL DEFAULT 0,
    updated_at              DATETIME(6)  NOT NULL,
    updated_by_user_id      BIGINT       NULL,
    CONSTRAINT pk_organization_settings PRIMARY KEY (id),
    CONSTRAINT ck_organization_settings_singleton CHECK (id = 1),
    CONSTRAINT ck_organization_settings_window CHECK (recent_hire_window_days BETWEEN 1 AND 365)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

INSERT INTO organization_settings (id, organization_name, recent_hire_window_days, version, updated_at)
VALUES (1, 'Enterprise Admin', 30, 0, UTC_TIMESTAMP(6));
