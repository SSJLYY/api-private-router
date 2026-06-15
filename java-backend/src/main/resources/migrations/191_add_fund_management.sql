-- Fund Management Center - Unified Financial System
-- Migration 191

-- 1. Fund Accounts - User wallet with multiple account types
CREATE TABLE IF NOT EXISTS fund_accounts (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    account_type VARCHAR(32) NOT NULL DEFAULT 'main',
    balance DECIMAL(20, 8) NOT NULL DEFAULT 0,
    frozen_amount DECIMAL(20, 8) NOT NULL DEFAULT 0,
    credit_limit DECIMAL(20, 8) NOT NULL DEFAULT 0,
    credit_used DECIMAL(20, 8) NOT NULL DEFAULT 0,
    status VARCHAR(20) NOT NULL DEFAULT 'active',
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    deleted_at TIMESTAMPTZ
);

CREATE UNIQUE INDEX IF NOT EXISTS uk_fund_accounts_user_type
    ON fund_accounts(user_id, account_type) WHERE deleted_at IS NULL;

-- 2. Fund Transactions - Unified ledger
CREATE TABLE IF NOT EXISTS fund_transactions (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    account_id BIGINT REFERENCES fund_accounts(id),
    tx_type VARCHAR(32) NOT NULL,
    direction VARCHAR(8) NOT NULL,
    amount DECIMAL(20, 8) NOT NULL,
    balance_before DECIMAL(20, 8) NOT NULL,
    balance_after DECIMAL(20, 8) NOT NULL,
    ref_type VARCHAR(32),
    ref_id BIGINT,
    description VARCHAR(255) NOT NULL DEFAULT '',
    operator_id BIGINT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_fund_tx_user
    ON fund_transactions(user_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_fund_tx_type
    ON fund_transactions(tx_type, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_fund_tx_ref
    ON fund_transactions(ref_type, ref_id) WHERE ref_id IS NOT NULL;

-- 3. Fund Freezes - Temporary balance holds
CREATE TABLE IF NOT EXISTS fund_freezes (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    account_id BIGINT REFERENCES fund_accounts(id),
    amount DECIMAL(20, 8) NOT NULL,
    reason VARCHAR(255) NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'frozen',
    ref_type VARCHAR(32),
    ref_id BIGINT,
    frozen_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    unfrozen_at TIMESTAMPTZ,
    expire_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    deleted_at TIMESTAMPTZ
);

CREATE INDEX IF NOT EXISTS idx_fund_freezes_user
    ON fund_freezes(user_id, status) WHERE deleted_at IS NULL;

-- 4. Fund Credit - Credit line management
CREATE TABLE IF NOT EXISTS fund_credit (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    credit_limit DECIMAL(20, 8) NOT NULL DEFAULT 0,
    credit_used DECIMAL(20, 8) NOT NULL DEFAULT 0,
    interest_rate DECIMAL(8, 6) NOT NULL DEFAULT 0,
    status VARCHAR(20) NOT NULL DEFAULT 'active',
    approved_by BIGINT,
    approved_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    deleted_at TIMESTAMPTZ
);

CREATE UNIQUE INDEX IF NOT EXISTS uk_fund_credit_user
    ON fund_credit(user_id) WHERE deleted_at IS NULL;

-- 5. Fund Loans - Borrowing records
CREATE TABLE IF NOT EXISTS fund_loans (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    amount DECIMAL(20, 8) NOT NULL,
    interest_rate DECIMAL(8, 6) NOT NULL DEFAULT 0,
    interest_amount DECIMAL(20, 8) NOT NULL DEFAULT 0,
    repaid_amount DECIMAL(20, 8) NOT NULL DEFAULT 0,
    remaining_amount DECIMAL(20, 8) NOT NULL DEFAULT 0,
    status VARCHAR(20) NOT NULL DEFAULT 'active',
    due_date TIMESTAMPTZ,
    repaid_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    deleted_at TIMESTAMPTZ
);

CREATE INDEX IF NOT EXISTS idx_fund_loans_user
    ON fund_loans(user_id, status) WHERE deleted_at IS NULL;

-- 6. Fund Lending - User-to-user lending offers
CREATE TABLE IF NOT EXISTS fund_lending (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    lender_id BIGINT REFERENCES users(id),
    borrower_id BIGINT REFERENCES users(id),
    amount DECIMAL(20, 8) NOT NULL,
    interest_rate DECIMAL(8, 6) NOT NULL DEFAULT 0,
    duration_days INT NOT NULL DEFAULT 30,
    status VARCHAR(20) NOT NULL DEFAULT 'open',
    funded_at TIMESTAMPTZ,
    due_date TIMESTAMPTZ,
    repaid_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    deleted_at TIMESTAMPTZ
);

CREATE INDEX IF NOT EXISTS idx_fund_lending_user
    ON fund_lending(user_id, status) WHERE deleted_at IS NULL;

-- 7. Fund Audit Log - Complete financial audit trail
CREATE TABLE IF NOT EXISTS fund_audit_log (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT,
    action VARCHAR(64) NOT NULL,
    target_type VARCHAR(32),
    target_id BIGINT,
    amount DECIMAL(20, 8),
    before_value DECIMAL(20, 8),
    after_value DECIMAL(20, 8),
    operator_id BIGINT,
    ip_address VARCHAR(64),
    description VARCHAR(512),
    metadata_json TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_fund_audit_user
    ON fund_audit_log(user_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_fund_audit_action
    ON fund_audit_log(action, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_fund_audit_target
    ON fund_audit_log(target_type, target_id);

COMMENT ON TABLE fund_accounts IS 'User fund accounts (wallet)';
COMMENT ON TABLE fund_transactions IS 'Unified financial ledger';
COMMENT ON TABLE fund_freezes IS 'Frozen fund records';
COMMENT ON TABLE fund_credit IS 'User credit line management';
COMMENT ON TABLE fund_loans IS 'Borrowing records';
COMMENT ON TABLE fund_lending IS 'User-to-user lending';
COMMENT ON TABLE fund_audit_log IS 'Financial audit trail';