-- =================================================================
-- Polymarket 结算 / 统计 / 账本排查 SQL
-- 用途：
-- 1. 排查结算流程卡点
-- 2. 核对仓位与资金流水是否一致
-- 3. 识别组合统计口径偏差
-- 4. 辅助确认日报是否被当前持仓反向污染
--
-- 使用建议：
-- - 先从“一、结算状态排查”开始
-- - 再执行“二、账本一致性排查”
-- - 最后看“三、统计口径排查”和“四、日报排查”
-- - 本文件只读，不包含任何删除/更新操作
-- =================================================================

-- -----------------------------------------------------------------
-- 一、结算状态排查
-- -----------------------------------------------------------------

-- 1. 查看当前卡在 RESOLVED_UNCLAIMED 的仓位
-- 含义：
-- - 市场结果已确认
-- - 但 claim / 到账流程尚未完全收口
SELECT
    id,
    execution_address,
    market_id,
    outcome,
    quantity,
    realized_pnl_usdc,
    closed_at,
    status
FROM smart_wallet_position
WHERE status = 'RESOLVED_UNCLAIMED'
ORDER BY closed_at DESC;


-- 2. 查找已结算或已领取仓位中，缺少 SETTLEMENT_PROCEEDS 账本记录的项
-- 含义：
-- - position 已经是 RESOLVED_UNCLAIMED / CLAIMED
-- - 但资金流水里没有 settlement proceeds
-- 风险：
-- - 可能导致容量、到账统计、日报口径不一致
SELECT
    p.id,
    p.execution_address,
    p.market_id,
    p.outcome,
    p.status,
    p.closed_at
FROM smart_wallet_position p
LEFT JOIN smart_wallet_cash_ledger l
    ON l.reference_type = 'POSITION'
   AND l.reference_id = p.id
   AND l.entry_type = 'SETTLEMENT_PROCEEDS'
WHERE p.status IN ('RESOLVED_UNCLAIMED', 'CLAIMED')
GROUP BY
    p.id,
    p.execution_address,
    p.market_id,
    p.outcome,
    p.status,
    p.closed_at
HAVING COUNT(l.id) = 0
ORDER BY p.closed_at DESC, p.id DESC;


-- 3. 查找 settlement retry 队列中的失败市场，以及它们当前还挂着多少未结算仓位
-- 含义：
-- - 如果 open_positions > 0 且 retry 长期失败，说明结算同步可能持续卡住
SELECT
    r.market_id,
    r.status,
    r.failure_count,
    r.last_error_message,
    r.last_failed_at,
    r.next_retry_at,
    COUNT(p.id) AS open_positions
FROM smart_wallet_settlement_retry r
LEFT JOIN smart_wallet_position p
    ON p.market_id = r.market_id
   AND p.status IN ('OPEN', 'PARTIALLY_CLOSED')
GROUP BY
    r.market_id,
    r.status,
    r.failure_count,
    r.last_error_message,
    r.last_failed_at,
    r.next_retry_at
ORDER BY r.last_failed_at DESC, r.market_id;


-- 4. 查看仍然处于 OPEN / PARTIALLY_CLOSED，但 market_end_at 已经过期的仓位
-- 含义：
-- - 这些仓位理论上应优先进入 settlement sync 复核
SELECT
    id,
    execution_address,
    market_id,
    outcome,
    quantity,
    market_end_at,
    status,
    last_filled_at
FROM smart_wallet_position
WHERE status IN ('OPEN', 'PARTIALLY_CLOSED')
  AND market_end_at IS NOT NULL
  AND market_end_at > 0
  AND market_end_at <= UNIX_TIMESTAMP(NOW(3)) * 1000
ORDER BY market_end_at ASC, id ASC;


-- -----------------------------------------------------------------
-- 二、账本一致性排查
-- -----------------------------------------------------------------

-- 5. 查找重复记账的 SETTLEMENT_PROCEEDS
-- 含义：
-- - 同一个 position 被写了多条 settlement proceeds
-- 风险：
-- - 到账金额重复
-- - claim 后统计被放大
SELECT
    reference_id AS position_id,
    COUNT(*) AS entry_count,
    SUM(CASE WHEN direction = 'OUT' THEN -amount ELSE amount END) AS net_amount
FROM smart_wallet_cash_ledger
WHERE reference_type = 'POSITION'
  AND entry_type = 'SETTLEMENT_PROCEEDS'
GROUP BY reference_id
HAVING COUNT(*) > 1
ORDER BY entry_count DESC, position_id DESC;


-- 6. 核对 position.realized_pnl_usdc 与 ledger 中 REALIZED_PNL 的累计金额是否一致
-- 含义：
-- - position 是状态快照
-- - ledger 是资金事实
-- - 两者偏差过大说明状态快照与账本已经漂移
SELECT
    p.id,
    p.execution_address,
    p.market_id,
    p.outcome,
    p.realized_pnl_usdc AS position_realized,
    COALESCE(SUM(CASE WHEN l.direction = 'OUT' THEN -l.amount ELSE l.amount END), 0) AS ledger_realized
FROM smart_wallet_position p
LEFT JOIN smart_wallet_cash_ledger l
    ON l.reference_type = 'POSITION'
   AND l.reference_id = p.id
   AND l.entry_type = 'REALIZED_PNL'
GROUP BY
    p.id,
    p.execution_address,
    p.market_id,
    p.outcome,
    p.realized_pnl_usdc
HAVING ABS(
    p.realized_pnl_usdc -
    COALESCE(SUM(CASE WHEN l.direction = 'OUT' THEN -l.amount ELSE l.amount END), 0)
) > 0.0001
ORDER BY p.id DESC;


-- 7. 检查 CLAIMED 仓位是否仍然没有 settlement proceeds
-- 含义：
-- - CLAIMED 理论上应表示领取完成
-- - 如果没有 proceeds，claim 的账务语义就不完整
SELECT
    p.id,
    p.execution_address,
    p.market_id,
    p.outcome,
    p.closed_at,
    p.updated_at
FROM smart_wallet_position p
LEFT JOIN smart_wallet_cash_ledger l
    ON l.reference_type = 'POSITION'
   AND l.reference_id = p.id
   AND l.entry_type = 'SETTLEMENT_PROCEEDS'
WHERE p.status = 'CLAIMED'
GROUP BY
    p.id,
    p.execution_address,
    p.market_id,
    p.outcome,
    p.closed_at,
    p.updated_at
HAVING COUNT(l.id) = 0
ORDER BY p.updated_at DESC, p.id DESC;


-- 8. 按 position 查看 REALIZED_PNL 与 SETTLEMENT_PROCEEDS 的组合情况
-- 用途：
-- - 快速判断某个 position 当前账本处于什么阶段
SELECT
    p.id AS position_id,
    p.execution_address,
    p.market_id,
    p.outcome,
    p.status,
    SUM(CASE WHEN l.entry_type = 'REALIZED_PNL'
             THEN CASE WHEN l.direction = 'OUT' THEN -l.amount ELSE l.amount END
             ELSE 0 END) AS realized_pnl_ledger,
    SUM(CASE WHEN l.entry_type = 'SETTLEMENT_PROCEEDS'
             THEN CASE WHEN l.direction = 'OUT' THEN -l.amount ELSE l.amount END
             ELSE 0 END) AS settlement_proceeds_ledger
FROM smart_wallet_position p
LEFT JOIN smart_wallet_cash_ledger l
    ON l.reference_type = 'POSITION'
   AND l.reference_id = p.id
WHERE p.status IN ('RESOLVED_UNCLAIMED', 'CLAIMED')
GROUP BY
    p.id,
    p.execution_address,
    p.market_id,
    p.outcome,
    p.status
ORDER BY p.id DESC;


-- -----------------------------------------------------------------
-- 三、统计口径排查
-- -----------------------------------------------------------------

-- 9. 对比 portfolio 视图的基础输入量 与 真实 live positions 数量
-- 含义：
-- - followed_tasks 来自 follow_task 口径
-- - live_positions 来自真实持仓口径
-- - 两者显著偏差时，说明 /portfolio/* 与 /positions/live 不是一回事
SELECT COUNT(*) AS followed_tasks
FROM smart_wallet_follow_task
WHERE status = 'FOLLOWED';

SELECT COUNT(*) AS live_positions
FROM smart_wallet_position
WHERE status IN ('OPEN', 'PARTIALLY_CLOSED');


-- 10. 查找同一 execution_address + market_id 下持有多个 outcome 的情况
-- 含义：
-- - 这是冲突市场或双边持仓的直接证据
-- - 容易影响胜率、归因与净暴露解释
SELECT
    execution_address,
    market_id,
    COUNT(DISTINCT outcome) AS outcome_count
FROM smart_wallet_position
WHERE status IN ('OPEN', 'PARTIALLY_CLOSED', 'RESOLVED_UNCLAIMED', 'CLAIMED')
GROUP BY execution_address, market_id
HAVING COUNT(DISTINCT outcome) > 1
ORDER BY outcome_count DESC, execution_address, market_id;


-- 11. 查看按市场聚合的持仓数量与净成本
-- 用途：
-- - 观察是否有明显重复市场、异常堆积市场
SELECT
    market_id,
    COUNT(*) AS position_count,
    COUNT(DISTINCT outcome) AS distinct_outcomes,
    SUM(cost_basis_usdc) AS total_cost_basis_usdc,
    SUM(realized_pnl_usdc) AS total_realized_pnl_usdc,
    SUM(unrealized_pnl_usdc) AS total_unrealized_pnl_usdc
FROM smart_wallet_position
GROUP BY market_id
ORDER BY position_count DESC, total_cost_basis_usdc DESC;


-- 12. 查看按执行地址聚合的账本净额
-- 用途：
-- - 判断 paper-book / live-wallet 等不同执行主体是否存在统计漂移
SELECT
    execution_address,
    SUM(CASE WHEN direction = 'OUT' THEN -amount ELSE amount END) AS net_cash_flow,
    SUM(CASE WHEN entry_type = 'FEE' THEN amount ELSE 0 END) AS total_fee,
    SUM(CASE WHEN entry_type = 'REALIZED_PNL'
             THEN CASE WHEN direction = 'OUT' THEN -amount ELSE amount END
             ELSE 0 END) AS total_realized_pnl,
    SUM(CASE WHEN entry_type = 'SETTLEMENT_PROCEEDS'
             THEN CASE WHEN direction = 'OUT' THEN -amount ELSE amount END
             ELSE 0 END) AS total_settlement_proceeds
FROM smart_wallet_cash_ledger
GROUP BY execution_address
ORDER BY execution_address;


-- -----------------------------------------------------------------
-- 四、日报 / 时间线原始输入排查
-- 说明：
-- - smart_wallet_daily_report 继续保留，作为每日收益沉淀
-- - 下面这些 SQL 用来核对日报写入所依赖的原始输入是否正确
-- -----------------------------------------------------------------

-- 13. 查看最近 30 天按天聚合的 REALIZED_PNL / FEE 原始输入
-- 用途：
-- - 检查日报和时间线的核心资金口径是否存在异常跳变
SELECT
    DATE(occurred_at) AS report_date,
    execution_address,
    SUM(CASE
            WHEN entry_type = 'REALIZED_PNL' AND direction = 'OUT' THEN -amount
            WHEN entry_type = 'REALIZED_PNL' THEN amount
            ELSE 0
        END) AS realized_pnl_usdc,
    SUM(CASE
            WHEN entry_type = 'FEE' THEN amount
            ELSE 0
        END) AS fee_usdc,
    SUM(CASE
            WHEN entry_type = 'REALIZED_PNL' AND direction = 'OUT' THEN -amount
            WHEN entry_type = 'REALIZED_PNL' THEN amount
            WHEN entry_type = 'FEE' THEN -amount
            ELSE 0
        END) AS net_realized_after_fee
FROM smart_wallet_cash_ledger
GROUP BY DATE(occurred_at), execution_address
ORDER BY report_date DESC, execution_address
LIMIT 30;


-- 14. 查看最近 30 天按天聚合的订单 / 成交活动
-- 用途：
-- - 核对日报里的 totalOrders / filledOrders / totalVolumeUsdc 是否有基础数据支撑
SELECT
    d.report_date,
    d.execution_address,
    COALESCE(o.total_orders, 0) AS total_orders,
    COALESCE(f.filled_orders, 0) AS filled_orders,
    COALESCE(f.total_volume_usdc, 0) AS total_volume_usdc
FROM (
    SELECT DISTINCT DATE(occurred_at) AS report_date, execution_address
    FROM smart_wallet_cash_ledger
    UNION
    SELECT DISTINCT DATE(placed_at) AS report_date, execution_address
    FROM smart_wallet_order
    UNION
    SELECT DISTINCT DATE(filled_at) AS report_date, execution_address
    FROM smart_wallet_fill
) d
LEFT JOIN (
    SELECT DATE(placed_at) AS report_date, execution_address, COUNT(*) AS total_orders
    FROM smart_wallet_order
    GROUP BY DATE(placed_at), execution_address
) o
    ON o.report_date = d.report_date
   AND o.execution_address <=> d.execution_address
LEFT JOIN (
    SELECT DATE(filled_at) AS report_date,
           execution_address,
           COUNT(*) AS filled_orders,
           SUM(notional_usdc) AS total_volume_usdc
    FROM smart_wallet_fill
    GROUP BY DATE(filled_at), execution_address
) f
    ON f.report_date = d.report_date
   AND f.execution_address <=> d.execution_address
ORDER BY d.report_date DESC, d.execution_address
LIMIT 30;


-- 15. 查看当前 open positions 的未实现盈亏输入
-- 用途：
-- - 日报历史不再持久化 unrealized
-- - 当前 unrealized 只应来自仍活跃仓位
SELECT
    execution_address,
    market_id,
    outcome,
    status,
    quantity,
    cost_basis_usdc,
    last_mark_price,
    unrealized_pnl_usdc,
    last_filled_at
FROM smart_wallet_position
WHERE status IN ('OPEN', 'PARTIALLY_CLOSED')
ORDER BY execution_address, last_filled_at DESC;


-- -----------------------------------------------------------------
-- 五、辅助核对
-- -----------------------------------------------------------------

-- 16. 查看最近的 settlement run 执行情况
SELECT
    id,
    trigger_type,
    status,
    scanned_positions,
    distinct_markets,
    fetched_markets,
    resolved_positions,
    unresolved_markets,
    skipped_future_markets,
    failed_markets,
    started_at,
    finished_at
FROM smart_wallet_settlement_run
ORDER BY started_at DESC
LIMIT 20;


-- 17. 查看最近的 claim run 执行情况
SELECT
    id,
    trigger_type,
    status,
    scanned_positions,
    attempted_claims,
    claimed_positions,
    waiting_confirmation_positions,
    failed_positions,
    skipped_positions,
    started_at,
    finished_at
FROM smart_wallet_claim_run
ORDER BY started_at DESC
LIMIT 20;
