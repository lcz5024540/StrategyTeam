-- =============================================================================
-- 文件：2026-05-28__fix_phantom_orders.sql
-- 目的：修复 `smart_wallet_trade_order` 中 33 笔幻影/异常订单与 3 行未来日期的
--       `smart_wallet_daily_report`，是 [问题记录事项-待解决-2026-05-28.md] P2-2 的对应数据修复脚本。
-- 关联：docs/problem/问题记录事项-待解决-2026-05-28.md §3.2、§ P2-2、§ P1-2
-- 作者：自动巡检（scheduled task `1-job`） + 待 DBA 复核
-- 状态：草稿 / 待人工复核 / 仅在 test 环境运行（生产需先回归）
--
-- ‼️ 执行前必读：
--   1. 本脚本仅做"修复明显错误"（设置 NULL / 重新计算 `roi_rate` / 删除 3 行
--      未来日期 daily_report），不重写 `total_pnl_usdc` —— 因为真实结算价缺失，
--      正确的 PnL 重写必须通过应用侧的结算 Job 拉取实际市场价后回写。
--   2. 全程在事务内执行；先做 `SELECT * INTO 备份表`，确认无误再 `COMMIT`。
--   3. 受影响的订单（33 行）多数 `status='SETTLED'` 且 `remaining_cost_basis_usdc=0`，
--      仅 `settled_at` 与 `roi_rate` 异常；本脚本把它们打回"待结算"状态：
--      `settled_at = NULL`、`settlement_price = NULL`、`settlement_*usdc = 0`、
--      `total_realized_pnl_usdc = total_pnl_usdc - settlement_pnl_usdc`（保持累计净额不变），
--      由应用侧结算 Job 在下次轮询时按真实市场价重新结算。
--   4. 7 笔 `roi_rate > 5` 中含 2 笔与 reversed 重叠（id=598、642）+ 1 笔与 future 重叠（id=610），
--      重叠 3 笔会被"修 settled_at"逻辑顺带处理；剩余 4 笔（id=597 / 616 / 622 / 661）
--      只重算 `roi_rate = total_pnl_usdc / entry_cost_usdc`（让字段口径一致，
--      真实 PnL 仍需结算 Job 修正）。
--   5. `smart_wallet_daily_report` 3 行未来日期（id=41 / 46 / 48）创建于 05-19 ~ 05-24，
--      `total_orders=0` 全部为脏行，直接 DELETE。
--
-- 数据快照（2026-05-28 09:11–09:14 CST 巡检实测）：
--   smart_wallet_trade_order: 总 176，按异常口径 33 行
--     ├─ settled_at > NOW()：5 行（id=581, 590, 605, 610, 612）
--     ├─ settled_at < opened_at：24 行（id=598, 599, 601~604, 608, 609, 615, 620,
--     │                                  623~625, 631, 633~635, 637, 639, 642,
--     │                                  644~647）
--     └─ roi_rate > 5：7 行（id=597, 598, 610, 616, 622, 642, 661）
--        ├─ 与 future_settled 重叠：id=610（1 行）
--        └─ 与 reversed_settle 重叠：id=598、642（2 行）
--   smart_wallet_daily_report 未来日期 3 行：id=41 (2026-06-30), id=46 (2026-05-29), id=48 (2026-05-31)
-- =============================================================================

-- -----------------------------------------------------------------------------
-- 第 0 步：连通性 + 字段存在性自检（不修改任何数据）
-- -----------------------------------------------------------------------------

SELECT NOW() AS run_at,
       DATABASE() AS db_name,
       VERSION()  AS mysql_version,
       @@autocommit AS autocommit_flag,
       @@transaction_isolation AS iso_level;

-- 受影响行数预估，若实际行数与下方注释 "33 / 3" 不一致，请先停下来核对
SELECT 'trade_order_anomalies' AS bucket,
       COUNT(*) AS expect_33,
       SUM(CASE WHEN settled_at > NOW() THEN 1 ELSE 0 END) AS expect_5_future,
       SUM(CASE WHEN settled_at IS NOT NULL AND settled_at < opened_at THEN 1 ELSE 0 END) AS expect_24_reversed,
       SUM(CASE WHEN roi_rate > 5 THEN 1 ELSE 0 END) AS expect_7_high_roi
FROM smart_wallet_trade_order
WHERE settled_at > NOW()
   OR (settled_at IS NOT NULL AND settled_at < opened_at)
   OR roi_rate > 5;

SELECT 'daily_report_future' AS bucket,
       COUNT(*) AS expect_3,
       GROUP_CONCAT(id ORDER BY id) AS ids
FROM smart_wallet_daily_report
WHERE report_date > CURDATE();


-- -----------------------------------------------------------------------------
-- 第 1 步：备份（每次执行都新建一张带时间戳的备份表，幂等可重跑）
-- -----------------------------------------------------------------------------

DROP TABLE IF EXISTS smart_wallet_trade_order_bak_20260528_phantom;
CREATE TABLE smart_wallet_trade_order_bak_20260528_phantom AS
SELECT *
FROM smart_wallet_trade_order
WHERE settled_at > NOW()
   OR (settled_at IS NOT NULL AND settled_at < opened_at)
   OR roi_rate > 5;

DROP TABLE IF EXISTS smart_wallet_daily_report_bak_20260528_future;
CREATE TABLE smart_wallet_daily_report_bak_20260528_future AS
SELECT *
FROM smart_wallet_daily_report
WHERE report_date > CURDATE();

-- 备份计数核对：应得 33 / 3
SELECT 'bak_trade_order' AS tbl, COUNT(*) AS rows_backed_up FROM smart_wallet_trade_order_bak_20260528_phantom
UNION ALL
SELECT 'bak_daily_report', COUNT(*) FROM smart_wallet_daily_report_bak_20260528_future;


-- -----------------------------------------------------------------------------
-- 第 2 步：进入事务（仅当上面 33 / 3 与预期一致时，才执行本步往后）
-- -----------------------------------------------------------------------------

START TRANSACTION;


-- -----------------------------------------------------------------------------
-- 第 2.1 步：修 5 笔 `settled_at > NOW()`
--   策略：把 `settled_at`、`settlement_price`、`settlement_*usdc` 清零；
--         保持 `status`、`total_pnl_usdc`、`exit_*` 不动（这些往往来自正常的
--         平仓动作，只是结算时戳被错误地写成了市场名义到期时间）；
--         并把"settlement 部分的 PnL"从 `total_realized_pnl_usdc` 中扣除（如果它
--         之前被加进去），让累计净额口径与重置后的 settlement 字段一致。
-- -----------------------------------------------------------------------------

UPDATE smart_wallet_trade_order
SET total_realized_pnl_usdc = total_realized_pnl_usdc - settlement_pnl_usdc,
    settlement_pnl_usdc      = 0,
    settlement_proceeds_usdc = 0,
    settlement_price         = NULL,
    settled_at               = NULL,
    updated_at               = NOW()
WHERE id IN (581, 590, 605, 610, 612)
  AND settled_at > NOW();   -- 二次保险：避免在重跑时把已经修好的行再改一次


-- -----------------------------------------------------------------------------
-- 第 2.2 步：修 24 笔 `settled_at < opened_at`
--   同上策略：清结算字段，让结算 Job 重新跑。
-- -----------------------------------------------------------------------------

UPDATE smart_wallet_trade_order
SET total_realized_pnl_usdc = total_realized_pnl_usdc - settlement_pnl_usdc,
    settlement_pnl_usdc      = 0,
    settlement_proceeds_usdc = 0,
    settlement_price         = NULL,
    settled_at               = NULL,
    updated_at               = NOW()
WHERE id IN (598, 599, 601, 602, 603, 604,
             608, 609, 615, 620,
             623, 624, 625,
             631, 633, 634, 635, 637, 639,
             642, 644, 645, 646, 647)
  AND settled_at IS NOT NULL
  AND settled_at < opened_at;


-- -----------------------------------------------------------------------------
-- 第 2.3 步：修 7 笔 `roi_rate > 5`
--   其中 id=610（future）、id=598、642（reversed）已在 2.1 / 2.2 被打回
--   "待结算"，无需再处理；剩余 4 笔（id=597 / 616 / 622 / 661）`settled_at` 本身
--   合理（在过去且晚于 `opened_at`），只是 `total_pnl_usdc` 因 PAPER $1.00 全额
--   估值导致 `roi_rate` 虚高。本节仅把 `roi_rate` 重算为
--   `total_pnl_usdc / entry_cost_usdc`，让字段口径一致；真实 PnL 需要应用侧用
--   真实市场价重新结算（不在本脚本范围）。
-- -----------------------------------------------------------------------------

UPDATE smart_wallet_trade_order
SET roi_rate   = CASE WHEN entry_cost_usdc > 0
                        THEN total_pnl_usdc / entry_cost_usdc
                      ELSE 0 END,
    updated_at = NOW()
WHERE id IN (597, 616, 622, 661)
  AND roi_rate > 5;


-- -----------------------------------------------------------------------------
-- 第 2.4 步：删除 3 行未来日期 `smart_wallet_daily_report`
--   id=41 (2026-06-30, 2026-05-19 创建)、id=46 (2026-05-29, 2026-05-22 创建)、
--   id=48 (2026-05-31, 2026-05-24 创建)，全部 `total_orders=0` 为脏行。
-- -----------------------------------------------------------------------------

DELETE FROM smart_wallet_daily_report
WHERE report_date > CURDATE()
  AND id IN (41, 46, 48);


-- -----------------------------------------------------------------------------
-- 第 3 步：事务后校验（必须在 COMMIT 之前看一眼）
--   预期：
--     - anomalies_after = 0（所有 33 行已修复 / 仅剩 settled_at IS NULL 状态）
--     - daily_report_future_after = 0
--     - 受影响订单的 status 仍为 SETTLED，但 settled_at=NULL，等待重结算
-- -----------------------------------------------------------------------------

SELECT 'anomalies_after' AS check_name,
       COUNT(*) AS expect_0
FROM smart_wallet_trade_order
WHERE settled_at > NOW()
   OR (settled_at IS NOT NULL AND settled_at < opened_at)
   OR roi_rate > 5;

SELECT 'daily_report_future_after' AS check_name,
       COUNT(*) AS expect_0
FROM smart_wallet_daily_report
WHERE report_date > CURDATE();

SELECT 'updated_order_state' AS check_name,
       id, status, settled_at, settlement_price,
       ROUND(settlement_pnl_usdc,2) AS settle_pnl,
       ROUND(total_pnl_usdc,2)      AS total_pnl,
       ROUND(roi_rate,4)            AS roi
FROM smart_wallet_trade_order
WHERE id IN (581, 590, 597, 598, 599, 601, 602, 603, 604,
             605, 608, 609, 610, 612, 615, 616, 620, 622,
             623, 624, 625, 631, 633, 634, 635, 637, 639,
             642, 644, 645, 646, 647, 661)
ORDER BY id;


-- -----------------------------------------------------------------------------
-- 第 4 步：提交（或回滚）
--   ⚠️ 只有当第 3 步的两个 expect_0 都为 0 时才执行 COMMIT。
--   ⚠️ 否则执行 ROLLBACK 并把第 3 步的输出截图发给 DBA 复核。
-- -----------------------------------------------------------------------------

-- COMMIT;
-- ROLLBACK;


-- =============================================================================
-- 第 5 步：回滚预案（如果 COMMIT 后发现问题，从备份表恢复）
-- =============================================================================

-- 回滚 smart_wallet_trade_order（按 id 反向 UPDATE 回原始值）
-- START TRANSACTION;
-- UPDATE smart_wallet_trade_order tgt
-- JOIN  smart_wallet_trade_order_bak_20260528_phantom src ON src.id = tgt.id
-- SET   tgt.settlement_pnl_usdc      = src.settlement_pnl_usdc,
--       tgt.settlement_proceeds_usdc = src.settlement_proceeds_usdc,
--       tgt.settlement_price         = src.settlement_price,
--       tgt.settled_at               = src.settled_at,
--       tgt.roi_rate                 = src.roi_rate,
--       tgt.total_realized_pnl_usdc  = src.total_realized_pnl_usdc,
--       tgt.updated_at               = NOW();
-- COMMIT;

-- 回滚 smart_wallet_daily_report（重新插回 3 行）
-- START TRANSACTION;
-- INSERT INTO smart_wallet_daily_report
-- SELECT * FROM smart_wallet_daily_report_bak_20260528_future;
-- COMMIT;


-- =============================================================================
-- 后续行动（不在本脚本范围）：
--   1. 触发应用侧 `SettlementJob` 对 settled_at=NULL 且 status=SETTLED 的订单
--      重新拉真实市场价并回写 `settled_at` / `settlement_price` / `settlement_pnl_usdc`
--   2. 在 `SmartWalletTradeOrderService` 写入入口添加防御：
--        assert settled_at IS NULL OR settled_at >= opened_at
--      并在违例时抛出 InvalidStateException 而非默默写入
--   3. 对 `roi_rate` 增加 CHECK 约束（MySQL 8.0.16+ 支持）：
--        ALTER TABLE smart_wallet_trade_order
--        ADD CONSTRAINT chk_roi_rate_range CHECK (roi_rate BETWEEN -1.0 AND 5.0);
--   4. 在 `smart_wallet_daily_report` 写入入口添加防御：
--        assert report_date <= CURDATE()
-- =============================================================================
