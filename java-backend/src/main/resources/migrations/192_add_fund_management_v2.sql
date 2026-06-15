-- Fund Management Center - Phase 2 Extension
-- Migration 192
-- Adds: counterparty/fee/group columns for transfer pairs,
--       cumulative stats on fund_accounts,
--       lend offer repayment tracking columns,
--       strict status checks.

-- 1. Extend fund_transactions for paired ledger entries (transfers, P2P lend)
ALTER TABLE fund_transactions
    ADD COLUMN IF NOT EXISTS related_user_id BIGINT REFERENCES users(id),
    ADD COLUMN IF NOT EXISTS fee DECIMAL(20, 8) NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS group_id VARCHAR(64),
    ADD COLUMN IF NOT EXISTS remark VARCHAR(255) NOT NULL DEFAULT '';

CREATE INDEX IF NOT EXISTS idx_fund_tx_group
    ON fund_transactions(group_id) WHERE group_id IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_fund_tx_related_user
    ON fund_transactions(related_user_id) WHERE related_user_id IS NOT NULL;

-- 2. Cumulative stats on fund_accounts
ALTER TABLE fund_accounts
    ADD COLUMN IF NOT EXISTS total_recharged DECIMAL(20, 8) NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS total_consumed DECIMAL(20, 8) NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS total_transferred_in DECIMAL(20, 8) NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS total_transferred_out DECIMAL(20, 8) NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS total_loan_out DECIMAL(20, 8) NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS total_loan_in DECIMAL(20, 8) NOT NULL DEFAULT 0;

-- 3. Extend fund_lending for complete P2P flow tracking
ALTER TABLE fund_lending
    ADD COLUMN IF NOT EXISTS platform_fee DECIMAL(20, 8) NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS total_repay_amount DECIMAL(20, 8) NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS remaining_amount DECIMAL(20, 8) NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS repaid_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS cancelled_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS remark VARCHAR(255) NOT NULL DEFAULT '';

-- 4. Extend fund_credit for risk metadata
ALTER TABLE fund_credit
    ADD COLUMN IF NOT EXISTS risk_level VARCHAR(16) NOT NULL DEFAULT 'normal',
    ADD COLUMN IF NOT EXISTS next_review_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS remark VARCHAR(255) NOT NULL DEFAULT '';

-- 5. Extend fund_audit_log with operator context
ALTER TABLE fund_audit_log
    ADD COLUMN IF NOT EXISTS operator_role VARCHAR(32),
    ADD COLUMN IF NOT EXISTS request_id VARCHAR(64),
    ADD COLUMN IF NOT EXISTS status VARCHAR(16) NOT NULL DEFAULT 'success';

CREATE INDEX IF NOT EXISTS idx_fund_audit_status
    ON fund_audit_log(status, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_fund_audit_request
    ON fund_audit_log(request_id) WHERE request_id IS NOT NULL;

-- 6. Freeze records: add expire handling metadata
ALTER TABLE fund_freezes
    ADD COLUMN IF NOT EXISTS unfreeze_reason VARCHAR(255) NOT NULL DEFAULT '',
    ADD COLUMN IF NOT EXISTS operator_id BIGINT;

-- 7. Add a topup_orders table for recharge tracking (links to fund_transactions)
CREATE TABLE IF NOT EXISTS fund_recharge_orders (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    account_id BIGINT REFERENCES fund_accounts(id),
    amount DECIMAL(20, 8) NOT NULL,
    fee DECIMAL(20, 8) NOT NULL DEFAULT 0,
    channel VARCHAR(32) NOT NULL DEFAULT 'manual',
    external_order_id VARCHAR(128),
    payment_order_id BIGINT,
    status VARCHAR(20) NOT NULL DEFAULT 'pending',
    remark VARCHAR(255) NOT NULL DEFAULT '',
    operator_id BIGINT,
    completed_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    deleted_at TIMESTAMPTZ
);

CREATE UNIQUE INDEX IF NOT EXISTS uk_fund_recharge_external
    ON fund_recharge_orders(external_order_id)
    WHERE external_order_id IS NOT NULL AND deleted_at IS NULL;
CREATE INDEX IF NOT EXISTS idx_fund_recharge_user
    ON fund_recharge_orders(user_id, created_at DESC) WHERE deleted_at IS NULL;
CREATE INDEX IF NOT EXISTS idx_fund_recharge_status
    ON fund_recharge_orders(status, created_at DESC) WHERE deleted_at IS NULL;
CREATE INDEX IF NOT EXISTS idx_fund_recharge_payment
    ON fund_recharge_orders(payment_order_id) WHERE payment_order_id IS NOT NULL;

COMMENT ON TABLE fund_recharge_orders IS 'Recharge orders - top-up flow into fund account';
