-- Persist the exchange-native settlement cadence used by the Paper scanner.
-- Existing rows intentionally remain NULL: their historical cadence is unknown.
ALTER TABLE cross_exchange_arb_opportunity
    ADD COLUMN IF NOT EXISTS long_funding_interval_hours INT NULL
        COMMENT '多头腿资金费结算周期（小时）' AFTER long_funding_rate,
    ADD COLUMN IF NOT EXISTS short_funding_interval_hours INT NULL
        COMMENT '空头腿资金费结算周期（小时）' AFTER short_funding_rate;
