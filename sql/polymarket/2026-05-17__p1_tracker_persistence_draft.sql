-- =================================================================
-- Batch diff: 2026-05-17 P1 跟单订单超时跟踪持久化 (DDL 草案)
--
-- 关联计划:
--   docs/polymarket/minimal-implementations-fix-plan.md
--     F-P1-3 LIVE GTC 全局超时取消 → pending_order_timeout_tracker
--     F-P1-4 MAKER_ONLY tracker 落库 → pending_maker_only_tracker
--
-- 状态: 草案 (DRAFT) ── 仅记录目标 schema，未在任一环境执行；
--       上线前需配套服务侧迁移 (持久化 + 重启恢复)。
--
-- 2026-05-17 第三轮调度更新: F-P1-3 服务侧已落地
--   * pending_order_timeout_tracker 服务侧已实现 (GtcOrderTimeoutTracker + Mapper +
--     启动期回放 + write-through 持久化); 仍需 DBA 在各环境执行本 DDL 后再启用
--     `smart-wallet.execution-wallet.enable-gtc-global-timeout=true`.
--   * pending_maker_only_tracker 仍未实现服务侧 write-through (列入下一轮 F-P1-4).
--
-- 2026-05-17 第四轮调度更新: F-P1-4 服务侧已落地
--   * pending_maker_only_tracker 服务侧已实现 (PendingMakerOnlyTrackerEntity +
--     PendingMakerOnlyTrackerMapper, 接入 MakerOnlyOrderTimeoutTracker 的 register / scan
--     / unregister, 增加 @PostConstruct replayPendingFromDb()); 共用
--     `execution-wallet.enable-pending-tracker-persistence` 持久化开关; 上线仍需 DBA
--     在各环境执行本 DDL.
-- 维护者: 跟单策略 owner
-- =================================================================


-- ─────────────────────────────────────────────────────────────────
-- F-P1-3: LIVE GTC 全局超时取消持久化
-- ─────────────────────────────────────────────────────────────────
-- 用途：所有 GTC 单（含 MAKER / TAKER / MAKER_ONLY）落入此表，
--       由 `GtcOrderTimeoutTracker` 在 `gtc-default-timeout-seconds` 到期时取消；
--       重启后从表恢复 in-flight tracker。
CREATE TABLE IF NOT EXISTS pending_order_timeout_tracker (
    id              BIGINT       NOT NULL AUTO_INCREMENT COMMENT 'pk',
    order_id        BIGINT       NOT NULL                COMMENT 'smart_wallet_order.id',
    external_order_id VARCHAR(128) DEFAULT NULL          COMMENT 'CLOB 远端 order id',
    follow_task_id  BIGINT       DEFAULT NULL            COMMENT 'smart_wallet_follow_task.id (nullable for exits)',
    market_id       VARCHAR(128) DEFAULT NULL            COMMENT 'Polymarket conditionId',
    outcome         VARCHAR(32)  DEFAULT NULL            COMMENT 'YES / NO',
    order_strategy  VARCHAR(32)  DEFAULT NULL            COMMENT 'TAKER / MAKER / MAKER_ONLY / MAKER_FIRST',
    expire_at       DATETIME     NOT NULL                COMMENT '超时时间点',
    cancel_status   VARCHAR(32)  NOT NULL DEFAULT 'PENDING'
                                                         COMMENT 'PENDING / CANCELLED / CANCEL_FAILED / SUPERSEDED',
    cancel_attempts INT          NOT NULL DEFAULT 0      COMMENT '已重试取消次数',
    last_attempt_at DATETIME     DEFAULT NULL,
    cancel_error    VARCHAR(512) DEFAULT NULL            COMMENT '最近一次取消失败错误信息',
    created_at      DATETIME     NOT NULL,
    updated_at      DATETIME     NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_pending_order_timeout_order (order_id),
    KEY idx_pending_order_timeout_expire (cancel_status, expire_at),
    KEY idx_pending_order_timeout_market (market_id, outcome)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT 'F-P1-3: GTC 订单全局超时取消跟踪';


-- ─────────────────────────────────────────────────────────────────
-- F-P1-4: MAKER_ONLY tracker 持久化
-- ─────────────────────────────────────────────────────────────────
-- 用途：MAKER_ONLY / MAKER_FIRST 单子的 maker-only 超时（≈10s）由独立 tracker 管理，
--       目前是 in-memory，重启就丢；本表为重启恢复用。
CREATE TABLE IF NOT EXISTS pending_maker_only_tracker (
    id              BIGINT       NOT NULL AUTO_INCREMENT COMMENT 'pk',
    order_id        BIGINT       NOT NULL                COMMENT 'smart_wallet_order.id',
    external_order_id VARCHAR(128) DEFAULT NULL          COMMENT 'CLOB 远端 order id',
    follow_task_id  BIGINT       DEFAULT NULL,
    market_id       VARCHAR(128) DEFAULT NULL,
    outcome         VARCHAR(32)  DEFAULT NULL,
    category        VARCHAR(32)  DEFAULT NULL            COMMENT '品类，用于按品类分桶超时配置（F-P2-14）',
    maker_strategy  VARCHAR(32)  DEFAULT NULL            COMMENT 'MAKER_ONLY / MAKER_FIRST',
    placed_at       DATETIME     NOT NULL                COMMENT '挂单时间',
    expire_at       DATETIME     NOT NULL                COMMENT 'maker-only-timeout-seconds 到期点',
    fallback_action VARCHAR(32)  NOT NULL DEFAULT 'CANCEL'
                                                         COMMENT 'CANCEL / PROMOTE_TO_TAKER / DOWNGRADE_PAPER',
    status          VARCHAR(32)  NOT NULL DEFAULT 'PENDING'
                                                         COMMENT 'PENDING / RESOLVED / EXPIRED / FAILED',
    resolved_at     DATETIME     DEFAULT NULL,
    resolved_reason VARCHAR(256) DEFAULT NULL,
    created_at      DATETIME     NOT NULL,
    updated_at      DATETIME     NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_pending_maker_only_order (order_id),
    KEY idx_pending_maker_only_expire (status, expire_at),
    KEY idx_pending_maker_only_category (category, maker_strategy)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT 'F-P1-4: MAKER_ONLY 订单超时 tracker 持久化';


-- ─────────────────────────────────────────────────────────────────
-- 上线 checklist (服务侧)
-- ─────────────────────────────────────────────────────────────────
-- [x] 1. `PendingOrderTimeoutTrackerMapper` 已落地 (第三轮)
-- [x] 1b. `PendingMakerOnlyTrackerMapper` 已落地 (第四轮 F-P1-4)
-- [x] 2. `MakerOnlyOrderTimeoutTracker` 改为：内存索引 + 表持久化 (write-through) (第四轮)
-- [x] 3a. 启动期回放：`GtcOrderTimeoutTracker.replayPendingFromDb` 扫 cancel_status=PENDING (第三轮)
-- [x] 3b. `MakerOnlyOrderTimeoutTracker.replayPendingFromDb` 启动期回放 (第四轮)
-- [x] 4. 配置开关 `execution-wallet.enable-gtc-global-timeout=false` (默认 OFF，灰度后再开) 已注册
-- [x] 5. 配置开关 `execution-wallet.enable-pending-tracker-persistence=true` 已注册
-- [ ] 6. 配套监控：tracker 表行数、cancel_attempts 分布、cancel_failed 比例告警
-- [ ] 7. DBA 在 test / staging / prod 执行本 DDL（两表 idempotent CREATE IF NOT EXISTS）
