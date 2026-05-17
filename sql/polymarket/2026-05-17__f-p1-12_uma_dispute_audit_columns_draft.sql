-- =================================================================
-- Batch diff: 2026-05-17 F-P1-12 UMA 争议审计字段 (DDL 草案)
--
-- 关联计划:
--   docs/polymarket/minimal-implementations-fix-plan.md
--     F-P1-12 接 Gamma + UMA API → pm_resolution_audit 新增 UMA 争议列
--
-- 目的:
--   * 在 pm_resolution_audit 表追加 uma_disputed / uma_dispute_observed_at 两列;
--   * 服务侧 (PolymarketApiClient.MarketMetadata.umaDisputed → ResolutionAuditRecord →
--     ResolutionAuditPersistenceService.persist) 已经能写入这两列, 但 entity 上
--     @TableField(exist = false) 注解默认关掉了 MyBatis-Plus 的 SELECT / UPDATE;
--   * 待 DBA 执行本 DDL 后, 需要在 PmResolutionAuditEntity 中删除两个字段的
--     @TableField(exist = false) 注解, 让 entity 真正参与 SQL.
--
-- 状态: 草案 (DRAFT) ── 仅记录目标 schema, 未在任一环境执行.
--
-- 上线 Checklist:
--   - [ ] test 环境 DBA 执行本 DDL (CREATE COLUMN IF NOT EXISTS 风格, 重复执行无害)
--   - [ ] 删除 PmResolutionAuditEntity#umaDisputed / #umaDisputeObservedAt 上的
--          @TableField(exist = false) 注解
--   - [ ] test 灰度观察 7d, ResolutionTriDiffService 报表中 umaDisputed 计数符合预期
--   - [ ] staging / prod DBA 依次执行同一 DDL + 删注解 release
--
-- 一致性说明:
--   * 列名遵循 snake_case (uma_disputed / uma_dispute_observed_at), 与 entity 字段
--     默认转换规则一致, 不需要额外 @TableField name 映射.
--   * uma_disputed 默认 false, 避免历史数据 NULL 引发 Boolean.TRUE.equals 判定混乱.
--   * uma_dispute_observed_at 默认 NULL, 仅在 disputed=true 那次观察时被填充;
--     注意此字段是"每次观察"的快照, 不是"全局首次", 全局首次需要由 TriDiffService
--     按 marketId 聚合 min(observedAt) 得出.
-- =================================================================

-- pm_resolution_audit: 追加 UMA 争议列
ALTER TABLE pm_resolution_audit
    ADD COLUMN IF NOT EXISTS uma_disputed TINYINT(1) DEFAULT 0
        COMMENT 'F-P1-12: UMA Oracle 争议中标记 (true 时即便 gammaClosed 也不应判 OURS_VS_GAMMA)';

ALTER TABLE pm_resolution_audit
    ADD COLUMN IF NOT EXISTS uma_dispute_observed_at DATETIME NULL
        COMMENT 'F-P1-12: 本次观察到争议时的时间戳 (用于 audit 反查"该 marketId 自何时进入争议")';

-- 索引: 让 ResolutionTriDiffService 按 (uma_disputed, observed_at) 快速过滤新增样本.
-- 注: 若 MySQL 版本不支持 IF NOT EXISTS for INDEX, 可改用 procedure 包一层.
CREATE INDEX IF NOT EXISTS idx_pm_resolution_audit_uma_disputed
    ON pm_resolution_audit (uma_disputed, observed_at);

-- =================================================================
-- 验收 SQL (DBA 执行后人工 sanity check):
-- =================================================================
-- 1. 确认列存在:
--    SHOW COLUMNS FROM pm_resolution_audit LIKE 'uma_disputed%';
-- 2. 抽样观察:
--    SELECT market_id, observed_at, resolution_status, uma_resolution_status,
--           uma_resolved, uma_disputed, uma_dispute_observed_at
--      FROM pm_resolution_audit
--     WHERE observed_at > NOW() - INTERVAL 1 DAY
--     ORDER BY observed_at DESC
--     LIMIT 50;
-- 3. 确认 ResolutionTriDiffService 报表中 umaDisputed > 0 (依赖 Gamma 上游真的有
--    disputed=true 的市场, 灰度初期可能为 0 — 这是正常的, 不算 bug).
