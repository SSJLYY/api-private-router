CREATE TABLE IF NOT EXISTS java_openai_response_bindings (
    api_key_id BIGINT NOT NULL,
    route_key VARCHAR(255) NOT NULL,
    response_id VARCHAR(255) NOT NULL,
    account_id BIGINT NOT NULL,
    requested_model VARCHAR(255),
    session_model VARCHAR(255),
    prompt_cache_key VARCHAR(255),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    expires_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (api_key_id, route_key, response_id)
);

CREATE INDEX IF NOT EXISTS idx_java_openai_response_bindings_expires_at
    ON java_openai_response_bindings (expires_at);

CREATE INDEX IF NOT EXISTS idx_java_openai_response_bindings_account_id
    ON java_openai_response_bindings (account_id);
