CREATE TABLE IF NOT EXISTS user_checkins (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    checkin_date DATE NOT NULL,
    timezone VARCHAR(64) NOT NULL,
    stake_amount DECIMAL(20, 8) NOT NULL,
    reward_amount DECIMAL(20, 8) NOT NULL,
    multiplier DECIMAL(4, 2) NOT NULL,
    net_change DECIMAL(20, 8) NOT NULL,
    balance_before DECIMAL(20, 8) NOT NULL,
    balance_after DECIMAL(20, 8) NOT NULL,
    checked_in_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    deleted_at TIMESTAMPTZ
);

CREATE UNIQUE INDEX IF NOT EXISTS uk_user_checkins_user_date
    ON user_checkins(user_id, checkin_date)
    WHERE deleted_at IS NULL;

CREATE INDEX IF NOT EXISTS idx_user_checkins_user_checked_in_at
    ON user_checkins(user_id, checked_in_at DESC)
    WHERE deleted_at IS NULL;

CREATE INDEX IF NOT EXISTS idx_user_checkins_user_month
    ON user_checkins(user_id, checkin_date)
    WHERE deleted_at IS NULL;

COMMENT ON TABLE user_checkins IS '用户每日签到记录';
COMMENT ON COLUMN user_checkins.checkin_date IS '按用户时区计算的签到日期';
COMMENT ON COLUMN user_checkins.timezone IS '签到使用的 IANA 时区';
COMMENT ON COLUMN user_checkins.stake_amount IS '签到时下注金额';
COMMENT ON COLUMN user_checkins.reward_amount IS '按倍率结算后的返奖金额';
COMMENT ON COLUMN user_checkins.multiplier IS '签到奖励倍率，范围 0.20-2.20';
COMMENT ON COLUMN user_checkins.net_change IS 'reward_amount - stake_amount';
COMMENT ON COLUMN user_checkins.balance_before IS '签到前余额快照';
COMMENT ON COLUMN user_checkins.balance_after IS '签到后余额快照';
COMMENT ON COLUMN user_checkins.checked_in_at IS '实际签到时间';
