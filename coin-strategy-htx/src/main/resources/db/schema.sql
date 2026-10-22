-- htx_trade_strategy 专用库。买/卖 round-trip、写前订单、撤单、单腿裸露死信。

-- Registered HTX trading accounts. api_key_cipher / secret_key_cipher must store ENC(...) in production.
CREATE TABLE IF NOT EXISTS htx_trade_account (
    id                                      BIGINT       PRIMARY KEY AUTO_INCREMENT,
    account_label                           VARCHAR(32)  NOT NULL UNIQUE,
    exchange                                VARCHAR(16)  NOT NULL DEFAULT 'HTX',
    market_type                             VARCHAR(16)  NOT NULL DEFAULT 'USDT_SWAP',
    account_role                            VARCHAR(16)  NOT NULL DEFAULT 'FOLLOWER',
    enabled                                 TINYINT(1)   NOT NULL DEFAULT 1,
    trend_enabled                           TINYINT(1)   NOT NULL DEFAULT 1,
    mirror_enabled                          TINYINT(1)   NOT NULL DEFAULT 0,
    api_key_cipher                          VARCHAR(1024),
    secret_key_cipher                       VARCHAR(2048),
    base_url                                VARCHAR(255) NOT NULL DEFAULT 'https://api.hbdm.com',
    sync_leverage                           TINYINT(1)   NOT NULL DEFAULT 1,
    block_entry_on_leverage_sync_failure    TINYINT(1)   NOT NULL DEFAULT 0,
    block_primary_close_until_synced         TINYINT(1)   NOT NULL DEFAULT 1,
    sort_order                              INT          NOT NULL DEFAULT 100,
    note                                    VARCHAR(512),
    created_at                              DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at                              DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    INDEX idx_trade_account_trend           (enabled, trend_enabled, mirror_enabled, account_role, sort_order)
);

INSERT INTO htx_trade_account (account_label, exchange, market_type, account_role, enabled, trend_enabled,
                               mirror_enabled, base_url, sync_leverage, block_entry_on_leverage_sync_failure,
                               block_primary_close_until_synced, sort_order, note)
SELECT 'primary', 'HTX', 'USDT_SWAP', 'PRIMARY', 1, 1, 0, 'https://api.hbdm.com', 1, 0, 1, 0,
       'default primary trend account; credentials may be filled by migration'
WHERE NOT EXISTS (SELECT 1 FROM htx_trade_account WHERE account_label = 'primary');

-- Append-only account fee schedule. The sole non-null current_profile_key marks the active version for one account+contract.
CREATE TABLE IF NOT EXISTS htx_trade_account_fee_profile (
    id                   BIGINT         PRIMARY KEY AUTO_INCREMENT,
    account_id           BIGINT         NOT NULL,
    account_label        VARCHAR(32)    NOT NULL,
    exchange             VARCHAR(16)    NOT NULL DEFAULT 'HTX',
    market_type          VARCHAR(16)    NOT NULL DEFAULT 'USDT_SWAP',
    contract_code        VARCHAR(32)    NOT NULL,
    open_maker_rate      DECIMAL(36,18) NOT NULL,
    open_taker_rate      DECIMAL(36,18) NOT NULL,
    close_maker_rate     DECIMAL(36,18) NOT NULL,
    close_taker_rate     DECIMAL(36,18) NOT NULL,
    effective_taker_rate DECIMAL(36,18) NOT NULL,
    source               VARCHAR(32)    NOT NULL,
    fallback             TINYINT(1)     NOT NULL DEFAULT 0,
    current_profile_key  VARCHAR(128),
    effective_from       DATETIME(3)    NOT NULL,
    effective_to         DATETIME(3),
    last_observed_at     DATETIME(3)    NOT NULL,
    created_at           DATETIME(3)    NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at           DATETIME(3)    NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    UNIQUE KEY uk_trade_account_fee_profile_current (current_profile_key),
    INDEX idx_trade_account_fee_profile_history (account_id, market_type, contract_code, effective_from)
);

-- Cross-strategy append-only ledger projection. Execution tables remain the source of truth;
-- this table provides account/strategy/model level attribution for dashboards and backtests.
CREATE TABLE IF NOT EXISTS coin_strategy_ledger_event (
    id                   BIGINT         PRIMARY KEY AUTO_INCREMENT,
    event_key            VARCHAR(160)   NOT NULL UNIQUE,
    event_type           VARCHAR(32)    NOT NULL,
    account_id           BIGINT,
    account_label        VARCHAR(32)    NOT NULL DEFAULT 'primary',
    exchange             VARCHAR(16)    NOT NULL DEFAULT 'HTX',
    market_type          VARCHAR(16)    NOT NULL DEFAULT 'USDT_SWAP',
    strategy_family      VARCHAR(32)    NOT NULL,
    strategy_name        VARCHAR(64)    NOT NULL,
    model_name           VARCHAR(64)    NOT NULL,
    run_id               VARCHAR(96),
    factor_snapshot_id   BIGINT,
    symbol               VARCHAR(32),
    contract_code        VARCHAR(32),
    position_id          BIGINT,
    order_id             BIGINT,
    client_order_id      VARCHAR(128),
    side                 VARCHAR(16),
    execution_price_usdt DECIMAL(36,18),
    entry_price_usdt     DECIMAL(36,18),
    close_price_usdt     DECIMAL(36,18),
    filled_contracts     DECIMAL(36,18),
    contract_size        DECIMAL(36,18),
    amount_usdt          DECIMAL(36,18),
    fee_usdt             DECIMAL(36,18) NOT NULL DEFAULT 0,
    funding_fee_usdt     DECIMAL(36,18) NOT NULL DEFAULT 0,
    gross_pnl_usdt       DECIMAL(36,18),
    net_pnl_after_fee    DECIMAL(36,18),
    reason               VARCHAR(1024),
    occurred_at          DATETIME(3)    NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    created_at           DATETIME(3)    NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    INDEX idx_strategy_ledger_account_time  (account_id, account_label, occurred_at),
    INDEX idx_strategy_ledger_strategy_time (strategy_family, strategy_name, model_name, occurred_at),
    INDEX idx_strategy_ledger_position      (position_id, event_type, occurred_at),
    INDEX idx_strategy_ledger_symbol_time   (contract_code, occurred_at)
);

CREATE TABLE IF NOT EXISTS htx_locked_pair (
    id                  BIGINT       PRIMARY KEY AUTO_INCREMENT,
    client_order_id     VARCHAR(64)  NOT NULL UNIQUE,
    symbol              VARCHAR(32)  NOT NULL,
    req_quantity        DECIMAL(36,18) NOT NULL,
    buy_price           DECIMAL(36,18),
    sell_price          DECIMAL(36,18),
    status              VARCHAR(32)  NOT NULL,
    result_code         VARCHAR(48),
    buy_order_id        BIGINT,
    sell_order_id       BIGINT,
    buy_filled_amount   DECIMAL(36,18) DEFAULT 0,
    sell_filled_amount  DECIMAL(36,18) DEFAULT 0,
    net_base_delta      DECIMAL(36,18) DEFAULT 0,
    total_fee_base      DECIMAL(36,18) DEFAULT 0,
    total_fee_quote     DECIMAL(36,18) DEFAULT 0,
    net_quote_cost      DECIMAL(36,18) DEFAULT 0,
    dry_run             TINYINT(1)   NOT NULL DEFAULT 0,
    strategy            VARCHAR(16),
    message             VARCHAR(512),
    created_at          DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at          DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
);

CREATE TABLE IF NOT EXISTS htx_order (
    id                  BIGINT       PRIMARY KEY AUTO_INCREMENT,
    pair_id             BIGINT       NOT NULL,
    client_order_id     VARCHAR(64),
    htx_order_id        BIGINT,
    side                VARCHAR(8)   NOT NULL,
    type                VARCHAR(16)  NOT NULL,
    price               DECIMAL(36,18),
    req_amount          DECIMAL(36,18) NOT NULL,
    state               VARCHAR(32),
    filled_amount       DECIMAL(36,18) DEFAULT 0,
    filled_cash_amount  DECIMAL(36,18) DEFAULT 0,
    filled_fees         DECIMAL(36,18) DEFAULT 0,
    fee_currency        VARCHAR(16),
    intent_status       VARCHAR(16)  NOT NULL,
    raw_response        TEXT,
    created_at          DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at          DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    -- 索引内联在建表语句里：MySQL 8 不支持 CREATE INDEX IF NOT EXISTS，
    -- 而 CREATE TABLE IF NOT EXISTS 已保证幂等；H2(测试) 与 MySQL 8(生产) 均兼容 INDEX 语法。
    INDEX idx_order_pair   (pair_id),
    INDEX idx_order_htxid  (htx_order_id),
    INDEX idx_order_intent (intent_status)
);

CREATE TABLE IF NOT EXISTS htx_cancel (
    id                  BIGINT       PRIMARY KEY AUTO_INCREMENT,
    order_id            BIGINT       NOT NULL,
    htx_order_id        BIGINT,
    reason              VARCHAR(64),
    cancel_ok           TINYINT(1),
    post_cancel_state   VARCHAR(32),
    post_cancel_filled  DECIMAL(36,18) DEFAULT 0,
    created_at          DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
);

CREATE TABLE IF NOT EXISTS htx_exposure (
    id                  BIGINT       PRIMARY KEY AUTO_INCREMENT,
    pair_id             BIGINT       NOT NULL,
    symbol              VARCHAR(32)  NOT NULL,
    exposed_side        VARCHAR(8)   NOT NULL,
    exposed_amount      DECIMAL(36,18) NOT NULL,
    status              VARCHAR(24)  NOT NULL,
    flatten_order_id    BIGINT,
    flatten_retries     INT          NOT NULL DEFAULT 0,
    detail              VARCHAR(512),
    created_at          DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at          DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
);

-- 每个 symbol 一行的持久化移动网格窗口；价格越界只推进 epoch，不结束策略。
CREATE TABLE IF NOT EXISTS htx_grid_window (
    id                  BIGINT         PRIMARY KEY AUTO_INCREMENT,
    symbol              VARCHAR(32)    NOT NULL UNIQUE,
    anchor_price        DECIMAL(36,18) NOT NULL,
    lower_price         DECIMAL(36,18) NOT NULL,
    upper_price         DECIMAL(36,18) NOT NULL,
    step_pct            DECIMAL(36,18) NOT NULL,
    levels              INT            NOT NULL,
    epoch               BIGINT         NOT NULL,
    state               VARCHAR(24)    NOT NULL,
    breach_direction    VARCHAR(8),
    breach_count        INT            NOT NULL DEFAULT 0,
    last_migrated_at    DATETIME(3),
    created_at          DATETIME(3)    NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at          DATETIME(3)    NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
);

-- Append-only spot fill ledger. HTX order query exposes cumulative filled fields; each new
-- cumulative snapshot is converted to a delta row and de-duplicated by ledger_key.
CREATE TABLE IF NOT EXISTS htx_spot_fill_ledger (
    id                              BIGINT         PRIMARY KEY AUTO_INCREMENT,
    ledger_key                      VARCHAR(128)   NOT NULL UNIQUE,
    symbol                          VARCHAR(32)    NOT NULL,
    pair_id                         BIGINT,
    order_row_id                    BIGINT,
    htx_order_id                    BIGINT,
    client_order_id                 VARCHAR(64),
    side                            VARCHAR(8)     NOT NULL,
    order_state                     VARCHAR(32),
    delta_base                      DECIMAL(36,18) NOT NULL DEFAULT 0,
    delta_quote                     DECIMAL(36,18) NOT NULL DEFAULT 0,
    fee_amount                      DECIMAL(36,18) NOT NULL DEFAULT 0,
    fee_currency                    VARCHAR(16),
    cumulative_filled_amount        DECIMAL(36,18) NOT NULL DEFAULT 0,
    cumulative_filled_cash_amount   DECIMAL(36,18) NOT NULL DEFAULT 0,
    cumulative_filled_fees          DECIMAL(36,18) NOT NULL DEFAULT 0,
    source                          VARCHAR(32)    NOT NULL,
    observed_at                     DATETIME(3)    NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    created_at                      DATETIME(3)    NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    INDEX idx_spot_fill_order       (symbol, side, htx_order_id),
    INDEX idx_spot_fill_client      (symbol, side, client_order_id),
    INDEX idx_spot_fill_pair        (pair_id)
);

-- FIFO inventory lots sourced from buy fills. Sell fills consume remaining_base from the oldest
-- open lot first; unmatched sells remain visible via the ledger result and must not create debt rows.
CREATE TABLE IF NOT EXISTS htx_inventory_lot (
    id                  BIGINT         PRIMARY KEY AUTO_INCREMENT,
    symbol              VARCHAR(32)    NOT NULL,
    source_pair_id      BIGINT,
    buy_order_id        BIGINT,
    buy_fill_ledger_id  BIGINT,
    acquired_base       DECIMAL(36,18) NOT NULL,
    remaining_base      DECIMAL(36,18) NOT NULL,
    cost_quote          DECIMAL(36,18) NOT NULL DEFAULT 0,
    fee_base            DECIMAL(36,18) NOT NULL DEFAULT 0,
    status              VARCHAR(16)    NOT NULL,
    created_at          DATETIME(3)    NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at          DATETIME(3)    NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    INDEX idx_inventory_symbol_open (symbol, status, id),
    INDEX idx_inventory_pair        (source_pair_id)
);

-- Durable intent ledger for one logical aggregate short per moving-grid symbol.
-- The row is created before any future contract order send path, so restart recovery can
-- distinguish "planned" from "actually sent" without inferring from logs.
CREATE TABLE IF NOT EXISTS htx_delta_hedge_ledger (
    id                  BIGINT         PRIMARY KEY AUTO_INCREMENT,
    hedge_key           VARCHAR(128)   NOT NULL UNIQUE,
    symbol              VARCHAR(32)    NOT NULL,
    contract_code       VARCHAR(32)    NOT NULL,
    epoch               BIGINT         NOT NULL,
    action              VARCHAR(16)    NOT NULL,
    target_short_base   DECIMAL(36,18) NOT NULL DEFAULT 0,
    contract_volume     BIGINT         NOT NULL,
    side                VARCHAR(8)     NOT NULL,
    position_side       VARCHAR(8)     NOT NULL,
    reduce_only         TINYINT(1)     NOT NULL,
    client_order_id     VARCHAR(64)    NOT NULL,
    exchange_order_id   BIGINT,
    exchange_client_order_id VARCHAR(64),
    exchange_state      VARCHAR(32),
    filled_contracts    DECIMAL(36,18) NOT NULL DEFAULT 0,
    trade_avg_price     DECIMAL(36,18),
    fee                 DECIMAL(36,18),
    fee_currency        VARCHAR(16),
    realized_profit     DECIMAL(36,18),
    planner_state       VARCHAR(24)    NOT NULL,
    status              VARCHAR(24)    NOT NULL,
    reason              VARCHAR(512),
    created_at          DATETIME(3)    NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at          DATETIME(3)    NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    INDEX idx_delta_hedge_symbol    (symbol, epoch, status),
    INDEX idx_delta_hedge_client    (client_order_id)
);

-- Paired recycle saga: confirmed spot sell fill drives reduce-only short close.
-- It intentionally stores the spot-fill ledger id that triggered the close intent.
CREATE TABLE IF NOT EXISTS htx_inventory_recycle_saga (
    id                    BIGINT         PRIMARY KEY AUTO_INCREMENT,
    saga_key              VARCHAR(128)   NOT NULL UNIQUE,
    symbol                VARCHAR(32)    NOT NULL,
    contract_code         VARCHAR(32)    NOT NULL,
    epoch                 BIGINT         NOT NULL,
    spot_fill_ledger_id   BIGINT         NOT NULL,
    spot_sold_base        DECIMAL(36,18) NOT NULL,
    available_short_base  DECIMAL(36,18) NOT NULL,
    close_contracts       BIGINT         NOT NULL,
    close_client_order_id VARCHAR(64)    NOT NULL,
    close_exchange_order_id BIGINT,
    close_exchange_client_order_id VARCHAR(64),
    close_exchange_state  VARCHAR(32),
    close_filled_contracts DECIMAL(36,18) NOT NULL DEFAULT 0,
    close_trade_avg_price DECIMAL(36,18),
    close_fee             DECIMAL(36,18),
    close_fee_currency    VARCHAR(16),
    close_realized_profit DECIMAL(36,18),
    planner_state         VARCHAR(24)    NOT NULL,
    status                VARCHAR(24)    NOT NULL,
    reason                VARCHAR(512),
    created_at            DATETIME(3)    NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at            DATETIME(3)    NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    INDEX idx_recycle_symbol         (symbol, epoch, status),
    INDEX idx_recycle_spot_fill      (spot_fill_ledger_id)
);


-- Spot-long plus perpetual-short funding fee arbitrage cycles. A row is written before
-- the first live spot order, so restart recovery can continue the second leg instead of
-- opening another spot position.
CREATE TABLE IF NOT EXISTS htx_funding_arbitrage_cycle (
    id                              BIGINT         PRIMARY KEY AUTO_INCREMENT,
    cycle_key                       VARCHAR(128)   NOT NULL UNIQUE,
    symbol                          VARCHAR(32)    NOT NULL,
    contract_code                   VARCHAR(32)    NOT NULL,
    status                          VARCHAR(32)    NOT NULL,
    target_quote_notional           DECIMAL(36,18) NOT NULL,
    target_base_amount              DECIMAL(36,18) NOT NULL,
    contract_size                   DECIMAL(36,18) NOT NULL,
    contract_volume                 BIGINT         NOT NULL,
    leverage                        INT            NOT NULL,
    entry_funding_rate              DECIMAL(36,18) NOT NULL DEFAULT 0,
    latest_funding_rate             DECIMAL(36,18) NOT NULL DEFAULT 0,
    entry_spot_price                DECIMAL(36,18),
    entry_swap_price                DECIMAL(36,18),
    spot_entry_client_order_id      VARCHAR(64),
    spot_entry_order_id             BIGINT,
    spot_entry_filled_base          DECIMAL(36,18) NOT NULL DEFAULT 0,
    spot_entry_filled_quote         DECIMAL(36,18) NOT NULL DEFAULT 0,
    spot_entry_fee_base             DECIMAL(36,18) NOT NULL DEFAULT 0,
    short_entry_client_order_id     VARCHAR(64),
    short_entry_order_id            BIGINT,
    short_entry_filled_contracts    DECIMAL(36,18) NOT NULL DEFAULT 0,
    short_entry_avg_price           DECIMAL(36,18),
    short_entry_fee                 DECIMAL(36,18) NOT NULL DEFAULT 0,
    short_close_client_order_id     VARCHAR(64),
    short_close_order_id            BIGINT,
    short_close_filled_contracts    DECIMAL(36,18) NOT NULL DEFAULT 0,
    short_close_avg_price           DECIMAL(36,18),
    short_close_fee                 DECIMAL(36,18) NOT NULL DEFAULT 0,
    spot_exit_client_order_id       VARCHAR(64),
    spot_exit_order_id              BIGINT,
    spot_exit_filled_base           DECIMAL(36,18) NOT NULL DEFAULT 0,
    spot_exit_filled_quote          DECIMAL(36,18) NOT NULL DEFAULT 0,
    spot_exit_fee_quote             DECIMAL(36,18) NOT NULL DEFAULT 0,
    exit_reason                     VARCHAR(512),
    message                         VARCHAR(512),
    created_at                      DATETIME(3)    NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at                      DATETIME(3)    NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    INDEX idx_funding_arb_active    (symbol, contract_code, status),
    INDEX idx_funding_arb_spot      (spot_entry_order_id),
    INDEX idx_funding_arb_short     (short_entry_order_id)
);

-- USDT-margined perpetual cycles are isolated from spot rows because volume is contracts, not base quantity.
CREATE TABLE IF NOT EXISTS htx_contract_cycle (
    id                    BIGINT         PRIMARY KEY AUTO_INCREMENT,
    client_order_id       BIGINT         NOT NULL UNIQUE,
    contract_code         VARCHAR(32)    NOT NULL,
    req_volume            BIGINT         NOT NULL,
    open_price            DECIMAL(36,18) NOT NULL,
    close_price           DECIMAL(36,18) NOT NULL,
    leverage              INT            NOT NULL,
    status                VARCHAR(24)    NOT NULL,
    open_order_id         BIGINT,
    close_order_id        BIGINT,
    open_filled_volume    DECIMAL(36,18) DEFAULT 0,
    close_filled_volume   DECIMAL(36,18) DEFAULT 0,
    message               VARCHAR(512),
    created_at            DATETIME(3)    NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at            DATETIME(3)    NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    INDEX idx_contract_cycle_active (contract_code, status)
);

-- Four durable legs for one dual-position cycle. Existing long-only cycle columns remain for compatibility.
CREATE TABLE IF NOT EXISTS htx_contract_leg (
    id                    BIGINT         PRIMARY KEY AUTO_INCREMENT,
    cycle_id              BIGINT         NOT NULL,
    leg_type              VARCHAR(16)    NOT NULL,
    client_order_id       BIGINT         NOT NULL UNIQUE,
    order_id              BIGINT,
    side                  VARCHAR(8)     NOT NULL,
    position_side         VARCHAR(8)     NOT NULL,
    reduce_only           TINYINT(1)     NOT NULL,
    req_volume            BIGINT         NOT NULL,
    price                 DECIMAL(36,18) NOT NULL,
    state                 VARCHAR(32)    NOT NULL,
    filled_volume         DECIMAL(36,18) DEFAULT 0,
    average_price         DECIMAL(36,18),
    fee                   DECIMAL(36,18) DEFAULT 0,
    fee_currency          VARCHAR(16),
    realized_profit       DECIMAL(36,18) DEFAULT 0,
    message               VARCHAR(512),
    created_at            DATETIME(3)    NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at            DATETIME(3)    NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    UNIQUE KEY uk_contract_leg_cycle_type (cycle_id, leg_type),
    INDEX idx_contract_leg_order (order_id)
);

-- Contract-only trend-following managed positions. The strategy refuses to take over external
-- positions; this table owns only positions opened by htx.trend.
CREATE TABLE IF NOT EXISTS htx_trend_position (
    id                       BIGINT         PRIMARY KEY AUTO_INCREMENT,
    source_position_id       BIGINT         NULL COMMENT 'Primary position that originated a follower-account mirror',
    position_key             VARCHAR(128)   NOT NULL UNIQUE,
    run_id                   VARCHAR(128),
    contract_code            VARCHAR(32)    NOT NULL,
    status                   VARCHAR(32)    NOT NULL,
    side                     VARCHAR(8)     NOT NULL,
    contract_size            DECIMAL(36,18) NOT NULL,
    contract_volume          BIGINT         NOT NULL,
    remaining_contract_volume BIGINT        NOT NULL DEFAULT 0,
    partial_exit_stage       INT            NOT NULL DEFAULT 0,
    partial_close_client_order_id VARCHAR(64),
    partial_close_order_id   BIGINT,
    leverage                 INT            NOT NULL,
    entry_client_order_id    VARCHAR(64),
    entry_order_id           BIGINT,
    entry_filled_contracts   DECIMAL(36,18) NOT NULL DEFAULT 0,
    entry_avg_price          DECIMAL(36,18),
    entry_fee                DECIMAL(36,18) NOT NULL DEFAULT 0,
    close_client_order_id    VARCHAR(64),
    close_order_id           BIGINT,
    close_filled_contracts   DECIMAL(36,18) NOT NULL DEFAULT 0,
    close_avg_price          DECIMAL(36,18),
    close_fee                DECIMAL(36,18) NOT NULL DEFAULT 0,
    realized_profit          DECIMAL(36,18),
    final_gross_profit       DECIMAL(36,18),
    final_total_fee          DECIMAL(36,18),
    final_net_profit         DECIMAL(36,18),
    final_pnl_source         VARCHAR(32),
    final_pnl_calculated_at  DATETIME(3),
    stop_price               DECIMAL(36,18),
    trailing_stop_price      DECIMAL(36,18),
    take_profit_price        DECIMAL(36,18),
    highest_price            DECIMAL(36,18),
    lowest_price             DECIMAL(36,18),
    entry_signal             VARCHAR(512),
    exit_reason              VARCHAR(512),
    message                  VARCHAR(512),
    account_id               BIGINT,
    account_label            VARCHAR(32)    NOT NULL DEFAULT 'primary',
    strategy_family          VARCHAR(32)    NOT NULL DEFAULT 'TREND',
    strategy_name            VARCHAR(64)    NOT NULL DEFAULT 'HTX_TREND_V1',
    model_name               VARCHAR(64)    NOT NULL DEFAULT 'LEGACY_TREND_SIGNAL',
    entry_fee_rate           DECIMAL(36,18),
    entry_fee_profile_id     BIGINT,
    estimated_entry_fee      DECIMAL(36,18),
    estimated_close_fee      DECIMAL(36,18),
    expected_net_after_fees  DECIMAL(36,18),
    sizing_multiplier        DECIMAL(18,8),
    sizing_reason            VARCHAR(512),
    entry_gate_reason        VARCHAR(1024),
    primary_model            VARCHAR(64),
    confirmation_models      VARCHAR(256),
    policy_version           VARCHAR(64),
    timeframe_profile        VARCHAR(32)    NOT NULL DEFAULT 'INTRADAY_15M',
    market_regime            VARCHAR(32),
    factor_snapshot_id       BIGINT,
    entry_hypothesis         VARCHAR(1024),
    invalidation_price       DECIMAL(36,18),
    target_zone_low          DECIMAL(36,18),
    target_zone_high         DECIMAL(36,18),
    expected_r_multiple      DECIMAL(36,18),
    momentum_quality_score   DECIMAL(36,18),
    exit_snapshot_state      VARCHAR(32),
    exit_snapshot_net        DECIMAL(36,18),
    exit_snapshot_mfe        DECIMAL(36,18),
    exit_snapshot_mae        DECIMAL(36,18),
    exit_snapshot_giveback   DECIMAL(18,8),
    exit_snapshot_bars       BIGINT,
    exit_snapshot_candidates VARCHAR(1024),
    exit_snapshot_updated_at DATETIME(3),
    exit_phase               VARCHAR(32),
    trailing_armed_at        DATETIME(3),
    trailing_arm_reason      VARCHAR(512),
    max_favorable_r          DECIMAL(36,18),
    profit_lock_price        DECIMAL(36,18),
    profit_protection_mfe_price DECIMAL(36,18),
    profit_protection_mfe_net_pnl DECIMAL(36,18),
    profit_protection_price DECIMAL(36,18),
    profit_protection_projected_net_pnl DECIMAL(36,18),
    profit_protection_capture_ratio DECIMAL(18,8),
    profit_protection_mode  VARCHAR(32),
    profit_protection_updated_at DATETIME(3),
    exit_engine_version      VARCHAR(64),
    exit_attribution_snapshot_id BIGINT,
    created_at               DATETIME(3)    NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at               DATETIME(3)    NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    INDEX idx_trend_position_contract (contract_code, status),
    INDEX idx_trend_position_source_account (source_position_id, account_label),
    INDEX idx_trend_position_account  (account_id, account_label, status, updated_at),
    INDEX idx_trend_position_strategy (strategy_family, strategy_name, model_name, updated_at),
    INDEX idx_trend_position_entry    (entry_order_id),
    INDEX idx_trend_position_close    (close_order_id)
    ,INDEX idx_trend_position_run     (run_id)
    ,INDEX idx_trend_position_profile (timeframe_profile, status, updated_at)
);

-- Append-only record of each model-specific exit decision. It is intentionally separate from
-- the mutable position row so observation and later replay can explain actions not taken.
CREATE TABLE IF NOT EXISTS htx_trend_exit_decision (
    id                        BIGINT         PRIMARY KEY AUTO_INCREMENT,
    decision_key              VARCHAR(160)   NOT NULL UNIQUE,
    position_id               BIGINT         NOT NULL,
    run_id                    VARCHAR(128),
    account_id                BIGINT,
    account_label             VARCHAR(32)    NOT NULL DEFAULT 'primary',
    contract_code             VARCHAR(32)    NOT NULL,
    side                      VARCHAR(8)     NOT NULL,
    primary_model             VARCHAR(64),
    engine_version            VARCHAR(64)    NOT NULL,
    exit_phase                VARCHAR(32)    NOT NULL,
    suggested_action          VARCHAR(32)    NOT NULL,
    executed_action           VARCHAR(32)    NOT NULL,
    execution_block_reason   VARCHAR(512),
    reference_price           DECIMAL(36,18),
    protection_price          DECIMAL(36,18),
    expected_target_price     DECIMAL(36,18),
    current_net_pnl           DECIMAL(36,18),
    mfe_net_pnl               DECIMAL(36,18),
    mae_net_pnl               DECIMAL(36,18),
    giveback_rate             DECIMAL(18,8),
    max_favorable_r           DECIMAL(36,18),
    profit_protection_mfe_price DECIMAL(36,18),
    projected_locked_net_pnl  DECIMAL(36,18),
    mfe_capture_ratio         DECIMAL(18,8),
    protection_mode           VARCHAR(32),
    execution_readiness       VARCHAR(32),
    target_remaining_contracts BIGINT,
    evidence_json             CLOB,
    created_at                DATETIME(3)    NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    INDEX idx_trend_exit_decision_position_time (position_id, created_at),
    INDEX idx_trend_exit_decision_account_time (account_label, created_at)
);

-- Test/bootstrap mirror of the explicit trend-exit-v5 migration. Production SQL init is disabled.
CREATE TABLE IF NOT EXISTS htx_trend_entry_contract (id BIGINT AUTO_INCREMENT PRIMARY KEY,position_id BIGINT NOT NULL,position_epoch BIGINT NOT NULL,account_id BIGINT NOT NULL,strategy_instance VARCHAR(64) NOT NULL,engine_version VARCHAR(32) NOT NULL,policy_hash CHAR(64) NOT NULL,contract_hash CHAR(64) NOT NULL,structure_id VARCHAR(160) NOT NULL,contract_json CLOB NOT NULL,created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),UNIQUE KEY uk_trend_entry_contract_epoch(position_id,position_epoch));
CREATE TABLE IF NOT EXISTS htx_trend_exit_owner_lease_v5 (scope_key VARCHAR(160) PRIMARY KEY,owner_id VARCHAR(128) NOT NULL,owner_token BIGINT NOT NULL,lease_until DATETIME(3) NOT NULL,renewed_at DATETIME(3) NOT NULL,UNIQUE KEY uk_trend_exit_owner_token(scope_key,owner_token));
CREATE TABLE IF NOT EXISTS htx_trend_exit_state_v5 (position_id BIGINT PRIMARY KEY,position_epoch BIGINT NOT NULL,revision BIGINT NOT NULL,contract_id BIGINT NOT NULL,owner_token BIGINT NOT NULL,last_bar_end BIGINT,last_event_key VARCHAR(160) NOT NULL,phase VARCHAR(24) NOT NULL,execution_state VARCHAR(24) NOT NULL,confirmed_remaining_qty DECIMAL(36,18) NOT NULL,state_json CLOB NOT NULL,updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3));
CREATE TABLE IF NOT EXISTS htx_trend_exit_intent_v5 (id BIGINT AUTO_INCREMENT PRIMARY KEY,decision_key VARCHAR(160) NOT NULL,position_id BIGINT NOT NULL,position_epoch BIGINT NOT NULL,state_revision BIGINT NOT NULL,owner_token BIGINT NOT NULL,client_order_id VARCHAR(64) NOT NULL,target_remaining_qty DECIMAL(36,18) NOT NULL,status VARCHAR(24) NOT NULL,payload_json CLOB NOT NULL,created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),UNIQUE KEY uk_trend_exit_intent_decision(decision_key),UNIQUE KEY uk_trend_exit_intent_client(client_order_id),INDEX idx_trend_exit_intent_position(position_id,position_epoch));
CREATE TABLE IF NOT EXISTS htx_trend_exit_fill_v5 (id BIGINT AUTO_INCREMENT PRIMARY KEY,intent_id BIGINT NOT NULL,account_id BIGINT NOT NULL,exchange_order_id VARCHAR(64) NOT NULL,trade_id VARCHAR(128) NOT NULL,filled_qty DECIMAL(36,18) NOT NULL,fill_price DECIMAL(36,18) NOT NULL,fee DECIMAL(36,18) NOT NULL,allocated_entry_fee DECIMAL(36,18) NOT NULL,allocated_funding DECIMAL(36,18) NOT NULL,filled_at DATETIME(3) NOT NULL,created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),UNIQUE KEY uk_trend_exit_fill_trade(account_id,trade_id));
CREATE TABLE IF NOT EXISTS htx_trend_reentry_guard_v5 (guard_key CHAR(64) PRIMARY KEY,account_id BIGINT NOT NULL,strategy_family VARCHAR(32) NOT NULL,contract_code VARCHAR(32) NOT NULL,side VARCHAR(8) NOT NULL,structure_id VARCHAR(160) NOT NULL,revision BIGINT NOT NULL,same_structure_attempts INT NOT NULL DEFAULT 0,accumulated_realized_loss DECIMAL(36,18) NOT NULL DEFAULT 0,accumulated_fees DECIMAL(36,18) NOT NULL DEFAULT 0,exit_reason VARCHAR(64) NOT NULL,status VARCHAR(24) NOT NULL,evidence_json CLOB NOT NULL,updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3));

CREATE TABLE IF NOT EXISTS htx_trend_assessment (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    assessment_key VARCHAR(192) NOT NULL UNIQUE,
    assessment_stage VARCHAR(32) NOT NULL,
    position_id BIGINT,
    run_id VARCHAR(128), account_label VARCHAR(64) NOT NULL DEFAULT 'primary', contract_code VARCHAR(32) NOT NULL, side VARCHAR(8), primary_model VARCHAR(64),
    timeframe_profile VARCHAR(32), bar_time BIGINT, trend_state VARCHAR(32) NOT NULL,
    previous_trend_state VARCHAR(32), transition_type VARCHAR(64), transition_reason VARCHAR(512),
    reference_price DECIMAL(36,18), expected_r_multiple DECIMAL(36,18), current_net_pnl DECIMAL(36,18),
    mfe_net_pnl DECIMAL(36,18), mae_net_pnl DECIMAL(36,18), protection_price DECIMAL(36,18), evidence_json LONGTEXT,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    INDEX idx_trend_assessment_position_time (position_id, bar_time),
    INDEX idx_trend_assessment_account_time (account_label, created_at),
    INDEX idx_trend_assessment_stage_time (assessment_stage, created_at)
);

-- Append-only transitions for MFE protection. P0 only records recommendations; later phases
-- add conditional-order identifiers and execution reconciliation without rewriting this history.
CREATE TABLE IF NOT EXISTS htx_trend_profit_protection_event (
    id                        BIGINT         PRIMARY KEY AUTO_INCREMENT,
    event_key                 VARCHAR(160)   NOT NULL UNIQUE,
    position_id               BIGINT         NOT NULL,
    run_id                    VARCHAR(128),
    account_id                BIGINT,
    account_label             VARCHAR(32)    NOT NULL DEFAULT 'primary',
    contract_code             VARCHAR(32)    NOT NULL,
    side                      VARCHAR(8)     NOT NULL,
    primary_model             VARCHAR(64),
    event_type                VARCHAR(32)    NOT NULL,
    protection_mode           VARCHAR(32),
    reference_price           DECIMAL(36,18),
    mfe_price                 DECIMAL(36,18),
    mfe_net_pnl               DECIMAL(36,18),
    protection_price          DECIMAL(36,18),
    projected_locked_net_pnl  DECIMAL(36,18),
    mfe_capture_ratio         DECIMAL(18,8),
    execution_readiness       VARCHAR(32)    NOT NULL,
    reason                    VARCHAR(512),
    evidence_json             CLOB,
    created_at                DATETIME(3)    NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    INDEX idx_trend_profit_protection_position_time (position_id, created_at),
    INDEX idx_trend_profit_protection_account_time (account_label, created_at)
);

-- Immutable factor evidence for a combined trend candidate. Policy and position rows reference this record;
-- the JSON is intentionally append-only so a later manual policy change cannot rewrite trade evidence.
CREATE TABLE IF NOT EXISTS htx_trend_factor_snapshot (
    id                       BIGINT         PRIMARY KEY AUTO_INCREMENT,
    snapshot_key             VARCHAR(160)   NOT NULL,
    contract_code            VARCHAR(32)    NOT NULL,
    period                   VARCHAR(16)    NOT NULL,
    bar_time                 BIGINT         NOT NULL,
    market_regime            VARCHAR(32)    NOT NULL,
    factors_json             TEXT           NOT NULL,
    created_at               DATETIME(3)    NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    UNIQUE KEY uk_trend_factor_snapshot_key (snapshot_key),
    INDEX idx_trend_factor_contract_time (contract_code, period, bar_time)
);

-- Immutable simulated fills. This table is isolated from the real HTX order and position tables.
CREATE TABLE IF NOT EXISTS htx_trend_paper_execution (
    id                   BIGINT         PRIMARY KEY AUTO_INCREMENT,
    execution_key        VARCHAR(160)   NOT NULL,
    run_id               VARCHAR(128)   NOT NULL,
    contract_code        VARCHAR(32)    NOT NULL,
    outcome              VARCHAR(32)    NOT NULL,
    fill_price           DECIMAL(36,18),
    filled_contracts     BIGINT,
    reason               VARCHAR(1024),
    created_at           DATETIME(3)    NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    UNIQUE KEY uk_htx_trend_paper_execution_key (execution_key),
    INDEX idx_htx_trend_paper_execution_run (run_id, created_at)
);

-- Append-only lifecycle events for a paper order. The summary above intentionally remains
-- compatible with the original fill callback while this table preserves every transition.
CREATE TABLE IF NOT EXISTS htx_trend_paper_execution_event (
    id                         BIGINT         PRIMARY KEY AUTO_INCREMENT,
    event_key                  VARCHAR(192)   NOT NULL,
    execution_key              VARCHAR(160)   NOT NULL,
    run_id                     VARCHAR(128)   NOT NULL,
    state_from                 VARCHAR(32)    NOT NULL,
    state_to                   VARCHAR(32)    NOT NULL,
    event_type                 VARCHAR(32)    NOT NULL,
    requested_contracts        BIGINT         NOT NULL,
    cumulative_filled_contracts BIGINT        NOT NULL DEFAULT 0,
    fill_price                 DECIMAL(36,18),
    reason                     VARCHAR(1024),
    occurred_at                DATETIME(3)    NOT NULL,
    created_at                 DATETIME(3)    NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    UNIQUE KEY uk_htx_trend_paper_execution_event_key (event_key),
    INDEX idx_htx_trend_paper_execution_event_execution (execution_key, id),
    INDEX idx_htx_trend_paper_execution_event_run (run_id, occurred_at)
);

-- Immutable input manifest and per-profile outcomes for deterministic exit comparisons.
CREATE TABLE IF NOT EXISTS htx_trend_replay_run (
    id                      BIGINT         PRIMARY KEY AUTO_INCREMENT,
    replay_run_id           VARCHAR(96)    NOT NULL,
    engine_version          VARCHAR(96)    NOT NULL,
    input_start_at          DATETIME(3)    NOT NULL,
    input_end_at            DATETIME(3)    NOT NULL,
    periods                 VARCHAR(256)   NOT NULL,
    status                  VARCHAR(32)    NOT NULL,
    input_integrity         VARCHAR(32)    NOT NULL,
    candidate_count         BIGINT         NOT NULL DEFAULT 0,
    trade_count             BIGINT         NOT NULL DEFAULT 0,
    comparable_trade_count  BIGINT         NOT NULL DEFAULT 0,
    net_profit              DECIMAL(36,18),
    profit_factor           DECIMAL(36,18),
    comparison_summary_json MEDIUMTEXT,
    message                 VARCHAR(1024),
    created_at              DATETIME(3)    NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    completed_at            DATETIME(3),
    UNIQUE KEY uk_htx_trend_replay_run_id (replay_run_id),
    INDEX idx_htx_trend_replay_run_time (created_at)
);

CREATE TABLE IF NOT EXISTS htx_trend_replay_candle (
    id              BIGINT         PRIMARY KEY AUTO_INCREMENT,
    replay_run_id   VARCHAR(96)    NOT NULL,
    contract_code   VARCHAR(32)    NOT NULL,
    period          VARCHAR(16)    NOT NULL,
    bar_time        BIGINT         NOT NULL,
    open_price      DECIMAL(36,18) NOT NULL,
    high_price      DECIMAL(36,18) NOT NULL,
    low_price       DECIMAL(36,18) NOT NULL,
    close_price     DECIMAL(36,18) NOT NULL,
    volume          DECIMAL(36,18),
    turnover        DECIMAL(36,18),
    trade_count     BIGINT,
    created_at      DATETIME(3)    NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    UNIQUE KEY uk_htx_trend_replay_candle (replay_run_id, contract_code, period, bar_time),
    INDEX idx_htx_trend_replay_candle_lookup (replay_run_id, contract_code, period, bar_time)
);

CREATE TABLE IF NOT EXISTS htx_trend_replay_trade (
    id                  BIGINT         PRIMARY KEY AUTO_INCREMENT,
    replay_run_id       VARCHAR(96)    NOT NULL,
    exit_profile        VARCHAR(32)    NOT NULL,
    source_candidate_id BIGINT         NOT NULL,
    source_position_id  BIGINT,
    source_run_id       VARCHAR(128),
    contract_code       VARCHAR(32)    NOT NULL,
    period              VARCHAR(16)    NOT NULL,
    direction           VARCHAR(16),
    market_regime       VARCHAR(32),
    primary_model       VARCHAR(64),
    entry_bar_time      BIGINT         NOT NULL,
    entry_price         DECIMAL(36,18),
    exit_bar_time       BIGINT,
    exit_price          DECIMAL(36,18),
    initial_contracts   BIGINT,
    remaining_contracts BIGINT,
    mfe                 DECIMAL(36,18),
    mae                 DECIMAL(36,18),
    entry_fee           DECIMAL(36,18),
    close_fee           DECIMAL(36,18),
    net_profit          DECIMAL(36,18),
    outcome_state       VARCHAR(32)    NOT NULL,
    exit_reason         VARCHAR(64)    NOT NULL,
    data_integrity      VARCHAR(32)    NOT NULL,
    ambiguity_reason    VARCHAR(512),
    message             VARCHAR(1024),
    created_at          DATETIME(3)    NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    UNIQUE KEY uk_htx_trend_replay_trade (replay_run_id, source_candidate_id, exit_profile),
    INDEX idx_htx_trend_replay_trade_source (source_position_id, source_run_id),
    INDEX idx_htx_trend_replay_trade_profile (replay_run_id, exit_profile, data_integrity, net_profit)
);

-- Dashboard-managed policy. Only the latest enabled version may promote a combination candidate to live entry.
CREATE TABLE IF NOT EXISTS htx_trend_strategy_policy (
    id                       BIGINT         PRIMARY KEY AUTO_INCREMENT,
    policy_code              VARCHAR(96)    NOT NULL,
    policy_version           VARCHAR(64)    NOT NULL,
    primary_model            VARCHAR(64)    NOT NULL,
    confirmation_models      VARCHAR(256)   NOT NULL,
    enabled                  TINYINT(1)     NOT NULL DEFAULT 1,
    base_notional_usdt       DECIMAL(36,18) NOT NULL,
    max_notional_usdt        DECIMAL(36,18) NOT NULL,
    score_threshold          DECIMAL(36,18) NOT NULL,
    box_window_bars          INT            NOT NULL DEFAULT 32,
    updated_by               VARCHAR(64)    NOT NULL DEFAULT 'system',
    change_note              VARCHAR(512),
    created_at               DATETIME(3)    NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at               DATETIME(3)    NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    UNIQUE KEY uk_trend_policy_code_version (policy_code, policy_version),
    INDEX idx_trend_policy_active (enabled, policy_code, updated_at)
);

CREATE TABLE IF NOT EXISTS htx_trend_strategy_policy_audit (
    id                       BIGINT         PRIMARY KEY AUTO_INCREMENT,
    policy_id                BIGINT         NOT NULL,
    policy_code              VARCHAR(96)    NOT NULL,
    policy_version           VARCHAR(64)    NOT NULL,
    action                   VARCHAR(32)    NOT NULL,
    enabled                  TINYINT(1)     NOT NULL,
    changed_by               VARCHAR(64)    NOT NULL,
    change_note              VARCHAR(512),
    created_at               DATETIME(3)    NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    INDEX idx_trend_policy_audit_policy_time (policy_id, created_at)
);

-- Contract-order audit trail for HTX trend following. A row is inserted before each
-- live order send; if this insert fails, the strategy refuses to send the order.
CREATE TABLE IF NOT EXISTS htx_trend_order (
    id                       BIGINT         PRIMARY KEY AUTO_INCREMENT,
    position_id              BIGINT         NOT NULL,
    position_key             VARCHAR(128)   NOT NULL,
    run_id                   VARCHAR(128),
    contract_code            VARCHAR(32)    NOT NULL,
    leg                      VARCHAR(16)    NOT NULL,
    trend_side               VARCHAR(8)     NOT NULL,
    order_side               VARCHAR(8)     NOT NULL,
    position_side            VARCHAR(8)     NOT NULL,
    reduce_only              TINYINT(1)     NOT NULL DEFAULT 0,
    client_order_id          VARCHAR(128)    NOT NULL,
    exchange_order_id        BIGINT,
    exchange_client_order_id BIGINT,
    request_volume           BIGINT         NOT NULL,
    filled_contracts         DECIMAL(36,18) NOT NULL DEFAULT 0,
    average_price            DECIMAL(36,18),
    fee                      DECIMAL(36,18) NOT NULL DEFAULT 0,
    fee_currency             VARCHAR(16),
    realized_profit          DECIMAL(36,18),
    exchange_state           VARCHAR(32),
    status                   VARCHAR(32)    NOT NULL,
    reason                   VARCHAR(512),
    message                  VARCHAR(512),
    account_id               BIGINT,
    account_label            VARCHAR(32)    NOT NULL DEFAULT 'primary',
    strategy_family          VARCHAR(32)    NOT NULL DEFAULT 'TREND',
    strategy_name            VARCHAR(64)    NOT NULL DEFAULT 'HTX_TREND_V1',
    model_name               VARCHAR(64)    NOT NULL DEFAULT 'LEGACY_TREND_SIGNAL',
    factor_snapshot_id       BIGINT,
    timeframe_profile        VARCHAR(32)    NOT NULL DEFAULT 'INTRADAY_15M',
    fee_rate_used            DECIMAL(36,18),
    fee_profile_id           BIGINT,
    estimated_fee            DECIMAL(36,18),
    estimated_open_fee       DECIMAL(36,18),
    estimated_close_fee      DECIMAL(36,18),
    sizing_multiplier        DECIMAL(18,8),
    sizing_reason            VARCHAR(512),
    entry_gate_reason        VARCHAR(1024),
    signal_px DECIMAL(36,18) NULL COMMENT 'Signal price; never inferred from fills',
    signal_ts DATETIME(6) NULL COMMENT 'Signal time in UTC',
    order_px DECIMAL(36,18) NULL COMMENT 'Submission price: limit or contemporaneous mid/last',
    order_px_source VARCHAR(16) NULL COMMENT 'Only limit, mid, last; NULL when unknown',
    order_ts DATETIME(6) NULL COMMENT 'Actual submission invocation time in UTC',
    cancel_count INT NULL COMMENT 'NULL means unknown, not zero',
    amend_count INT NULL COMMENT 'NULL means unknown, not zero',
    CONSTRAINT chk_htx_order_px_source CHECK (order_px_source IN ('limit','mid','last')),
    created_at               DATETIME(3)    NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at               DATETIME(3)    NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    UNIQUE KEY uk_trend_order_client (client_order_id),
    INDEX idx_trend_order_position   (position_id, leg, status),
    INDEX idx_trend_order_account    (account_id, account_label, status, updated_at),
    INDEX idx_trend_order_strategy   (strategy_family, strategy_name, model_name, updated_at),
    INDEX idx_trend_order_exchange   (exchange_order_id),
    INDEX idx_trend_order_created    (created_at)
    ,INDEX idx_trend_order_run       (run_id)
);

-- Shadow-only multi-asset, multi-period trend ensemble candidates. These rows are generated
-- before any live-order decision and are used to compare models, periods and symbols.
CREATE TABLE IF NOT EXISTS htx_trend_candidate_signal (
    id                   BIGINT         PRIMARY KEY AUTO_INCREMENT,
    contract_code        VARCHAR(32)    NOT NULL,
    timeframe_profile    VARCHAR(32),
    period               VARCHAR(16)    NOT NULL,
    bar_time             BIGINT,
    direction            VARCHAR(16)    NOT NULL,
    regime               VARCHAR(32)    NOT NULL,
    signal_score         DECIMAL(36,18) NOT NULL DEFAULT 0,
    long_score           DECIMAL(36,18) NOT NULL DEFAULT 0,
    short_score          DECIMAL(36,18) NOT NULL DEFAULT 0,
    score_gap            DECIMAL(36,18) NOT NULL DEFAULT 0,
    close_price          DECIMAL(36,18),
    ema_fast             DECIMAL(36,18),
    ema_medium           DECIMAL(36,18),
    ema_slow             DECIMAL(36,18),
    breakout_high        DECIMAL(36,18),
    breakout_low         DECIMAL(36,18),
    atr                  DECIMAL(36,18),
    atr_pct              DECIMAL(36,18),
    adx                  DECIMAL(36,18),
    momentum_pct         DECIMAL(36,18),
    volume_ratio         DECIMAL(36,18),
    entry_reason         VARCHAR(512),
    invalidation_reason  VARCHAR(512),
    shadow               TINYINT(1)     NOT NULL DEFAULT 1,
    model_version        VARCHAR(64)    NOT NULL,
    primary_model        VARCHAR(64),
    confirmation_models  VARCHAR(256),
    policy_version       VARCHAR(64),
    run_id               VARCHAR(128),
    factor_snapshot_id   BIGINT,
    gate_result          VARCHAR(32),
    gate_reason          VARCHAR(1024),
    execution_gate_result VARCHAR(32),
    execution_gate_reason VARCHAR(1024),
    execution_evaluated_at DATETIME(3),
    invalidation_price   DECIMAL(36,18),
    entry_hypothesis     VARCHAR(1024),
    sizing_multiplier    DECIMAL(18,8),
    planned_notional_usdt DECIMAL(36,18),
    target_zone_low      DECIMAL(36,18),
    target_zone_high     DECIMAL(36,18),
    box_measured_move_target DECIMAL(36,18),
    fib_extension_1272   DECIMAL(36,18),
    fib_extension_1618   DECIMAL(36,18),
    expected_r_multiple  DECIMAL(36,18),
    momentum_quality_score DECIMAL(36,18),
    created_at           DATETIME(3)    NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    UNIQUE KEY uk_trend_candidate_bar     (contract_code, period, bar_time, model_version),
    INDEX idx_trend_candidate_symbol_time (contract_code, period, bar_time, created_at),
    INDEX idx_trend_candidate_direction   (direction, signal_score, created_at),
    INDEX idx_trend_candidate_created     (created_at)
    ,INDEX idx_trend_candidate_run         (run_id, contract_code)
);

-- Append-only snapshots of the computed trend signal. This is the main observation surface while
-- htx.trend.observe-only=true.
CREATE TABLE IF NOT EXISTS htx_trend_signal (
    id              BIGINT         PRIMARY KEY AUTO_INCREMENT,
    contract_code   VARCHAR(32)    NOT NULL,
    period          VARCHAR(16)    NOT NULL,
    bar_time        BIGINT,
    trend_signal    VARCHAR(16)    NOT NULL,
    close_price     DECIMAL(36,18),
    ema_fast        DECIMAL(36,18),
    ema_slow        DECIMAL(36,18),
    breakout_high   DECIMAL(36,18),
    breakout_low    DECIMAL(36,18),
    atr             DECIMAL(36,18),
    atr_pct         DECIMAL(36,18),
    adx             DECIMAL(36,18),
    spread_rate     DECIMAL(36,18),
    funding_rate    DECIMAL(36,18),
    reason          VARCHAR(512),
    created_at      DATETIME(3)    NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    INDEX idx_trend_signal_contract_time (contract_code, bar_time, created_at),
    INDEX idx_trend_signal_created       (created_at)
);

-- P0 strategy research foundation; production upgrades use sql/htx/2026-09-29__htx_trend_research_foundation.sql.
-- HTX quant strategy research foundation (P0).
-- Purpose: immutable datasets, frozen candles, market-state snapshots, outcome labels and model evaluations.
-- Related feature: HTX_QUANT_STRATEGY_REMEDIATION_PLAN_V1 P0.
-- Downtime: none. Additive tables only; no existing execution table or live switch is changed.
-- Rollback: after confirming no consumer references these tables, DROP TABLE in reverse dependency order.

CREATE TABLE IF NOT EXISTS htx_trend_research_dataset (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT 'Surrogate primary key',
    dataset_key VARCHAR(160) NOT NULL COMMENT 'Immutable research dataset identity',
    source_kind VARCHAR(32) NOT NULL COMMENT 'HTX_PUBLIC_API, DERIVED_4H or other documented source',
    source_reference VARCHAR(512) NULL COMMENT 'Non-secret source or capture reference',
    captured_at DATETIME(3) NOT NULL COMMENT 'When the source was frozen',
    input_start_at DATETIME(3) NOT NULL COMMENT 'Inclusive research input start in Asia/Shanghai',
    input_end_at DATETIME(3) NOT NULL COMMENT 'Exclusive research input end in Asia/Shanghai',
    contracts_json TEXT NOT NULL COMMENT 'Frozen contract universe',
    periods_json TEXT NOT NULL COMMENT 'Frozen timeframe universe',
    input_checksum VARCHAR(128) NULL COMMENT 'Checksum over the manifest or canonical inputs',
    input_integrity VARCHAR(32) NOT NULL COMMENT 'COMPLETE, PARTIAL or INSUFFICIENT_EVIDENCE',
    status VARCHAR(32) NOT NULL COMMENT 'FREEZING, FROZEN, FAILED or RETIRED',
    purpose VARCHAR(64) NOT NULL COMMENT 'RESEARCH, VALIDATION, PAPER or INCIDENT_REVIEW',
    provenance_json TEXT NULL COMMENT 'Capture failures, derivations and source metadata',
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT 'Ledger creation time',
    PRIMARY KEY (id),
    UNIQUE KEY uk_htx_trend_research_dataset_key (dataset_key),
    KEY idx_htx_trend_research_dataset_window (input_start_at, input_end_at, status)
) COMMENT='Immutable HTX strategy research input manifest';

CREATE TABLE IF NOT EXISTS htx_trend_research_candle (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT 'Surrogate primary key',
    dataset_key VARCHAR(160) NOT NULL COMMENT 'Owning immutable research dataset',
    contract_code VARCHAR(32) NOT NULL COMMENT 'HTX linear swap contract',
    period VARCHAR(16) NOT NULL COMMENT '15min, 30min, 60min, 4hour or 1day',
    bar_time BIGINT NOT NULL COMMENT 'UTC epoch-second bar open time',
    open_price DECIMAL(36,18) NOT NULL COMMENT 'Frozen open price',
    high_price DECIMAL(36,18) NOT NULL COMMENT 'Frozen high price',
    low_price DECIMAL(36,18) NOT NULL COMMENT 'Frozen low price',
    close_price DECIMAL(36,18) NOT NULL COMMENT 'Frozen close price',
    volume DECIMAL(36,18) NULL COMMENT 'Reported volume',
    turnover DECIMAL(36,18) NULL COMMENT 'Reported turnover',
    trade_count BIGINT NULL COMMENT 'Reported trade count',
    source_kind VARCHAR(32) NOT NULL COMMENT 'RAW_PUBLIC or DERIVED_4H',
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT 'Ledger creation time',
    PRIMARY KEY (id),
    UNIQUE KEY uk_htx_trend_research_candle (dataset_key, contract_code, period, bar_time),
    KEY idx_htx_trend_research_candle_lookup (dataset_key, contract_code, period, bar_time)
) COMMENT='Frozen market candles used by strategy research only';

-- Runtime K-line evidence is deliberately separated from research datasets. It records public inputs actually
-- supplied to production calculations and keeps corrected exchange bars as content-addressed versions.
CREATE TABLE IF NOT EXISTS htx_trend_runtime_kline_candle (
    id BIGINT NOT NULL AUTO_INCREMENT,
    candle_hash CHAR(64) NOT NULL,
    contract_code VARCHAR(32) NOT NULL,
    period VARCHAR(16) NOT NULL,
    bar_time BIGINT NOT NULL,
    open_price DECIMAL(36,18), high_price DECIMAL(36,18), low_price DECIMAL(36,18), close_price DECIMAL(36,18),
    amount DECIMAL(36,18), volume DECIMAL(36,18), turnover DECIMAL(36,18), trade_count BIGINT,
    source_kind VARCHAR(32) NOT NULL, captured_at DATETIME(3) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_htx_trend_runtime_kline_candle_hash (candle_hash),
    KEY idx_htx_trend_runtime_kline_candle_lookup (contract_code, period, bar_time, captured_at)
);

CREATE TABLE IF NOT EXISTS htx_trend_runtime_kline_snapshot (
    id BIGINT NOT NULL AUTO_INCREMENT,
    snapshot_key VARCHAR(255) NOT NULL, run_id VARCHAR(128), factor_snapshot_id BIGINT, position_id BIGINT,
    capture_stage VARCHAR(32) NOT NULL, contract_code VARCHAR(32) NOT NULL, period VARCHAR(16) NOT NULL,
    target_bar_time BIGINT NOT NULL, window_start_bar_time BIGINT NOT NULL, window_end_bar_time BIGINT NOT NULL,
    expected_window_size INT NOT NULL, actual_window_size INT NOT NULL, complete_window TINYINT(1) NOT NULL,
    window_sha256 CHAR(64) NOT NULL, source_kind VARCHAR(32) NOT NULL, source_reference VARCHAR(128) NOT NULL,
    data_integrity VARCHAR(32) NOT NULL, captured_at DATETIME(3) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_htx_trend_runtime_kline_snapshot_key (snapshot_key),
    KEY idx_htx_trend_runtime_kline_snapshot_lookup (contract_code, period, target_bar_time, capture_stage),
    KEY idx_htx_trend_runtime_kline_snapshot_run (run_id, factor_snapshot_id, position_id, captured_at)
);

CREATE TABLE IF NOT EXISTS htx_trend_position_mark_snapshot (
    id BIGINT NOT NULL AUTO_INCREMENT,
    snapshot_key VARCHAR(192) NOT NULL, position_id BIGINT NOT NULL, run_id VARCHAR(128), account_id BIGINT,
    account_label VARCHAR(32) NOT NULL DEFAULT 'primary', contract_code VARCHAR(32) NOT NULL, side VARCHAR(8) NOT NULL,
    primary_model VARCHAR(64), timeframe_profile VARCHAR(32), bar_time BIGINT NOT NULL, reference_price DECIMAL(36,18) NOT NULL,
    unrealized_net_pnl DECIMAL(36,18), mfe_net_pnl DECIMAL(36,18), mae_net_pnl DECIMAL(36,18), giveback_rate DECIMAL(18,8),
    protection_price DECIMAL(36,18), trend_state VARCHAR(32), exit_phase VARCHAR(32), suggested_action VARCHAR(32),
    execution_mode VARCHAR(32) NOT NULL, evidence_json LONGTEXT, created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_htx_trend_position_mark_snapshot_key (snapshot_key),
    KEY idx_htx_trend_position_mark_position_time (position_id, bar_time),
    KEY idx_htx_trend_position_mark_run_time (run_id, created_at),
    KEY idx_htx_trend_position_mark_account_time (account_label, created_at)
) COMMENT='Append-only trend position mark-to-market evidence for replay and attribution';

CREATE TABLE IF NOT EXISTS htx_trend_market_regime_snapshot (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT 'Surrogate primary key',
    snapshot_key VARCHAR(192) NOT NULL COMMENT 'Immutable state snapshot identity',
    dataset_key VARCHAR(160) NOT NULL COMMENT 'Research dataset identity',
    scope VARCHAR(16) NOT NULL COMMENT 'MARKET or CONTRACT',
    contract_code VARCHAR(32) NULL COMMENT 'Null for market-wide state',
    bar_time BIGINT NOT NULL COMMENT 'State observation bar time',
    state_code VARCHAR(32) NOT NULL COMMENT 'TREND_UP, TREND_DOWN, PULLBACK, RANGE, VOLATILE_DISORDER or CROWDED',
    daily_direction VARCHAR(16) NULL COMMENT 'Completed 1d direction',
    four_hour_direction VARCHAR(16) NULL COMMENT 'Completed 4h direction',
    one_hour_direction VARCHAR(16) NULL COMMENT 'Completed 1h direction',
    breadth_ratio DECIMAL(18,8) NULL COMMENT 'Directional market breadth ratio',
    volatility_score DECIMAL(18,8) NULL COMMENT 'Normalized volatility state score',
    crowding_score DECIMAL(18,8) NULL COMMENT 'Funding/OI crowding score when available',
    evidence_json TEXT NOT NULL COMMENT 'Immutable state evidence and unavailable inputs',
    data_integrity VARCHAR(32) NOT NULL COMMENT 'COMPLETE, PARTIAL or INSUFFICIENT_EVIDENCE',
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT 'Ledger creation time',
    PRIMARY KEY (id),
    UNIQUE KEY uk_htx_trend_market_regime_snapshot_key (snapshot_key),
    KEY idx_htx_trend_market_regime_lookup (dataset_key, contract_code, bar_time, state_code)
) COMMENT='Immutable market classification evidence for research and replay';

CREATE TABLE IF NOT EXISTS htx_trend_research_label (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT 'Surrogate primary key',
    label_key VARCHAR(192) NOT NULL COMMENT 'Immutable label identity',
    dataset_key VARCHAR(160) NOT NULL COMMENT 'Research dataset identity',
    source_candidate_id BIGINT NULL COMMENT 'Optional live candidate source; null for regenerated candidate',
    source_run_id VARCHAR(128) NULL COMMENT 'Optional live run identity',
    model_code VARCHAR(64) NOT NULL COMMENT 'Independent alpha model',
    model_version VARCHAR(64) NOT NULL COMMENT 'Versioned model rules',
    contract_code VARCHAR(32) NOT NULL COMMENT 'HTX linear swap contract',
    side VARCHAR(16) NOT NULL COMMENT 'LONG or SHORT',
    timeframe_profile VARCHAR(32) NOT NULL COMMENT 'INTRADAY_15M, SWING_4H or other profile',
    entry_bar_time BIGINT NOT NULL COMMENT 'Completed signal bar time',
    entry_price DECIMAL(36,18) NOT NULL COMMENT 'Assumed or actual entry price',
    stop_price DECIMAL(36,18) NOT NULL COMMENT 'Predefined initial loss boundary',
    target_one_r_price DECIMAL(36,18) NULL COMMENT 'Predefined 1R target',
    target_two_r_price DECIMAL(36,18) NULL COMMENT 'Predefined 2R target',
    horizon_bar_time BIGINT NOT NULL COMMENT 'Last bar allowed by the label horizon',
    first_barrier VARCHAR(32) NOT NULL COMMENT 'TARGET_1R, TARGET_2R, STOP, TIMEOUT or UNAVAILABLE',
    first_barrier_time BIGINT NULL COMMENT 'When the first deterministic barrier occurred',
    mfe DECIMAL(36,18) NULL COMMENT 'Maximum favorable excursion in price terms',
    mae DECIMAL(36,18) NULL COMMENT 'Maximum adverse excursion in price terms',
    estimated_entry_fee DECIMAL(36,18) NULL COMMENT 'Entry fee used by research',
    estimated_exit_fee DECIMAL(36,18) NULL COMMENT 'Exit fee used by research',
    net_profit DECIMAL(36,18) NULL COMMENT 'Net result under declared label rules',
    ambiguity_reason VARCHAR(512) NULL COMMENT 'Same-bar ordering or other ambiguity',
    data_integrity VARCHAR(32) NOT NULL COMMENT 'COMPLETE, PARTIAL or INSUFFICIENT_EVIDENCE',
    evidence_json TEXT NOT NULL COMMENT 'Frozen barriers, cost assumptions and evidence',
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT 'Ledger creation time',
    PRIMARY KEY (id),
    UNIQUE KEY uk_htx_trend_research_label_key (label_key),
    KEY idx_htx_trend_research_label_model (dataset_key, model_code, timeframe_profile, entry_bar_time),
    KEY idx_htx_trend_research_label_contract (contract_code, side, entry_bar_time)
) COMMENT='Immutable target-before-stop and excursion labels for research candidates';

CREATE TABLE IF NOT EXISTS htx_trend_model_evaluation (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT 'Surrogate primary key',
    evaluation_key VARCHAR(192) NOT NULL COMMENT 'Immutable evaluation identity',
    dataset_key VARCHAR(160) NOT NULL COMMENT 'Research dataset identity',
    research_version VARCHAR(64) NOT NULL COMMENT 'Backtest/research engine version',
    sample_split VARCHAR(32) NOT NULL COMMENT 'TRAIN, VALIDATION, OUT_OF_SAMPLE or WALK_FORWARD',
    model_code VARCHAR(64) NOT NULL COMMENT 'Independent alpha model',
    model_version VARCHAR(64) NOT NULL COMMENT 'Versioned model rules',
    direction VARCHAR(16) NULL COMMENT 'Optional LONG or SHORT bucket',
    timeframe_profile VARCHAR(32) NULL COMMENT 'Optional period profile bucket',
    market_state VARCHAR(32) NULL COMMENT 'Optional market-state bucket',
    symbol_bucket VARCHAR(64) NULL COMMENT 'Liquidity or asset-group bucket',
    trade_count BIGINT NOT NULL COMMENT 'Comparable completed trade count',
    win_count BIGINT NOT NULL COMMENT 'Net-positive completed trade count',
    loss_count BIGINT NOT NULL COMMENT 'Net-negative completed trade count',
    net_profit DECIMAL(36,18) NULL COMMENT 'Aggregate net PnL after declared costs',
    profit_factor DECIMAL(36,18) NULL COMMENT 'Gross profit divided by gross loss',
    expectancy DECIMAL(36,18) NULL COMMENT 'Average net PnL per trade',
    max_drawdown DECIMAL(36,18) NULL COMMENT 'Declared replay maximum drawdown',
    fee_total DECIMAL(36,18) NULL COMMENT 'Aggregate estimated or actual fees',
    decision VARCHAR(32) NOT NULL COMMENT 'PAPER_CANDIDATE, REJECT or INSUFFICIENT_SAMPLE',
    evidence_json TEXT NOT NULL COMMENT 'Metric definitions, thresholds and exclusions',
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT 'Ledger creation time',
    PRIMARY KEY (id),
    UNIQUE KEY uk_htx_trend_model_evaluation_key (evaluation_key),
    KEY idx_htx_trend_model_evaluation_lookup (dataset_key, model_code, sample_split, decision)
) COMMENT='Versioned independent model evaluation ledger';
