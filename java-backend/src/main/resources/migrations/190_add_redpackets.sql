-- Red packets master table
CREATE TABLE IF NOT EXISTS redpackets (
    id BIGSERIAL PRIMARY KEY,
    creator_id BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    code VARCHAR(32) NOT NULL,
    redpacket_type VARCHAR(16) NOT NULL DEFAULT 'random',
    total_amount DECIMAL(20, 8) NOT NULL,
    remaining_amount DECIMAL(20, 8) NOT NULL,
    total_count INT NOT NULL,
    remaining_count INT NOT NULL,
    memo VARCHAR(255) NOT NULL DEFAULT '',
    expire_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    deleted_at TIMESTAMPTZ
);

CREATE UNIQUE INDEX IF NOT EXISTS uk_redpackets_code
    ON redpackets(code)
    WHERE deleted_at IS NULL;

CREATE INDEX IF NOT EXISTS idx_redpackets_creator
    ON redpackets(creator_id, created_at DESC)
    WHERE deleted_at IS NULL;

-- Red packet claims table
CREATE TABLE IF NOT EXISTS redpacket_claims (
    id BIGSERIAL PRIMARY KEY,
    redpacket_id BIGINT NOT NULL REFERENCES redpackets(id) ON DELETE CASCADE,
    user_id BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    amount DECIMAL(20, 8) NOT NULL,
    balance_before DECIMAL(20, 8) NOT NULL,
    balance_after DECIMAL(20, 8) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    deleted_at TIMESTAMPTZ
);

CREATE UNIQUE INDEX IF NOT EXISTS uk_redpacket_claims_unique
    ON redpacket_claims(redpacket_id, user_id)
    WHERE deleted_at IS NULL;

CREATE INDEX IF NOT EXISTS idx_redpacket_claims_packet
    ON redpacket_claims(redpacket_id, created_at DESC)
    WHERE deleted_at IS NULL;

CREATE INDEX IF NOT EXISTS idx_redpacket_claims_user
    ON redpacket_claims(user_id, created_at DESC)
    WHERE deleted_at IS NULL;

COMMENT ON TABLE redpackets IS 'Red packet master table';
COMMENT ON TABLE redpacket_claims IS 'Red packet claim records';
