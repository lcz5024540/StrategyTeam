# Smart-Wallet 跟单策略：端到端交易流程链路与架构（2026-06-03）

> 目的：把"链上 leader 成交 → 信号 → 风控 → 定仓 → 执行 → 记账 → 盯市 → 出场 → 结算 → 认领"整条链路、涉及的服务/表/定时任务、风控门、并发与资金模型梳理清楚，作为后续修复与上线的权威参考。
> 适用模块：`coin-strategy-job` 的 `smartwallet` 包 + `coin-strategy-common` 配置。部署拓扑见 `scripts/build-env.sh`（job+DB 在 49.51.250.174，web 在 43.139.186.27）。

---

## 0. 一句话定位
这是一个**基于"聪明钱"链上成交的跟单型量化策略**：发现优质 leader 钱包 → 实时抓取其 Polymarket 成交 → 经多道风控/EV/仓位门 → 以 PAPER(纸面) 或 LIVE(实盘) 复制开仓 → 盯市并按止盈止损/资金回收/跟随 leader 出场 → 市场结算后入账并链上认领。`smart-wallet.auto-execution-enabled` 是 PAPER/LIVE 总开关（当前=false，纯纸面）。

---

## 1. 端到端链路（S1–S9）

### S1 事件摄取（leader 成交 → trade_event）
- 触发：`PolymarketWalletDiscoveryService.scheduledSyncWatchlistTrades`，`@Scheduled fixedDelay=30s`（`event-sync-fixed-delay-ms`）。
- 仅触发节拍在共享调度池；**真正的抓取跑在专用单线程 executor** `smartWalletEventSyncExecutor`（`SmartWalletEventSyncAsyncConfig`，core=1）——防重型任务饿死摄取。`eventSyncRunning` 保证不重入。
- 每轮取 HOT 钱包批（`watchlist-trade-sync-batch-size=5`，`wallet-trade-sync-min-interval-ms=60s`），`fetchUserTrades` 增量（游标=`smart_wallet_profile.last_event_sync_at`，过滤 `tradedAt>checkpoint`）→ `ingestTradeEvent`。
- 落表：`smart_wallet_trade_event`（`processed=false`，按 `external_trade_id` 去重）。
- ⚠️ 风险点：摄取滞后→信号过期(G5)零开单（曾 16h 停摆，根因=callTimeout 缺失+池饥饿，已修）；游标卡旧→重放旧 backlog 全被 STALE 跳过；去重为 TOCTOU 无唯一索引（R7）。

### S2 候选增强 / 实体聚类
- `wallet_follow_candidate`、`wallet_entity_map`：把多钱包归并到 entity，做共识/去重；丰富 leader 画像。

### S3 信号风控评估（trade_event → 决策）
- 入口：`SmartWalletFollowTaskService.recordTradeEvent → createFollowTaskIfQualified → SmartWalletSignalRiskService.evaluate(profile, event, …)`。
- **风控门顺序（全列表见 §3）**，输出 `accepted` + `firstRejectGate` + `recommendedStakeUsdc` + `executionMode`。
- 结果状态写 `smart_wallet_follow_task.status`：
  - `FOLLOWED`：通过 → 进入执行队列（PAPER 主动跟 / LIVE 视总开关）。
  - `PAPER_SHADOW`：被拒但记录为影子（多数被拒信号）。
  - `SKIPPED`：不可交易类（NON_TRADABLE 等）。
  - `PENDING`：已排程待执行。`SETTLED`：已结算。
- 入场取价：`resolveFreshEntryPrice`（实时盘口 best-ask→midpoint→leader 触发价兜底）。

### S4 定仓（Sizing）
- `KellyStakeCalculator/KellyCalculator`：`b=(1-price)/price`，`f*=(p·b-(1-p))/b`，`stake=capital·kellyFraction·f*`，乘共识倍率，钳 [minOrder, maxStake]。
- 资金上下文：`resolveCapitalContext` = `min(策略可用资金, 链上钱包余额)`；可用资金 `availableCapitalUsdc = bankroll + 已实现PnL − 手续费/gas − 已开仓成本 − 锁定认领额`。
- 敞口上限：`FollowExposureLimitService`（per-market `maxPerMarketUsdc=200`、per-leader 上限）。
- ⚠️ 风险点：在途资金(待执行任务+未成交挂单)不计入可用资金→超额(R4)；G4 非原子无锁→并发突破(R5)；无持仓数/品类/事件/相关性硬上限(R6)；EV 的 p_win 语义错(P0-2 暂放)。

### S5 执行（决策 → 订单/持仓）
- 触发：`SmartWalletFollowTaskService.processPendingTasks`，`@Scheduled fixedDelay=5s`（`smart-wallet.poll-fixed-delay-ms`）。`claimTaskForExecution` 乐观锁防同 task 重复。
- 网关：`LoggingPolymarketExecutionGateway.execute(signal, mode)`：
  - **PAPER**：按 `signal.market.price`（本轮已加滑点）即时 FILLED 记账（不真正下单）。
  - **LIVE**：`PolymarketOrderRequestFactory` 算限价（MAKER：`min(raw+tickBuffer, bestBid+offset)`；TAKER：`raw·(1+slippage)` 并钳到 `bestAsk·(1+premium)`）→ `PolymarketClobExecutionService` 经 `PolymarketClobClient`/Python SDK 下单。
- 限价/止盈止损盘口来自 `smart_wallet_market_snapshot`（`MarketSnapshotCollectorService` ~45s 轮询；本会话修复了 `/book` 参数与 trades 非致命，使快照恢复落库）。
- 落表：`smart_wallet_order`（含 `fee_usdc/gas_fee_usdc/fee_pol`）、`smart_wallet_fill`、`smart_wallet_position`、`smart_wallet_trade_order`。

### S6 记账（持仓与现金流）
- `SmartWalletAccountingService.recordExecution → applyFillToPosition`：建/加仓 `smart_wallet_position`（`@Version` 乐观锁），写现金流水 `smart_wallet_cash_ledger`（`FEE/NETWORK_FEE/REALIZED_PNL`）。
- 已实现 PnL 复利回 `availableCapital`；手续费/gas 扣减。

### S7 盯市（mark-to-market）
- `PositionPriceMonitorService` + `SmartWalletAccountingService.markToMarket`：标记价**优先取实时盘口 bestBid→midpoint**，再退 trade_event 价（带跳变/区间防污染），最后退上次 mark；钳 [0.001,0.999]，杜绝按 $1 估值。
- `GtcOrderTimeoutTracker`：GTC/入场 BUY 挂单超时撤销（本会话 callTimeout 修复使其不再被卡死）。

### S8 出场（止盈/止损/回收/跟随退出）
- `SmartWalletPositionSyncService`（@Scheduled 扫描）：
  - 时间感知止损 `TimeAwareStopLossService`（分层 `tiers=0.35/0.25/0.15/0.05` 按剩余小时收紧）、止盈（`auto-take-profit`）、趋势止损。
  - 触发=按 `pnlRate` 与阈值比较；纸面平仓价**本轮已改为按真实盘口 bid**（原按阈值价虚高）。
- `CapitalRecycleExitService`（@Scheduled 5min）：持仓超 `holding-period-hours=8` 且评分够 → 按盘口 bid 回收；有 bid 流动性吸纳门。⚠️ 死区上沿 +0.08 会砍慢热赢家(R17)。
- 跟随 leader 出场：leader SELL → `DYNAMIC_FOLLOW_EXIT` → `FollowPositionAdjustmentService.adjust`（全平/部分平/对手 BUY 先平后可选反手）。⚠️ 纸面退出价仍按 leader 价(R8 剩余)；allowlist 仓位被排除在自动止损外(R16)。

### S9 结算与认领（市场 resolve → 入账 → 链上认领）
- `SmartWalletSettlementSyncService`（@Scheduled ~5min）→ `SmartWalletAccountingService.syncSettlements`：
  - 对已到期/已 resolve 的市场 `resolvePosition`（settlementPrice=1/0，按 outcome/token 判定）；本会话新增**本地权威兜底结算** `tryLocalAuthoritativeSettle`（本地 `resolved_outcome` 确认即结算）+ **坍塌即亏损** `tryExpiredWorthlessSettle`（报价≤0.05 且过龄→按 0 结算），解决"已结算却卡 OPEN"。
  - 多道防虚高闸：`settlementTerminalCheck`（四指标全终结）、`SETTLEMENT_GATE_ENDAT`、`SETTLEMENT_GUARD`（acceptingOrders=true 拒结算）、`SettlementTimeGuard`（clamp 时间）。
  - 状态：`OPEN→RESOLVED_UNCLAIMED→CLAIMED`；未决→`SETTLEMENT_PENDING_ORACLE`，进 `smart_wallet_settlement_retry`。
- `SmartWalletClaimService`：链上认领赢利（`smart_wallet_claim_run/retry`）。⚠️ 未认领资金与对账闭环缺失(R19)。

---

## 2. leader 质量 / 选择闭环（alpha 来源）
- 画像与资格：`SmartWalletProfileStoreService.evaluateQualification`（winRate≥0.62、score≥0.70、trades≥25、PnL≥1000、回撤≤0.25）→ `qualified/watchEnabled/monitorTier=HOT`。
- 真值刷新：`SmartWalletProfileTruthRefreshService`（从 `smart_wallet_leader_trade_outcome` 重算；本轮已修胜率分母剔除平局）。
- 近期冷却：`recentWinRate7d<0.45` 等→ 7 天 observe 冷却（G1）。
- 滚动/漂移：`LeaderRollingPerformanceService`（默认关）、`LeaderBehaviorDriftService`（G2c）。
- 自动选池：`WeeklyTopLeaderAutoAllowlistJob`（每日提名"活跃+高 PnL+高胜率"入 allowlist，PAPER、30 天过期、0.5 倍仓）。⚠️ 幸存者偏差/无显著性检验(R13)。

---

## 3. 风控门全列表（执行顺序）
> `SmartWalletSignalRiskService.evaluate` 自上而下，命中即拒（记 `firstRejectGate`）。建议新增 **G0_RISK_HALT** 作为最前置熔断门（见 R1）。

| 阶段 | 门 | 作用 |
|---|---|---|
| G1 | MANUAL_BLOCKLIST / MANUAL_WATCH_DISABLED / OBSERVE_COOLDOWN / ALLOWLIST_PROFILE_MISSING / WATCHLIST_UNQUALIFIED | 名单与 leader 资格/冷却 |
| G2 | ARCHETYPE_OBSERVE_ONLY / BEHAVIOR_FAST_EXIT / LEADER_ROLLING_SUSPENDED / (G2c)LEADER_DRIFT_SEVERE | 行为画像/滚动/漂移 |
| G3 | TRIGGER_SIZE_TOO_SMALL / PRICE_OUT_OF_RANGE | 触发额与价带 |
| G4 | MAX_OPEN_POSITIONS(已废) / MAX_TOTAL_OPEN_COST / CAPITAL_DEPLETED | 组合资金（⚠️非原子R5） |
| G5 | SIGNAL_STALE | 信号新鲜度(max-signal-age=3600s) |
| G6 | CATEGORY_DISABLED / MARKET_NOT_ACTIVE / MARKET_EXPIRED / END_TOO_FAR / END_DATA_QUALITY | 品类与市场状态/时间窗 |
| G6c | ENTRY_EMPTY_ASK / ASK_SNAPSHOT_STALE | 入口空盘口护栏 |
| G7 | (sell 门) / LATE_POOR_ODDS | 卖出信号/临尾低赔率 |
| G8 | 深度门(minAcceptableDepthUsdc) / 取价手续费门 | 流动性/费用 |
| G10 | KELLY_NEGATIVE_EV | EV/Kelly（⚠️p_win语义P0-2） |
| G10a/b/c | ALLOWLIST_SIGNAL_BUFFERED / FINAL_STAKE_BELOW_MIN / EXPOSURE_LIMIT | 缓冲/最小单/敞口 |
| G11 | PROBABILITY / PROFITABILITY(净EV,默认关) / SLIPPAGE / SPREAD / SIGNAL_SCORE / PRICE_IMPACT / TIME_TO_EXPIRY | 概率/净EV/滑点/价差/打分/冲击 |
| G12 | ENTRY_STRATEGY_REJECTED | 入场策略路由 |
| G13 | SCALE_UP_* / DUPLICATE_POSITION_* | 加仓/重复仓 |

---

## 4. 调度与并发模型
- 全局 `@Scheduled` 共享池：`SchedulingConfig`（本会话改为核感知，下限 24，配 callTimeout 防卡死）。
- 高频专用：事件摄取走 `smartWalletEventSyncExecutor`（单线程）。
- 关键定时：摄取 30s、执行 poll 5s、候选重评 120s、stale 排水 120s、回收 5min、结算 5min、市场元数据/快照刷新等。
- 并发短板：资金预留无锁(R5)、去重无唯一索引(R7)、对立二元仓竞态(已知)。

---

## 5. 关键数据表
- 信号/任务：`smart_wallet_trade_event`、`smart_wallet_follow_task`、`wallet_follow_candidate`、`smart_wallet_signal_shadow`。
- 执行/持仓：`smart_wallet_order`、`smart_wallet_fill`、`smart_wallet_position`、`smart_wallet_trade_order`、`smart_wallet_cash_ledger`。
- 行情：`smart_wallet_market_info`、`smart_wallet_market_snapshot`。
- leader：`smart_wallet_profile`、`smart_wallet_leader_trade_outcome`、`smart_wallet_manual_allowlist/blocklist`、`leader_rolling_aggregation_daily`。
- 结算/认领：`smart_wallet_settlement_run/retry`、`smart_wallet_claim_run/retry`、`pm_resolution_audit`。
- 配置/告警：`strategy_runtime_config`、`strategy_alert`、`strategy_engine_flag`。

---

## 6. 已知风险与修复状态索引
- 运行问题与修复：`docs/problem/问题分析与修复方案-2026-06-01.md`（开单/跟单/胜率/盘口/结算）。
- 策略代码自检：`docs/problem/策略代码自检-盈利与胜率风险审计-2026-06-03.md`（纸面成交/leader 胜率/EV-Kelly/出场）。
- 全盘量化风险：`docs/problem/全盘风险审计-量化产品视角-2026-06-03.md`（R1–R20：熔断/资金/并发/集中度/对账/alpha）。

**本会话已落地（已部署或已编译待部署）**：盘口快照管道(/book+trades)、结算兜底(local-authoritative+worthless)、调度 callTimeout+核感知池、纸面开仓滑点、纸面阈值平仓改真实 bid、leader 胜率剔除平局。
**最高优先待办**：R1 组合级熔断 → R4/R5 在途资金+原子预留 → R6 集中度硬上限 → R2/R3 安全门 fail-safe+健康监控。
