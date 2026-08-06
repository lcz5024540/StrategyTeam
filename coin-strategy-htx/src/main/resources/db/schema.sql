-- htx_trade_strategy 专用库。买/卖 round-trip、写前订单、撤单、单腿裸露死信。
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
