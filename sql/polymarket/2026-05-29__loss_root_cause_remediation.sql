-- =====================================================================
-- 2026-05-29 ray@cowork — Loss root-cause remediation (PAPER)
-- =====================================================================
-- 关联文档：
--   docs/problem/亏损根因分析-2026-05-29.md
--   docs/problem/问题记录事项-待解决-2026-05-29.md (§3.4 / P0-1 / P0-5)
--
-- 本文件解决用户在 2026-05-29 巡检后提出的三项动作：
--   1. 启用 PAPER 止损链路；止损前增加 bid 侧吸纳能力校验
--   2. SPORTS 品类不再跟单
--   3. 修复亏损根因 #4（LOW_CONFIDENCE 过滤已就位，本次确认无 SQL 变更）
--      与根因 #5（STALE 摄取 → 收紧 HOT/WARM 轮询周期）
--   + DISABLE 单一钱包 0x2005…875ea（贡献 86% 损失）
--
-- 全部 idempotent，可重复执行。仅写 strategy_runtime_config 与
-- smart_wallet_manual_allowlist，不动业务订单 / 持仓表。
--
-- 灰度发布步骤：
--   (a) 服务端先发布带本次代码改动的新 jar（mapper.upsert / 止损 bid 校验 / G6_CATEGORY_DISABLED）
--   (b) 执行本 SQL；
--   (c) 1h 内核对 smart_wallet_order 是否出现非零 SELL 行：
--         SELECT side, COUNT(*) FROM smart_wallet_order
--          WHERE created_at >= NOW() - INTERVAL 1 HOUR GROUP BY side;
--   (d) 24h 后按 docs/problem/亏损根因分析-2026-05-29.md §4 全量核对。
-- =====================================================================

-- ─── 1. 启用 PAPER 资金回收 / 自动减仓 + bid 吸纳能力闸门 ────────────────
INSERT INTO strategy_runtime_config
  (strategy_code, config_key, config_value, value_type, group_name, editable, description)
VALUES
  ('POLYMARKET_COPY', 'smart-wallet.signal-risk.capital-recycle.paper-enabled',
   'true', 'BOOLEAN', 'capital-recycle', 1,
   '允许 CapitalRecycleExitService 扫描 PAPER 仓位 (与 enabled 独立)。亏损根因 #1 修复。'),
  ('POLYMARKET_COPY', 'smart-wallet.position-price-monitor.auto-exit-enabled',
   'true', 'BOOLEAN', 'position-price-monitor', 1,
   '允许 PositionPriceMonitorService 在三条件下触发自动减仓。亏损根因 #1 修复。'),
  ('POLYMARKET_COPY', 'smart-wallet.position-price-monitor.auto-exit-paper-enabled',
   'true', 'BOOLEAN', 'position-price-monitor', 1,
   'PAPER 模式仅写影子日志，不真下单（与 auto-exit-enabled 配套）。'),
  ('POLYMARKET_COPY', 'smart-wallet.signal-risk.capital-recycle.min-bid-absorb-ratio',
   '0.5', 'DECIMAL', 'capital-recycle', 1,
   'absorbRatio = bidDepth(±3¢) / position.quantity 低于此值跳过本轮 + cooldown。'
   ' 避免"空盘口挂 SELL"导致挂单残留与账面价脱节。亏损根因 #1 修复。'),
  ('POLYMARKET_COPY', 'smart-wallet.signal-risk.capital-recycle.bid-depth-window-cents',
   '0.03', 'DECIMAL', 'capital-recycle', 1,
   '累计 bid 深度的价格窗口（best bid 下 N 美分内），与 min-bid-absorb-ratio 配对。')
ON DUPLICATE KEY UPDATE
  config_value = VALUES(config_value),
  description  = VALUES(description),
  updated_at   = CURRENT_TIMESTAMP;

-- ─── 2. SPORTS 品类拉黑（G6_CATEGORY_DISABLED） ─────────────────────────
-- 取自 smart_wallet_market_info.normalized_category；大小写不敏感。
-- 想恢复某品类时，把该项 config_value 改成空字符串或移除该品类。
-- 亏损根因 #3 修复：14/15 settled 落在 SPORTS，BTTS / Match Winner 子类全损。
INSERT INTO strategy_runtime_config
  (strategy_code, config_key, config_value, value_type, group_name, editable, description)
VALUES
  ('POLYMARKET_COPY', 'smart-wallet.signal-risk.disabled-categories',
   'SPORTS', 'STRING_SET', 'signal-risk', 1,
   '逗号分隔的品类黑名单（normalizedCategory）。命中即 G6_CATEGORY_DISABLED 拒单。'
   ' 亏损根因 #3 修复。')
ON DUPLICATE KEY UPDATE
  config_value = VALUES(config_value),
  description  = VALUES(description),
  updated_at   = CURRENT_TIMESTAMP;

-- ─── 3. 根因 #5 缓解：收紧 HOT 钱包事件同步轮询 + WS 拉取最小间隔 ───────
-- 30s → 15s：HOT 钱包成交事件平均 P90 摄取时延从 894s 降至预期 < 600s WARN 阈值。
-- 60s → 30s：单钱包再次成交的最小拉取间隔减半。
-- 风险：Gamma API 请求频次翻倍，需观察 429 限流。可随时回退到 30000 / 60000。
INSERT INTO strategy_runtime_config
  (strategy_code, config_key, config_value, value_type, group_name, editable, description)
VALUES
  ('POLYMARKET_COPY', 'smart-wallet.polymarket.event-sync-fixed-delay-ms',
   '15000', 'INTEGER', 'polymarket', 1,
   'PolymarketWalletDiscoveryService.scheduledSyncWatchlistTrades 调度间隔（毫秒）。'
   ' 亏损根因 #5 修复：从默认 30000 降到 15000，缓解 STALE 入口拒单。'),
  ('POLYMARKET_COPY', 'smart-wallet.polymarket.wallet-trade-sync-min-interval-ms',
   '30000', 'INTEGER', 'polymarket', 1,
   '单钱包两次拉取交易的最小间隔（毫秒），从 60000 降到 30000。')
ON DUPLICATE KEY UPDATE
  config_value = VALUES(config_value),
  description  = VALUES(description),
  updated_at   = CURRENT_TIMESTAMP;

-- ─── 4. DISABLE 单钱包 0x2005…875ea（占 86% 损失） ──────────────────────
-- 亏损根因 #2 修复。同期 OPEN 仓位（10 笔, cost 62.49 USDC）会被
-- 上面 step 1 启用后的 CapitalRecycleExitService 自动评估清仓。
UPDATE smart_wallet_manual_allowlist
   SET status      = 'DISABLED',
       updated_at  = CURRENT_TIMESTAMP
 WHERE LOWER(wallet_address) = '0x2005d16a84ceefa912d4e380cd32e7ff827875ea'
   AND status = 'ACTIVE';

-- ─── 5. 验证查询（手动核对，不改数据） ──────────────────────────────────
-- 5.1 配置是否落库
SELECT config_key, config_value, updated_at
  FROM strategy_runtime_config
 WHERE strategy_code = 'POLYMARKET_COPY'
   AND (config_key LIKE '%capital-recycle%'
        OR config_key LIKE '%position-price-monitor%'
        OR config_key LIKE '%disabled-categories%'
        OR config_key LIKE '%event-sync-fixed-delay-ms%'
        OR config_key LIKE '%wallet-trade-sync-min-interval%')
 ORDER BY config_key;

-- 5.2 钱包是否已停用
SELECT wallet_address, status, updated_at
  FROM smart_wallet_manual_allowlist
 WHERE LOWER(wallet_address) = '0x2005d16a84ceefa912d4e380cd32e7ff827875ea';

-- 5.3 24h 后 — 出场动作是否真正落地（应出现非零 SELL 与非空 exit_reason）
-- SELECT side, COUNT(*) FROM smart_wallet_order
--  WHERE created_at >= NOW() - INTERVAL 24 HOUR GROUP BY side;
-- SELECT exit_reason, COUNT(*) FROM smart_wallet_order
--  WHERE exit_reason IS NOT NULL
--    AND created_at >= NOW() - INTERVAL 24 HOUR
--  GROUP BY exit_reason ORDER BY 2 DESC;

-- 5.4 24h 后 — 品类分布是否还有 SPORTS（应仅剩 OPEN 历史）
-- SELECT m.normalized_category, COUNT(*) cnt
--   FROM smart_wallet_follow_task ft
--   JOIN smart_wallet_market_info m ON m.id = ft.market_info_id
--  WHERE ft.created_at >= NOW() - INTERVAL 24 HOUR
--    AND ft.status = 'FOLLOWED'
--  GROUP BY m.normalized_category;

-- 5.5 24h 后 — STALE 拒单占比是否下降（目标 <30%）
-- 在服务器日志侧：
--   grep -aoE 'firstRejectGate=[A-Z0-9_]+' \
--     /opt/coinstrategy/smart-wallet/logs/job-current.log \
--     | sort | uniq -c | sort -rn | head
