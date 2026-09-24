-- Phase 6: refresh-token successor tracking (aborted-refresh recovery) and a shared rate-limit store. Additive.

-- Each rotated token points at the token that replaced it. If a client never received that replacement (its
-- request was aborted, e.g. by a page reload) the replacement is still unused, which lets the server re-issue once
-- within the grace period instead of treating the retry as token theft.
ALTER TABLE refresh_tokens ADD COLUMN replaced_by_id BIGINT NULL;
ALTER TABLE refresh_tokens ADD CONSTRAINT fk_refresh_tokens_replaced_by
    FOREIGN KEY (replaced_by_id) REFERENCES refresh_tokens (id) ON DELETE SET NULL;

-- SUPERSEDED: an unused replacement that was re-issued after an aborted refresh.
ALTER TABLE refresh_tokens DROP CHECK ck_refresh_tokens_reason;
ALTER TABLE refresh_tokens ADD CONSTRAINT ck_refresh_tokens_reason CHECK (revoked_reason IS NULL
    OR revoked_reason IN ('ROTATED', 'LOGOUT', 'REUSE_DETECTED', 'USER_DISABLED', 'PASSWORD_CHANGED', 'SUPERSEDED'));

-- Fixed-window request counters shared by every application instance (see RateLimitService).
-- bucket_key is a policy name plus a SHA-256 of the subject (IP, user id or account email) — never raw values.
CREATE TABLE rate_limit_buckets (
    bucket_key   VARCHAR(120) NOT NULL,
    window_start DATETIME(6)  NOT NULL,
    hits         INT          NOT NULL,
    expires_at   DATETIME(6)  NOT NULL,
    CONSTRAINT pk_rate_limit_buckets PRIMARY KEY (bucket_key, window_start)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE INDEX ix_rate_limit_buckets_expires_at ON rate_limit_buckets (expires_at);
