-- Phase 3: supports user administration and self-service password changes. Non-destructive.

-- Sorting/searching the user list by name.
CREATE INDEX ix_users_name ON users (last_name, first_name);
CREATE INDEX ix_users_created_at ON users (created_at);

-- Allow the new revocation reason used when a user changes their password.
ALTER TABLE refresh_tokens DROP CHECK ck_refresh_tokens_reason;
ALTER TABLE refresh_tokens ADD CONSTRAINT ck_refresh_tokens_reason CHECK (revoked_reason IS NULL
    OR revoked_reason IN ('ROTATED', 'LOGOUT', 'REUSE_DETECTED', 'USER_DISABLED', 'PASSWORD_CHANGED'));
