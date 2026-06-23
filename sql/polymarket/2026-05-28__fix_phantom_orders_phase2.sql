-- =============================================================================
-- 文件：2026-05-28__fix_phantom_orders_phase2.sql
-- 目的：补做 Phase 1 漏掉的下游字段修复——
--       (1) 把 29 笔已清 `settled_at` 的订单的 PnL / ROI / last_mark 也清零
--           （Phase 1 只清了 settled_at / settlement_* 三个字段，留下了 stale
--           `total_pnl_usdc` 与 `roi_rate`，造成"settled_at=NULL 但账面 +122"
--           这种不一致状态）；
--       (2) 重建 `smart_wallet_daily_report` 在 2026-05-19 及以后的所有行，
--           按 `DATE(opened_at)` 分桶用清洗后的数据重算 `total_orders`、
--           `winning_positions`、`losing_positions`、`realized_pnl_usdc`、
--           `unrealized_pnl_usdc`、`net_pnl_usdc`、`win_rate`、`loss_rate`，
--           覆盖原始 8 行（含 Phase 1 已删的 3 行未来日期）。
-- 关联：sql/polymarket/2026-05-28__fix_phantom_orders.sql（Phase 1，已执行）
--       docs/problem/问题记录事项-待解决-2026-05-28.md §8.4 / §P2-2 / §P1-2
-- 作者：自动巡检（scheduled task `1-job`） + 待 DBA 复核
-- 状态：草稿 / 待人工复核 / 仅在 test 环境运行（生产需先回归）
--
-- ‼️ 前置条件：
--   1. Phase 1 已 COMMIT，备份表 `smart_wallet_trade_order_bak_20260528_phantom`
--      （33 行）与 `smart_wallet_daily_report_bak_20260528_future`（3 行）已存在。
--   2. DB 中应有 **29 行** `settled_at IS NULL AND status='SETTLED'` 的订单
--      （Phase 1 清掉的；若数量不符请先停下来对账）。
--
-- 数据快照（2026-05-28 10:18 CST 实测 + 10:28 CST 修复后回测确认）：
--   29 笔已清 settled_at 的订单：9 winners + 20 losers，账面 SUM(total_pnl_usdc) = +175.92
--     ├─ 20 笔属于"当前版本"（opened_at ≥ 2026-05-24 17:32:27），其中 7 winners / 13 losers，SUM = +179.65
--     └─ 9 笔属于历史版本（05-11 ~ 05-22），其中 2 winners / 7 losers，SUM = −3.73
--   当前版本 67 笔已了结：修复前 27W / 40L / SUM = +478.68
--   修完 Phase 2 之后实测：67 笔不变，分布 **20W / 27L / 20 zeros**（zeros = 当前版本里被清零的 20 笔），SUM = **+299.03**
--     （差值 478.68 − 299.03 = 179.65，与上面"当前版本里被清的 20 笔 SUM"一致 ✓）
--
-- 设计思路（为什么清零 PnL 而不是保留）：
--   29 笔的 `total_pnl_usdc` 字段是「市场名义到期 + PAPER 按 $1.00 全额估值」结算出来的
--   ——结算时间戳已被证伪（Phase 1 清除），结算价 settlement_price 同时被清为 NULL。
--   在没有真实市场 settlement 数据回写之前，账面 PnL 不再可信；保留 stale 值会让
--   下游 `daily_report` / 胜率统计继续被污染。最安全的处置是把所有 PnL 字段清零，
--   等应用侧 `SettlementJob` 用真实市场价重新结算回写。
-- =============================================================================

-- -----------------------------------------------------------------------------
-- 第 0 步：前置自检 + 影响面预演（不修改任何数据）
-- -----------------------------------------------------------------------------

SELECT NOW() AS run_at,
       DATABASE() AS db_name;

-- 预期 29
SELECT 'cleared_phase1_count' AS k,
       COUNT(*) AS expect_29,
       SUM(CASE WHEN total_pnl_usdc > 0 THEN 1 ELSE 0 END) AS expect_9_winners,
       SUM(CASE WHEN total_pnl_usdc < 0 THEN 1 ELSE 0 END) AS expect_20_losers,
       ROUND(SUM(total_pnl_usdc),2) AS expect_pnl_sum_175_92
FROM smart_wallet_trade_order
WHERE settled_at IS NULL
  AND status = 'SETTLED';

-- Phase 1 备份表存在性自检
SELECT 'bak_tables' AS k,
       (SELECT COUNT(*) FROM smart_wallet_trade_order_bak_20260528_phantom) AS bak_trade_order_rows_33,
       (SELECT COUNT(*) FROM smart_wallet_daily_report_bak_20260528_future) AS bak_daily_report_rows_3;

-- daily_report 现状：≥ 2026-05-19 的所有行（预期 8 行，含 Phase 1 已删未来日期 0）
SELECT 'daily_report_current' AS k,
       COUNT(*) AS rows_to_rebuild,
       MIN(report_date) AS min_date,
       MAX(report_date) AS max_date
FROM smart_wallet_daily_report
WHERE report_date >= '2026-05-19';

-- 预演 Phase 2 之后 daily_report 应有的行数与 PnL（用现在仍 stale 的 PnL 估）
SELECT 'daily_report_preview_before_pnl_clear' AS k,
       DATE(opened_at) AS report_date,
       COUNT(*) AS total_orders,
       SUM(CASE WHEN status IN ('SETTLED','EXITED','CLAIMED') AND total_pnl_usdc > 0 THEN 1 ELSE 0 END) AS wins,
       SUM(CASE WHEN status IN ('SETTLED','EXITED','CLAIMED') AND total_pnl_usdc < 0 THEN 1 ELSE 0 END) AS losses,
       ROUND(SUM(total_pnl_usdc),2) AS net_pnl
FROM smart_wallet_trade_order
WHERE opened_at >= '2026-05-19'
GROUP BY DATE(opened_at)
ORDER BY report_date;


-- -----------------------------------------------------------------------------
-- 第 1 步：备份 daily_report 全量 ≥ 2026-05-19 的行（Phase 1 只备份了未来日期 3 行）
-- -----------------------------------------------------------------------------

DROP TABLE IF EXISTS smart_wallet_daily_report_bak_20260528_phase2;
CREATE TABLE smart_wallet_daily_report_bak_20260528_phase2 AS
SELECT *
FROM smart_wallet_daily_report
WHERE report_date >= '2026-05-19';

-- 备份计数
SELECT 'phase2_bak_daily_report' AS k,
       COUNT(*) AS rows_backed_up
FROM smart_wallet_daily_report_bak_20260528_phase2;


-- -----------------------------------------------------------------------------
-- 第 2 步：进入事务
-- -----------------------------------------------------------------------------

START TRANSACTION;


-- -----------------------------------------------------------------------------
-- 第 2.1 步：把 29 笔已清 settled_at 的订单的 PnL / ROI / last_mark 全部清零
--   清零字段：total_pnl_usdc / roi_rate / last_mark_price / unrealized_pnl_usdc
--             / exit_proceeds_usdc / exit_realized_pnl_usdc / exit_quantity
--             / exit_avg_price / exit_fee_usdc / last_exit_at
--   保留字段：entry_*（进场记录，事实）/ remaining_quantity / remaining_cost_basis_usdc
--             / status（保持 SETTLED——状态机不退回；下次 SettlementJob 直接用
--             SETTLED 行重新结算时只会 UPDATE 字段，不会改 status）
-- -----------------------------------------------------------------------------

UPDATE smart_wallet_trade_order
SET total_pnl_usdc          = 0,
    roi_rate                = 0,
    last_mark_price         = 0,
    unrealized_pnl_usdc     = 0,
    exit_proceeds_usdc      = 0,
    exit_realized_pnl_usdc  = 0,
    exit_quantity           = 0,
    exit_avg_price          = 0,
    exit_fee_usdc           = 0,
    last_exit_at            = NULL,
    updated_at              = NOW()
WHERE settled_at IS NULL
  AND status = 'SETTLED'
  AND id IN (581, 590, 605, 610, 612,   -- 5 原 future_settled
             598, 599, 601, 602, 603, 604,
             608, 609, 615, 620,
             623, 624, 625,
             631, 633, 634, 635, 637, 639,
             642, 644, 645, 646, 647);   -- 24 原 reversed_settle


-- -----------------------------------------------------------------------------
-- 第 2.2 步：清空 daily_report ≥ 2026-05-19，准备重建
-- -----------------------------------------------------------------------------

DELETE FROM smart_wallet_daily_report
WHERE report_date >= '2026-05-19';


-- -----------------------------------------------------------------------------
-- 第 2.3 步：基于清洗后的 trade_order 重建 daily_report
--   分桶口径：DATE(opened_at)
--   total_orders        = 该日开仓数（统一口径，便于和 trade_order 实测对账）
--   filled_orders       = 该日开仓且已部分/完全成交（PAPER 模式下 = total_orders）
--   total_volume_usdc   = SUM(entry_cost_usdc)
--   fee_usdc            = SUM(entry_fee_usdc + exit_fee_usdc)
--   realized_pnl_usdc   = SUM(total_realized_pnl_usdc)
--   unrealized_pnl_usdc = SUM(unrealized_pnl_usdc)
--   net_pnl_usdc        = SUM(total_pnl_usdc)
--   winning_positions   = COUNT(status IN closed AND total_pnl_usdc > 0)
--   losing_positions    = COUNT(status IN closed AND total_pnl_usdc < 0)
--   win_rate / loss_rate = wins / losses 除以已了结样本数
--   execution_address   = 'paper-book'（数据库中现仅此一个值）
-- -----------------------------------------------------------------------------

INSERT INTO smart_wallet_daily_report
       (report_date, execution_address, total_orders, filled_orders,
        total_volume_usdc, fee_usdc,
        realized_pnl_usdc, unrealized_pnl_usdc, net_pnl_usdc,
        winning_positions, losing_positions, win_rate, loss_rate,
        created_at, updated_at)
SELECT
  DATE(opened_at)                                           AS report_date,
  'paper-book'                                              AS execution_address,
  COUNT(*)                                                  AS total_orders,
  COUNT(*)                                                  AS filled_orders,
  ROUND(SUM(entry_cost_usdc), 8)                            AS total_volume_usdc,
  ROUND(SUM(entry_fee_usdc + exit_fee_usdc), 8)             AS fee_usdc,
  ROUND(SUM(total_realized_pnl_usdc), 8)                    AS realized_pnl_usdc,
  ROUND(SUM(unrealized_pnl_usdc), 8)                        AS unrealized_pnl_usdc,
  ROUND(SUM(total_pnl_usdc), 8)                             AS net_pnl_usdc,
  SUM(CASE WHEN status IN ('SETTLED','EXITED','CLAIMED')
            AND total_pnl_usdc > 0 THEN 1 ELSE 0 END)       AS winning_positions,
  SUM(CASE WHEN status IN ('SETTLED','EXITED','CLAIMED')
            AND total_pnl_usdc < 0 THEN 1 ELSE 0 END)       AS losing_positions,
  COALESCE(ROUND(
    SUM(CASE WHEN status IN ('SETTLED','EXITED','CLAIMED')
              AND total_pnl_usdc > 0 THEN 1 ELSE 0 END)
    / NULLIF(SUM(CASE WHEN status IN ('SETTLED','EXITED','CLAIMED') THEN 1 ELSE 0 END), 0),
    4), 0)                                                   AS win_rate,
  COALESCE(ROUND(
    SUM(CASE WHEN status IN ('SETTLED','EXITED','CLAIMED')
              AND total_pnl_usdc < 0 THEN 1 ELSE 0 END)
    / NULLIF(SUM(CASE WHEN status IN ('SETTLED','EXITED','CLAIMED') THEN 1 ELSE 0 END), 0),
    4), 0)                                                   AS loss_rate,
  NOW(), NOW()
FROM smart_wallet_trade_order
WHERE opened_at >= '2026-05-19'
GROUP BY DATE(opened_at)
ORDER BY DATE(opened_at);


-- -----------------------------------------------------------------------------
-- 第 3 步：事务后校验
-- -----------------------------------------------------------------------------

-- 3.1 已清零订单核对（预期：29 笔 settled_at IS NULL + status='SETTLED' 的 total_pnl/roi/last_mark 全部为 0）
SELECT 'cleared_orders_state_after' AS k,
       COUNT(*) AS expect_29,
       SUM(CASE WHEN total_pnl_usdc = 0 THEN 1 ELSE 0 END) AS expect_29_pnl_zero,
       SUM(CASE WHEN roi_rate = 0 THEN 1 ELSE 0 END) AS expect_29_roi_zero,
       SUM(CASE WHEN last_mark_price = 0 THEN 1 ELSE 0 END) AS expect_29_lastmark_zero
FROM smart_wallet_trade_order
WHERE settled_at IS NULL AND status = 'SETTLED';

-- 3.2 当前版本聚合复算
--   修复前：67 笔，27W / 40L / 0 zeros，SUM(total_pnl_usdc) = +478.68
--   修复后实测（10:28 CST COMMIT 后）：67 笔不变，20W / 27L / **20 zeros**（当前版本里被清零的 20 笔），SUM = **+299.03**
--   差值 +478.68 → +299.03 = −179.65，与"当前版本里被清的 20 笔账面值"一致 ✓
SELECT 'current_version_after' AS k,
       COUNT(*) AS closed_n_expect_67,
       SUM(CASE WHEN total_pnl_usdc > 0 THEN 1 ELSE 0 END) AS wins_expect_20,
       SUM(CASE WHEN total_pnl_usdc < 0 THEN 1 ELSE 0 END) AS losses_expect_27,
       SUM(CASE WHEN total_pnl_usdc = 0 THEN 1 ELSE 0 END) AS zeros_expect_20,
       ROUND(SUM(total_pnl_usdc),2) AS pnl_expect_299_03
FROM smart_wallet_trade_order
WHERE opened_at >= '2026-05-24 17:32:27'
  AND status IN ('SETTLED','EXITED','CLAIMED');

-- 3.3 daily_report 重建后预览
SELECT 'daily_report_rebuilt' AS k,
       id, report_date, total_orders, winning_positions, losing_positions,
       ROUND(net_pnl_usdc,2)        AS net_pnl,
       ROUND(realized_pnl_usdc,2)   AS realized,
       ROUND(unrealized_pnl_usdc,2) AS unrealized,
       ROUND(win_rate,4)            AS win_rate,
       ROUND(loss_rate,4)           AS loss_rate
FROM smart_wallet_daily_report
WHERE report_date >= '2026-05-19'
ORDER BY report_date;

-- 3.4 全量异常重扫（预期：trade_order 异常 0、daily_report 未来日期 0）
SELECT 'anomalies_final' AS k,
       (SELECT COUNT(*) FROM smart_wallet_trade_order
         WHERE settled_at > NOW()
            OR (settled_at IS NOT NULL AND settled_at < opened_at)) AS expect_0_bad_settled_at,
       (SELECT COUNT(*) FROM smart_wallet_daily_report
         WHERE report_date > CURDATE()) AS expect_0_future_daily,
       (SELECT COUNT(*) FROM smart_wallet_trade_order
         WHERE settled_at IS NULL AND status='SETTLED'
           AND (total_pnl_usdc <> 0 OR roi_rate <> 0)) AS expect_0_inconsistent_cleared;


-- -----------------------------------------------------------------------------
-- 第 4 步：提交（或回滚）
--   只有当 3.1 的 29/29/29/29、3.2 的 **67/20/27/20/+299.03**、3.3 的行数与符号、
--   以及 3.4 的三个 0 都满足时，才执行 COMMIT。
--   (3.2 的口径：当前版本 67 笔已了结，20W / 27L / 20 zeros，账面 PnL = +299.03 USDC；
--    上一稿注释里的 "67/18/20/29/+302.76" 是我事先的均值估算，与实测分布不一致，已订正。)
-- -----------------------------------------------------------------------------

-- COMMIT;
-- ROLLBACK;


-- =============================================================================
-- 第 5 步：回滚预案（如 COMMIT 后发现问题）
-- =============================================================================

-- 5.1 回滚 trade_order PnL 字段（从 Phase 1 备份表读原始 PnL）
-- START TRANSACTION;
-- UPDATE smart_wallet_trade_order tgt
-- JOIN  smart_wallet_trade_order_bak_20260528_phantom src ON src.id = tgt.id
-- SET   tgt.total_pnl_usdc         = src.total_pnl_usdc,
--       tgt.roi_rate               = src.roi_rate,
--       tgt.last_mark_price        = src.last_mark_price,
--       tgt.unrealized_pnl_usdc    = src.unrealized_pnl_usdc,
--       tgt.exit_proceeds_usdc     = src.exit_proceeds_usdc,
--       tgt.exit_realized_pnl_usdc = src.exit_realized_pnl_usdc,
--       tgt.exit_quantity          = src.exit_quantity,
--       tgt.exit_avg_price         = src.exit_avg_price,
--       tgt.exit_fee_usdc          = src.exit_fee_usdc,
--       tgt.last_exit_at           = src.last_exit_at,
--       tgt.updated_at             = NOW()
-- WHERE tgt.settled_at IS NULL AND tgt.status = 'SETTLED';
-- COMMIT;

-- 5.2 回滚 daily_report（删重建的，回插 Phase 2 备份的）
-- START TRANSACTION;
-- DELETE FROM smart_wallet_daily_report WHERE report_date >= '2026-05-19';
-- INSERT INTO smart_wallet_daily_report
-- SELECT * FROM smart_wallet_daily_report_bak_20260528_phase2;
-- COMMIT;


-- =============================================================================
-- 后续行动（不在本脚本范围）：
--   1. 触发应用侧 `SettlementJob` 对 29 笔 `settled_at IS NULL AND status='SETTLED'`
--      的订单按真实 Polymarket 市场价拉数据并回写 `settled_at` / `settlement_price`
--      / `settlement_pnl_usdc` / `total_pnl_usdc` / `roi_rate`，之后 daily_report
--      下次自然刷新就会带上真实数据。
--   2. 代码侧防御补齐：
--      - `SmartWalletTradeOrderService` 写入入口：assert settled_at IS NULL OR settled_at >= opened_at
--      - `SmartWalletDailyReportService` 写入入口：assert report_date <= CURDATE()
--      - `roi_rate` 加 CHECK 约束时上限不应低于 50（Polymarket 0.02 USDC 进、1.00 结时
--        ROI=49，硬上限设 5 会把真大赢误拦）
-- =============================================================================
