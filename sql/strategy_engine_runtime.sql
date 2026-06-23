-- Strategy Engine Runtime State Tables
-- 用于把 strategy-engine 模块的内存状态下沉到 DB。
-- 包含三张表：
--   strategy_engine_flag    —— 引擎级标志位（如 kill_switch）
--   strategy_manual_trigger —— 异步命令队列（手动 run-cycle / exit-scan / alert-check / snapshot-collect）
--   strategy_registry_state —— 策略注册快照（由 job 端定期 sync）

CREATE TABLE IF NOT EXISTS strategy_engine_flag (
    id BIGINT NOT NULL AUTO_INCREMENT,
    flag_key VARCHAR(64) NOT NULL,
    flag_value VARCHAR(64),
    reason VARCHAR(512),
    activated_at DATETIME,
    activated_by VARCHAR(64),
    updated_at DATETIME NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_flag_key (flag_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='策略引擎全局标志位（kill_switch 等）';

CREATE TABLE IF NOT EXISTS strategy_manual_trigger (
    id BIGINT NOT NULL AUTO_INCREMENT,
    strategy_id VARCHAR(64) NOT NULL,
    action VARCHAR(32) NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    requested_at DATETIME NOT NULL,
    requested_by VARCHAR(64),
    processed_at DATETIME,
    completed_at DATETIME,
    success TINYINT(1),
    payload TEXT,
    message TEXT,
    result_json MEDIUMTEXT,
    PRIMARY KEY (id),
    KEY idx_status_requested (status, requested_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='策略手动触发命令队列（异步执行）';

CREATE TABLE IF NOT EXISTS strategy_registry_state (
    id BIGINT NOT NULL AUTO_INCREMENT,
    strategy_id VARCHAR(64) NOT NULL,
    display_name VARCHAR(128),
    enabled TINYINT(1) NOT NULL DEFAULT 0,
    mode VARCHAR(32) NOT NULL,
    wallet_id VARCHAR(64),
    updated_at DATETIME NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_strategy_id (strategy_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='策略注册状态快照（job 端定期 UPSERT）';
