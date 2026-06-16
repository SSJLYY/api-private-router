SET LOCAL lock_timeout = '5s';
SET LOCAL statement_timeout = '10min';

ALTER TABLE ops_metrics_hourly
    ADD COLUMN IF NOT EXISTS ttft_sample_count BIGINT NOT NULL DEFAULT 0;

ALTER TABLE ops_metrics_daily
    ADD COLUMN IF NOT EXISTS ttft_sample_count BIGINT NOT NULL DEFAULT 0;
