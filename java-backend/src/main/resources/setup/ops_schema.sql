-- Ops schema installation script.
-- Ensures the current ops tables, indexes, and seed data are available.

SET LOCAL lock_timeout = '5s';
SET LOCAL statement_timeout = '10min';

CREATE TABLE IF NOT EXISTS ops_error_logs (
    id BIGSERIAL PRIMARY KEY,
    request_id VARCHAR(64),
    client_request_id VARCHAR(64),
    user_id BIGINT,
    api_key_id BIGINT,
    account_id BIGINT,
    group_id BIGINT,
    client_ip inet,
    platform VARCHAR(32),
    model VARCHAR(100),
    request_path VARCHAR(256),
    stream BOOLEAN NOT NULL DEFAULT false,
    user_agent TEXT,
    error_phase VARCHAR(32) NOT NULL,
    error_type VARCHAR(64) NOT NULL,
    severity VARCHAR(8) NOT NULL DEFAULT 'P2',
    status_code INT,
    is_business_limited BOOLEAN NOT NULL DEFAULT false,
    error_message TEXT,
    error_body TEXT,
    error_source VARCHAR(64),
    error_owner VARCHAR(32),
    account_status VARCHAR(50),
    upstream_status_code INT,
    upstream_error_message TEXT,
    upstream_error_detail TEXT,
    provider_error_code VARCHAR(64),
    provider_error_type VARCHAR(64),
    network_error_type VARCHAR(50),
    retry_after_seconds INT,
    duration_ms INT,
    time_to_first_token_ms BIGINT,
    auth_latency_ms BIGINT,
    routing_latency_ms BIGINT,
    upstream_latency_ms BIGINT,
    response_latency_ms BIGINT,
    request_body JSONB,
    request_headers JSONB,
    request_body_truncated BOOLEAN NOT NULL DEFAULT false,
    request_body_bytes INT,
    is_retryable BOOLEAN NOT NULL DEFAULT false,
    retry_count INT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    upstream_errors JSONB,
    is_count_tokens BOOLEAN NOT NULL DEFAULT FALSE,
    resolved BOOLEAN NOT NULL DEFAULT false,
    resolved_at TIMESTAMPTZ,
    resolved_by_user_id BIGINT,
    resolved_retry_id BIGINT,
    inbound_endpoint VARCHAR(256),
    upstream_endpoint VARCHAR(256),
    requested_model VARCHAR(100),
    upstream_model VARCHAR(100),
    request_type SMALLINT
);

COMMENT ON TABLE ops_error_logs IS 'Ops error logs. Stores sanitized error details and request_body for retries (errors only).';
COMMENT ON COLUMN ops_error_logs.upstream_errors IS
    'Sanitized upstream error events list (JSON array), correlated per gateway request (request_id/client_request_id); used for per-request upstream debugging.';
COMMENT ON COLUMN ops_error_logs.is_count_tokens IS 'Whether the error came from a count_tokens request.';
COMMENT ON COLUMN ops_error_logs.inbound_endpoint IS 'Normalized client-facing API endpoint path, e.g. /v1/chat/completions. Populated from InboundEndpointMiddleware.';
COMMENT ON COLUMN ops_error_logs.upstream_endpoint IS 'Normalized upstream endpoint path derived from platform, e.g. /v1/responses.';
COMMENT ON COLUMN ops_error_logs.requested_model IS 'Client-requested model name before mapping (raw from request body).';
COMMENT ON COLUMN ops_error_logs.upstream_model IS 'Actual model sent to upstream provider after mapping. NULL means no mapping applied.';
COMMENT ON COLUMN ops_error_logs.request_type IS 'Request type enum: 0=unknown, 1=sync, 2=stream, 3=ws_v2. Matches usage_logs.request_type semantics.';

CREATE TABLE IF NOT EXISTS ops_retry_attempts (
    id BIGSERIAL PRIMARY KEY,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    requested_by_user_id BIGINT,
    source_error_id BIGINT,
    mode VARCHAR(16) NOT NULL,
    pinned_account_id BIGINT,
    status VARCHAR(16) NOT NULL DEFAULT 'queued',
    started_at TIMESTAMPTZ,
    finished_at TIMESTAMPTZ,
    duration_ms BIGINT,
    result_request_id VARCHAR(64),
    result_error_id BIGINT,
    result_usage_request_id VARCHAR(64),
    error_message TEXT,
    success BOOLEAN,
    http_status_code INT,
    upstream_request_id VARCHAR(128),
    used_account_id BIGINT,
    response_preview TEXT,
    response_truncated BOOLEAN NOT NULL DEFAULT false
);

COMMENT ON TABLE ops_retry_attempts IS 'Audit table for ops retries (client retry / pinned upstream retry).';

CREATE TABLE IF NOT EXISTS ops_system_metrics (
    id BIGSERIAL PRIMARY KEY,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    window_minutes INT NOT NULL DEFAULT 1,
    platform VARCHAR(32),
    group_id BIGINT,
    success_count BIGINT NOT NULL DEFAULT 0,
    error_count_total BIGINT NOT NULL DEFAULT 0,
    business_limited_count BIGINT NOT NULL DEFAULT 0,
    error_count_sla BIGINT NOT NULL DEFAULT 0,
    upstream_error_count_excl_429_529 BIGINT NOT NULL DEFAULT 0,
    upstream_429_count BIGINT NOT NULL DEFAULT 0,
    upstream_529_count BIGINT NOT NULL DEFAULT 0,
    token_consumed BIGINT NOT NULL DEFAULT 0,
    qps DOUBLE PRECISION,
    tps DOUBLE PRECISION,
    duration_p50_ms INT,
    duration_p90_ms INT,
    duration_p95_ms INT,
    duration_p99_ms INT,
    duration_avg_ms DOUBLE PRECISION,
    duration_max_ms INT,
    ttft_p50_ms INT,
    ttft_p90_ms INT,
    ttft_p95_ms INT,
    ttft_p99_ms INT,
    ttft_avg_ms DOUBLE PRECISION,
    ttft_max_ms INT,
    cpu_usage_percent DOUBLE PRECISION,
    memory_used_mb BIGINT,
    memory_total_mb BIGINT,
    memory_usage_percent DOUBLE PRECISION,
    db_ok BOOLEAN,
    redis_ok BOOLEAN,
    db_conn_active INT,
    db_conn_idle INT,
    db_conn_waiting INT,
    thread_count INT,
    concurrency_queue_depth INT,
    redis_conn_total INT,
    redis_conn_idle INT,
    account_switch_count BIGINT NOT NULL DEFAULT 0
);

COMMENT ON TABLE ops_system_metrics IS 'Ops system/request metrics snapshots. Used for dashboard overview and realtime rates.';
COMMENT ON COLUMN ops_system_metrics.redis_conn_total IS 'Redis pool total connections.';
COMMENT ON COLUMN ops_system_metrics.redis_conn_idle IS 'Redis pool idle connections.';

CREATE TABLE IF NOT EXISTS ops_job_heartbeats (
    job_name VARCHAR(64) PRIMARY KEY,
    last_run_at TIMESTAMPTZ,
    last_success_at TIMESTAMPTZ,
    last_error_at TIMESTAMPTZ,
    last_error TEXT,
    last_duration_ms BIGINT,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    last_result TEXT
);

COMMENT ON TABLE ops_job_heartbeats IS 'Ops background jobs heartbeats.';
COMMENT ON COLUMN ops_job_heartbeats.last_result IS 'Last successful run result summary (human readable).';

CREATE TABLE IF NOT EXISTS ops_alert_rules (
    id BIGSERIAL PRIMARY KEY,
    name VARCHAR(128) NOT NULL,
    description TEXT,
    enabled BOOLEAN NOT NULL DEFAULT true,
    severity VARCHAR(16) NOT NULL DEFAULT 'warning',
    metric_type VARCHAR(64) NOT NULL,
    operator VARCHAR(8) NOT NULL,
    threshold DOUBLE PRECISION NOT NULL,
    window_minutes INT NOT NULL DEFAULT 5,
    sustained_minutes INT NOT NULL DEFAULT 5,
    cooldown_minutes INT NOT NULL DEFAULT 10,
    filters JSONB,
    last_triggered_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    notify_email BOOLEAN NOT NULL DEFAULT true
);

CREATE UNIQUE INDEX IF NOT EXISTS idx_ops_alert_rules_name_unique
    ON ops_alert_rules (name);

CREATE INDEX IF NOT EXISTS idx_ops_alert_rules_enabled
    ON ops_alert_rules (enabled);

CREATE TABLE IF NOT EXISTS ops_alert_events (
    id BIGSERIAL PRIMARY KEY,
    rule_id BIGINT,
    severity VARCHAR(16) NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'firing',
    title VARCHAR(200),
    description TEXT,
    metric_value DOUBLE PRECISION,
    threshold_value DOUBLE PRECISION,
    dimensions JSONB,
    fired_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    resolved_at TIMESTAMPTZ,
    email_sent BOOLEAN NOT NULL DEFAULT false,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_ops_alert_events_rule_status
    ON ops_alert_events (rule_id, status);

CREATE INDEX IF NOT EXISTS idx_ops_alert_events_fired_at
    ON ops_alert_events (fired_at DESC);

CREATE TABLE IF NOT EXISTS ops_metrics_hourly (
    id BIGSERIAL PRIMARY KEY,
    bucket_start TIMESTAMPTZ NOT NULL,
    platform VARCHAR(32),
    group_id BIGINT,
    success_count BIGINT NOT NULL DEFAULT 0,
    error_count_total BIGINT NOT NULL DEFAULT 0,
    business_limited_count BIGINT NOT NULL DEFAULT 0,
    error_count_sla BIGINT NOT NULL DEFAULT 0,
    upstream_error_count_excl_429_529 BIGINT NOT NULL DEFAULT 0,
    upstream_429_count BIGINT NOT NULL DEFAULT 0,
    upstream_529_count BIGINT NOT NULL DEFAULT 0,
    token_consumed BIGINT NOT NULL DEFAULT 0,
    duration_p50_ms INT,
    duration_p90_ms INT,
    duration_p95_ms INT,
    duration_p99_ms INT,
    ttft_p50_ms INT,
    ttft_p90_ms INT,
    ttft_p95_ms INT,
    ttft_p99_ms INT,
    computed_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    duration_avg_ms DOUBLE PRECISION,
    duration_max_ms INT,
    ttft_avg_ms DOUBLE PRECISION,
    ttft_max_ms INT
);

CREATE UNIQUE INDEX IF NOT EXISTS idx_ops_metrics_hourly_unique_dim
    ON ops_metrics_hourly (
        bucket_start,
        COALESCE(platform, ''),
        COALESCE(group_id, 0)
    );

CREATE INDEX IF NOT EXISTS idx_ops_metrics_hourly_bucket
    ON ops_metrics_hourly (bucket_start DESC);

CREATE INDEX IF NOT EXISTS idx_ops_metrics_hourly_platform_bucket
    ON ops_metrics_hourly (platform, bucket_start DESC)
    WHERE platform IS NOT NULL AND platform <> '' AND group_id IS NULL;

CREATE INDEX IF NOT EXISTS idx_ops_metrics_hourly_group_bucket
    ON ops_metrics_hourly (group_id, bucket_start DESC)
    WHERE group_id IS NOT NULL AND group_id <> 0;

COMMENT ON TABLE ops_metrics_hourly IS 'Hourly pre-aggregated ops metrics (overall/platform/group).';

CREATE TABLE IF NOT EXISTS ops_metrics_daily (
    id BIGSERIAL PRIMARY KEY,
    bucket_date DATE NOT NULL,
    platform VARCHAR(32),
    group_id BIGINT,
    success_count BIGINT NOT NULL DEFAULT 0,
    error_count_total BIGINT NOT NULL DEFAULT 0,
    business_limited_count BIGINT NOT NULL DEFAULT 0,
    error_count_sla BIGINT NOT NULL DEFAULT 0,
    upstream_error_count_excl_429_529 BIGINT NOT NULL DEFAULT 0,
    upstream_429_count BIGINT NOT NULL DEFAULT 0,
    upstream_529_count BIGINT NOT NULL DEFAULT 0,
    token_consumed BIGINT NOT NULL DEFAULT 0,
    duration_p50_ms INT,
    duration_p90_ms INT,
    duration_p95_ms INT,
    duration_p99_ms INT,
    ttft_p50_ms INT,
    ttft_p90_ms INT,
    ttft_p95_ms INT,
    ttft_p99_ms INT,
    computed_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    duration_avg_ms DOUBLE PRECISION,
    duration_max_ms INT,
    ttft_avg_ms DOUBLE PRECISION,
    ttft_max_ms INT
);

CREATE UNIQUE INDEX IF NOT EXISTS idx_ops_metrics_daily_unique_dim
    ON ops_metrics_daily (
        bucket_date,
        COALESCE(platform, ''),
        COALESCE(group_id, 0)
    );

CREATE INDEX IF NOT EXISTS idx_ops_metrics_daily_bucket
    ON ops_metrics_daily (bucket_date DESC);

CREATE INDEX IF NOT EXISTS idx_ops_metrics_daily_platform_bucket
    ON ops_metrics_daily (platform, bucket_date DESC)
    WHERE platform IS NOT NULL AND platform <> '' AND group_id IS NULL;

CREATE INDEX IF NOT EXISTS idx_ops_metrics_daily_group_bucket
    ON ops_metrics_daily (group_id, bucket_date DESC)
    WHERE group_id IS NOT NULL AND group_id <> 0;

COMMENT ON TABLE ops_metrics_daily IS 'Daily pre-aggregated ops metrics (overall/platform/group).';

CREATE INDEX IF NOT EXISTS idx_ops_error_logs_created_at
    ON ops_error_logs (created_at DESC);

CREATE INDEX IF NOT EXISTS idx_ops_error_logs_platform_time
    ON ops_error_logs (platform, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_ops_error_logs_group_time
    ON ops_error_logs (group_id, created_at DESC)
    WHERE group_id IS NOT NULL;

CREATE INDEX IF NOT EXISTS idx_ops_error_logs_account_time
    ON ops_error_logs (account_id, created_at DESC)
    WHERE account_id IS NOT NULL;

CREATE INDEX IF NOT EXISTS idx_ops_error_logs_status_time
    ON ops_error_logs (status_code, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_ops_error_logs_phase_time
    ON ops_error_logs (error_phase, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_ops_error_logs_type_time
    ON ops_error_logs (error_type, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_ops_error_logs_request_id
    ON ops_error_logs (request_id);

CREATE INDEX IF NOT EXISTS idx_ops_error_logs_client_request_id
    ON ops_error_logs (client_request_id);

CREATE INDEX IF NOT EXISTS idx_ops_error_logs_resolved_time
    ON ops_error_logs (resolved, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_ops_error_logs_unresolved_time
    ON ops_error_logs (created_at DESC)
    WHERE resolved = false;

CREATE INDEX IF NOT EXISTS idx_ops_error_logs_is_count_tokens
    ON ops_error_logs(is_count_tokens)
    WHERE is_count_tokens = TRUE;

CREATE INDEX IF NOT EXISTS idx_ops_system_metrics_created_at
    ON ops_system_metrics (created_at DESC);

CREATE INDEX IF NOT EXISTS idx_ops_system_metrics_window_time
    ON ops_system_metrics (window_minutes, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_ops_system_metrics_platform_time
    ON ops_system_metrics (platform, created_at DESC)
    WHERE platform IS NOT NULL AND platform <> '' AND group_id IS NULL;

CREATE INDEX IF NOT EXISTS idx_ops_system_metrics_group_time
    ON ops_system_metrics (group_id, created_at DESC)
    WHERE group_id IS NOT NULL;

CREATE INDEX IF NOT EXISTS idx_ops_retry_attempts_created_at
    ON ops_retry_attempts (created_at DESC);

CREATE INDEX IF NOT EXISTS idx_ops_retry_attempts_source_error
    ON ops_retry_attempts (source_error_id, created_at DESC)
    WHERE source_error_id IS NOT NULL;

CREATE UNIQUE INDEX IF NOT EXISTS idx_ops_retry_attempts_unique_active
    ON ops_retry_attempts (source_error_id)
    WHERE source_error_id IS NOT NULL AND status IN ('queued', 'running');

CREATE INDEX IF NOT EXISTS idx_ops_retry_attempts_success_time
    ON ops_retry_attempts (success, created_at DESC);

DO $$
BEGIN
  BEGIN
    CREATE EXTENSION IF NOT EXISTS pg_trgm;
  EXCEPTION WHEN OTHERS THEN
    RAISE NOTICE 'pg_trgm extension not created: %', SQLERRM;
  END;

  IF EXISTS (SELECT 1 FROM pg_extension WHERE extname = 'pg_trgm') THEN
    EXECUTE 'CREATE INDEX IF NOT EXISTS idx_ops_error_logs_request_id_trgm
             ON ops_error_logs USING gin (request_id gin_trgm_ops)';
    EXECUTE 'CREATE INDEX IF NOT EXISTS idx_ops_error_logs_client_request_id_trgm
             ON ops_error_logs USING gin (client_request_id gin_trgm_ops)';
    EXECUTE 'CREATE INDEX IF NOT EXISTS idx_ops_error_logs_error_message_trgm
             ON ops_error_logs USING gin (error_message gin_trgm_ops)';
  END IF;
END $$;

CREATE TABLE IF NOT EXISTS ops_alert_silences (
    id BIGSERIAL PRIMARY KEY,
    rule_id BIGINT NOT NULL,
    platform VARCHAR(64) NOT NULL,
    group_id BIGINT,
    region VARCHAR(64),
    until TIMESTAMPTZ NOT NULL,
    reason TEXT,
    created_by BIGINT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_ops_alert_silences_lookup
    ON ops_alert_silences (rule_id, platform, group_id, region, until);

CREATE TABLE IF NOT EXISTS ops_system_logs (
  id BIGSERIAL PRIMARY KEY,
  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  level VARCHAR(16) NOT NULL,
  component VARCHAR(128) NOT NULL DEFAULT '',
  message TEXT NOT NULL,
  request_id VARCHAR(128),
  client_request_id VARCHAR(128),
  user_id BIGINT,
  account_id BIGINT,
  platform VARCHAR(32),
  model VARCHAR(128),
  extra JSONB NOT NULL DEFAULT '{}'::jsonb
);

CREATE INDEX IF NOT EXISTS idx_ops_system_logs_created_at_id
  ON ops_system_logs (created_at DESC, id DESC);

CREATE INDEX IF NOT EXISTS idx_ops_system_logs_level_created_at
  ON ops_system_logs (level, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_ops_system_logs_component_created_at
  ON ops_system_logs (component, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_ops_system_logs_request_id
  ON ops_system_logs (request_id);

CREATE INDEX IF NOT EXISTS idx_ops_system_logs_client_request_id
  ON ops_system_logs (client_request_id);

CREATE INDEX IF NOT EXISTS idx_ops_system_logs_user_id_created_at
  ON ops_system_logs (user_id, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_ops_system_logs_account_id_created_at
  ON ops_system_logs (account_id, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_ops_system_logs_platform_model_created_at
  ON ops_system_logs (platform, model, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_ops_system_logs_message_search
  ON ops_system_logs USING GIN (to_tsvector('simple', COALESCE(message, '')));

CREATE TABLE IF NOT EXISTS ops_system_log_cleanup_audits (
  id BIGSERIAL PRIMARY KEY,
  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  operator_id BIGINT NOT NULL,
  conditions JSONB NOT NULL DEFAULT '{}'::jsonb,
  deleted_rows BIGINT NOT NULL DEFAULT 0
);

CREATE INDEX IF NOT EXISTS idx_ops_system_log_cleanup_audits_created_at
  ON ops_system_log_cleanup_audits (created_at DESC, id DESC);

INSERT INTO ops_alert_rules (
    name, description, enabled, metric_type, operator, threshold,
    window_minutes, sustained_minutes, severity, notify_email, cooldown_minutes,
    created_at, updated_at
) VALUES
    ('High Error Rate', 'Trigger when error rate stays above 5% for 5 minutes.', true, 'error_rate', '>', 5.0, 5, 5, 'P1', true, 20, NOW(), NOW()),
    ('Low Success Rate', 'Trigger when success rate stays below 95% for 5 minutes.', true, 'success_rate', '<', 95.0, 5, 5, 'P0', true, 15, NOW(), NOW()),
    ('High P99 Latency', 'Trigger when P99 latency stays above 3000ms for 10 minutes.', true, 'p99_latency_ms', '>', 3000.0, 5, 10, 'P2', true, 30, NOW(), NOW()),
    ('High P95 Latency', 'Trigger when P95 latency stays above 2000ms for 10 minutes.', true, 'p95_latency_ms', '>', 2000.0, 5, 10, 'P2', true, 30, NOW(), NOW()),
    ('High CPU Usage', 'Trigger when CPU usage stays above 85% for 10 minutes.', true, 'cpu_usage_percent', '>', 85.0, 5, 10, 'P2', true, 30, NOW(), NOW()),
    ('High Memory Usage', 'Trigger when memory usage stays above 90% for 10 minutes.', true, 'memory_usage_percent', '>', 90.0, 5, 10, 'P1', true, 20, NOW(), NOW()),
    ('Concurrency Queue Buildup', 'Trigger when concurrency queue depth stays above 100 for 5 minutes.', true, 'concurrency_queue_depth', '>', 100.0, 5, 5, 'P1', true, 20, NOW(), NOW()),
    ('Critical Error Rate', 'Trigger when error rate stays above 20% for 1 minute.', true, 'error_rate', '>', 20.0, 1, 1, 'P0', true, 15, NOW(), NOW())
ON CONFLICT (name) DO NOTHING;
