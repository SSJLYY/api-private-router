-- Migration 193: Add house/banker pool account
-- 庄家账户表
CREATE TABLE IF NOT EXISTS fund_house_accounts (
    id BIGSERIAL PRIMARY KEY,
    balance NUMERIC(20,8) NOT NULL DEFAULT 0,
    total_income NUMERIC(20,8) NOT NULL DEFAULT 0,
    total_expense NUMERIC(20,8) NOT NULL DEFAULT 0,
    status VARCHAR(32) NOT NULL DEFAULT 'active',
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- 庄家收支流水表
CREATE TABLE IF NOT EXISTS fund_house_transactions (
    id BIGSERIAL PRIMARY KEY,
    tx_type VARCHAR(64) NOT NULL,
    amount NUMERIC(20,8) NOT NULL,
    balance_before NUMERIC(20,8) NOT NULL DEFAULT 0,
    balance_after NUMERIC(20,8) NOT NULL DEFAULT 0,
    ref_type VARCHAR(64),
    ref_id BIGINT,
    user_id BIGINT,
    description TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- 索引
CREATE INDEX IF NOT EXISTS idx_house_tx_user ON fund_house_transactions(user_id);
CREATE INDEX IF NOT EXISTS idx_house_tx_type ON fund_house_transactions(tx_type);
CREATE INDEX IF NOT EXISTS idx_house_tx_created ON fund_house_transactions(created_at);

-- 插入默认庄家账户（单行设计，id=1）
INSERT INTO fund_house_accounts (id, balance, total_income, total_expense, status)
VALUES (1, 0, 0, 0, 'active')
ON CONFLICT (id) DO NOTHING;
