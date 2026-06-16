CREATE TABLE IF NOT EXISTS user_platform_quotas (
    id                   BIGSERIAL PRIMARY KEY,
    user_id              BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    platform             VARCHAR(32) NOT NULL CHECK (platform IN ('anthropic', 'openai', 'gemini', 'antigravity')),
    daily_limit_usd      DECIMAL(20,10),
    weekly_limit_usd     DECIMAL(20,10),
    monthly_limit_usd    DECIMAL(20,10),
    daily_usage_usd      DECIMAL(20,10) NOT NULL DEFAULT 0,
    weekly_usage_usd     DECIMAL(20,10) NOT NULL DEFAULT 0,
    monthly_usage_usd    DECIMAL(20,10) NOT NULL DEFAULT 0,
    daily_window_start   TIMESTAMPTZ,
    weekly_window_start  TIMESTAMPTZ,
    monthly_window_start TIMESTAMPTZ,
    created_at           TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at           TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    deleted_at           TIMESTAMPTZ
);

CREATE UNIQUE INDEX IF NOT EXISTS userplatformquota_user_id_platform_uq
    ON user_platform_quotas (user_id, platform)
    WHERE deleted_at IS NULL;

CREATE INDEX IF NOT EXISTS userplatformquota_user_id
    ON user_platform_quotas (user_id);
