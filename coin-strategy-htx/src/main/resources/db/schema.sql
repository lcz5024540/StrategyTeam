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
    client_order_id      VARCHAR(64),
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
    position_key             VARCHAR(128)   NOT NULL UNIQUE,
    contract_code            VARCHAR(32)    NOT NULL,
    status                   VARCHAR(32)    NOT NULL,
    side                     VARCHAR(8)     NOT NULL,
    contract_size            DECIMAL(36,18) NOT NULL,
    contract_volume          BIGINT         NOT NULL,
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
    stop_price               DECIMAL(36,18),
    trailing_stop_price      DECIMAL(36,18),
    take_profit_price        DECIMAL(36,18),
    highest_price            DECIMAL(36,18),
    lowest_price             DECIMAL(36,18),
    entry_signal             VARCHAR(512),
    exit_reason              VARCHAR(512),
    message                  VARCHAR(512),
    account_label            VARCHAR(32)    NOT NULL DEFAULT 'primary',
    entry_fee_rate           DECIMAL(36,18),
    estimated_entry_fee      DECIMAL(36,18),
    estimated_close_fee      DECIMAL(36,18),
    expected_net_after_fees  DECIMAL(36,18),
    sizing_multiplier        DECIMAL(18,8),
    sizing_reason            VARCHAR(512),
    entry_gate_reason        VARCHAR(1024),
    created_at               DATETIME(3)    NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at               DATETIME(3)    NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    INDEX idx_trend_position_contract (contract_code, status),
    INDEX idx_trend_position_account  (account_label, status, updated_at),
    INDEX idx_trend_position_entry    (entry_order_id),
    INDEX idx_trend_position_close    (close_order_id)
);

-- Contract-order audit trail for HTX trend following. A row is inserted before each
-- live order send; if this insert fails, the strategy refuses to send the order.
CREATE TABLE IF NOT EXISTS htx_trend_order (
    id                       BIGINT         PRIMARY KEY AUTO_INCREMENT,
    position_id              BIGINT         NOT NULL,
    position_key             VARCHAR(128)   NOT NULL,
    contract_code            VARCHAR(32)    NOT NULL,
    leg                      VARCHAR(16)    NOT NULL,
    trend_side               VARCHAR(8)     NOT NULL,
    order_side               VARCHAR(8)     NOT NULL,
    position_side            VARCHAR(8)     NOT NULL,
    reduce_only              TINYINT(1)     NOT NULL DEFAULT 0,
    client_order_id          VARCHAR(64)    NOT NULL,
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
    account_label            VARCHAR(32)    NOT NULL DEFAULT 'primary',
    fee_rate_used            DECIMAL(36,18),
    estimated_fee            DECIMAL(36,18),
    estimated_open_fee       DECIMAL(36,18),
    estimated_close_fee      DECIMAL(36,18),
    sizing_multiplier        DECIMAL(18,8),
    sizing_reason            VARCHAR(512),
    entry_gate_reason        VARCHAR(1024),
    created_at               DATETIME(3)    NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at               DATETIME(3)    NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    UNIQUE KEY uk_trend_order_client (client_order_id),
    INDEX idx_trend_order_position   (position_id, leg, status),
    INDEX idx_trend_order_account    (account_label, status, updated_at),
    INDEX idx_trend_order_exchange   (exchange_order_id),
    INDEX idx_trend_order_created    (created_at)
);

-- Shadow-only multi-asset, multi-period trend ensemble candidates. These rows are generated
-- before any live-order decision and are used to compare models, periods and symbols.
CREATE TABLE IF NOT EXISTS htx_trend_candidate_signal (
    id                   BIGINT         PRIMARY KEY AUTO_INCREMENT,
    contract_code        VARCHAR(32)    NOT NULL,
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
    created_at           DATETIME(3)    NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    UNIQUE KEY uk_trend_candidate_bar     (contract_code, period, bar_time, model_version),
    INDEX idx_trend_candidate_symbol_time (contract_code, period, bar_time, created_at),
    INDEX idx_trend_candidate_direction   (direction, signal_score, created_at),
    INDEX idx_trend_candidate_created     (created_at)
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
