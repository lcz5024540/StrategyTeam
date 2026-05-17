# AUDIT_* 告警 Onboarding 决策树（第十九批 #6）

> **目的**：把跟单 6 个 `AUDIT_*` AlertType（含第十八批新增的 `AUDIT_LEADER_ROLLING_HEALTH`）的
> 「看到告警 → 看哪个 trend → 调哪个字段」决策树落档。
>
> 适用范围：on-call / 运维 / 量化研究员收到 `strategy_alert` 表
> （`strategy_id=SMART_WALLET_AUDIT`）新告警时的统一应急 SOP。
>
> 历史/原文：本批前散落在主决议第七 / 十 / 十二 / 十五 / 十八节里，对 on-call 不友好。

---

## 0. 总览：6 个 AUDIT_* AlertType

所有跟单审计告警共 6 种类型，全部 level=WARN，写入 `strategy_alert` 表，
统一来源 `strategy_id=SMART_WALLET_AUDIT`：

| AlertType | 触发源 (Job/Service) | 默认调度 | 主要含义 |
| --- | --- | --- | --- |
| `AUDIT_LIVE_REPLAY_DIFF` | `LiveReplayDiffJob` | 每日 02:00 | T-1 日 replay 推演 PnL 与实盘 PnL 差异超阈值 |
| `AUDIT_SHADOW_VS_LIVE` | `ShadowVsLiveWeeklyReportService` | 每周一 06:00 | 上周影子记账（被拒信号）若放行 PnL 与实盘 PnL 偏差超阈值 |
| `AUDIT_SETTLEMENT_SOURCE` | `SettlementSourceAuditJob` | 每小时 00 分 | 卡 settlement market / parseFailure / claim 重试堆积 |
| `AUDIT_SLIPPAGE_POSTFACT` | `SlippagePostFactReportService` | 每周一 07:00 | 实际成交价 vs leader 入场价偏离 P95 超阈值 |
| `AUDIT_LEADER_DRIFT` | `LeaderDriftAggregationJob` | 每日 08:00 | 聚合活跃 leader 的行为漂移分布异常 |
| `AUDIT_RESOLUTION_TRIDIFF` | `ResolutionTriDiffJob` | 每 4 小时 | 我方 / Gamma / UMA 三方结算判定不一致计数超阈值 |
| `AUDIT_LEADER_ROLLING_HEALTH` ⭐ | `LeaderRollingMonitorJob` (第十八批) | 每日 09:30 | W2-T2 灰度 cooling 比例 / overall winRate 异常 |

> 触发后单一类型同 strategyId 已存在未恢复告警时 `StrategyAlertService.fireAlert` 内置去重 →
> 同一异常 *持续未解决* 不会重复刷表，直到运营在 `strategy_alert` 表 `is_resolved=1`。

---

## 1. 应急决策树（按 AlertType 分支）

### 1.1 `AUDIT_LIVE_REPLAY_DIFF` (W1-T1)

**典型 message**：`pnl_diff_bps=XX signal_miss_pct=YY pnlExceeded=true|false signalMissExceeded=true|false`

**第一步**：看 Web 端 trend
```
GET /smart-wallet/audit/live-replay-diff/latest   # 最新一日
GET /smart-wallet/audit/live-replay-diff/history?limitDays=30  # trend
```

**第二步**：定位是 `pnlDiffExceeded` 还是 `signalMissExceeded`

| 信号 | 可能原因 | 验证步骤 |
| --- | --- | --- |
| `pnlDiffExceeded=true` | 滑点恶化 / leader 入场价已 priced-in / 异常 fill | 同日 `GET /audit/slippage-postfact?lookbackDays=7` 看分布 |
| `signalMissExceeded=true` | 门控变更后 SignalRiskService 接受率漂移 / properties 配置改了 | grep `[PRICE_IMPACT_SRC]` / `[PRICE_IMPACT_*]` 决策日志 |

**第三步**：调阈值（仅当 trend 持续 ≥ 3 天异常）
- `smart-wallet.audit.live-replay-pnl-diff-bps`（默认 100）
- `smart-wallet.audit.live-replay-signal-miss-pct`（默认 0.05）

**对开单影响**：纯只读 audit，不影响实时下单决策；但持续告警常预示门控失效或参数漂移。

---

### 1.2 `AUDIT_SHADOW_VS_LIVE` (W1-T4)

**典型 message**：top-3 reject_layer 摘要 + pnlDiff

**第一步**：看 Web 端按 reject_layer 分桶
```
GET /smart-wallet/shadow/records?status=SETTLED&limit=200  # 详情列表
GET /smart-wallet/audit/shadow-estimator-source?lookbackDays=7  # VWAP vs PROXY 验证
```

**第二步**：识别 "被拒得最贵" 的 layer

| 高 PnL 损失的 reject_layer | 决策 | 调字段 |
| --- | --- | --- |
| `G11_PRICE_IMPACT_REJECT` | W2-T4 拒绝阈值过紧 | 上调 `priceImpactRejectBps` |
| `G11_SPREAD_P70_REJECT` | W2-T1.4 价差阈值过紧 | 上调 `spreadP70RejectFactor` |
| `G2b_LEADER_ROLLING_SUSPEND` | leader 黑名单误伤 | 用 `manualAllowlist*bypass` 临时白名单 |
| `G2c_LEADER_DRIFT_SEVERE` | leader 行为画像门过严 | 看 `/leader-drift/{wallet}` 详情，调 `leaderDriftSevereCosineThreshold` |

**对开单影响**：纯只读，不影响下单。本告警是「门控是否过严」的核心反馈。

---

### 1.3 `AUDIT_SETTLEMENT_SOURCE` (W1-T2)

**典型 message**：parseFailure=N resolutionDelayed=M pendingSettlementOverdue=K claimFailureMax=X

**第一步**：看 Web 端
```
GET /smart-wallet/audit/settlement-source
```

**第二步**：按异常维度处理

| 异常维度 | 含义 | 处理 |
| --- | --- | --- |
| `parseFailure > 0` | resolveWithAudit 解析失败 | 看 ResolutionAudit (`/audit/resolution/{marketId}`) 详情 |
| `resolutionDelayed > 0` | `closed=true` 但 UMA 未 resolved | UMA Oracle 争议（外部因素，等 UMA） |
| `pendingSettlementOverdue > 0` | 仓位 status=PENDING_SETTLEMENT 超 24h | 跑 `SmartWalletSettlementSyncService` 主动同步 |
| `claimFailureMax >= 5` | claim 链上重试失败 | 检查 onchain gas / nonce / 私钥状态 |

**对开单影响**：⚪ 无直接影响；间接：长期 stuck market 锁资金，影响后续开单容量。

---

### 1.4 `AUDIT_SLIPPAGE_POSTFACT` (W2-T4.5)

**典型 message**：category=POLITICS p95=Xbps overall p95=Ybps

**第一步**：看 Web 端按 category 分桶
```
GET /smart-wallet/audit/slippage-postfact?lookbackDays=7
```

**第二步**：决策树

| 现象 | 可能原因 | 处理 |
| --- | --- | --- |
| 某品类 P95 单点尖刺 | 短期市场流动性枯竭 | 看 leader 入场时段，可能加入 `timeToExpiryExemptMarketTypes` |
| 整体 P95 持续上行 | leader 池 alpha 衰减 / 跟单饱和 | 配合 W2-T2 leader-rolling 看是否 cooling 比例同步上升 |
| VWAP 桶 P95 > PROXY 桶 P95 | VWAP 估算反而更差 | `enable-vwap-slippage-estimator=false` 临时回退 |

**对开单影响**：⚪ 无直接影响；间接：调 `priceImpactDowngradeBps` / `priceImpactSevereDowngradeBps` 时本告警是核心依据。

---

### 1.5 `AUDIT_LEADER_DRIFT` (W2-T6)

**典型 message**：(drifted+severe)/evaluated=X% topDrifted 3 个 leader 摘要

**第一步**：聚合 + 单点
```
GET /smart-wallet/audit/leader-drift-aggregation?lookbackHours=24
GET /smart-wallet/leader-drift/{wallet}  # 单 leader 详情
```

**第二步**：决策

| 现象 | 可能原因 | 处理 |
| --- | --- | --- |
| 同一 leader 反复 DRIFTED_SEVERE | 账号被盗 / 切换策略实验 / 合规风险 | 通过 `manualBlocklist` 加黑名单 |
| 多个 leader 同时 DRIFTED 且品类一致 | 整体市场行情切换（如政治→加密热度） | 不处理，让 30 天 lookback 自动 re-baseline |
| SEVERE count 周期性突刺 | 数据脏（trade_event 同步 lag） | 看 leader 最近 trade_event 时间分布 |

**第三步（启用门控前）**：当前 prod / test 默认 `enableLeaderDriftGate=true`；如要灰度回退 `enable-leader-drift-gate=false`。

**对开单影响**：⬇️ 启用后 SEVERE leader 拒单 / DRIFTED leader 仓位 ×0.25；告警本身只读。

---

### 1.6 `AUDIT_RESOLUTION_TRIDIFF` (W1-T2.2)

**典型 message**：OURS_VS_UMA=X OURS_VS_GAMMA=Y UMA_VS_GAMMA=Z HISTORY_CONFLICT=W + topConflicted 3 个 marketId

**第一步**：看 web 端
```
GET /smart-wallet/audit/resolution-tridiff?lookbackHours=24
GET /smart-wallet/audit/resolution/{marketId}  # 单市场审计快照
```

**第二步**：决策

| 主要冲突类型 | 可能原因 | 处理 |
| --- | --- | --- |
| `OURS_VS_UMA` 多 | 我方预解析过早（UMA 仍 disputed） | 检查 `MarketResolutionResolver` 解析窗口 |
| `OURS_VS_GAMMA` 多 | 我方解析漏判（Gamma 已 closed） | 跑 ResolutionLiveRefresh 强制刷新 |
| `UMA_VS_GAMMA` 多 | 平台层不一致 | 上报 Polymarket / UMA support |
| `HISTORY_CONFLICT` 多 | 我方 winningOutcome 漂移 | 看 `/audit/resolution/{marketId}` 历史所有快照 |

**对开单影响**：⚪ 无直接影响；间接：长期 stuck resolution 锁资金。

---

### 1.7 `AUDIT_LEADER_ROLLING_HEALTH` ⭐ (W2-T2 灰度自动化，第十八批)

**典型 message**：`coolingRatio=X overallWinRate=Y healthStatus=WARN_*`

**第一步**：看 Web 端 trend
```
GET /smart-wallet/audit/leader-rolling-aggregation/history?historyDays=30
GET /smart-wallet/audit/leader-rolling-aggregation?lookbackDays=14
GET /smart-wallet/audit/copy-trading-health  # 第十九批 #5: 三件套总览
```

**第二步**：按 healthStatus 决策

| healthStatus | 触发条件 | 含义 | 处理 |
| --- | --- | --- | --- |
| `WARN_HIGH_COOLING` | coolingRatio >= 0.40 && overallWin >= 0.48 | 大量 leader 进 cooling，但总体仍盈利 | 看 top-N cooling leader，可能要补 leader 池 |
| `WARN_LOW_WIN_RATE` | coolingRatio < 0.40 && overallWin < 0.48 | leader 数量正常但整体胜率下滑 | 配合 `AUDIT_SHADOW_VS_LIVE` 看是否门控过松 |
| `WARN_BOTH` | coolingRatio >= 0.40 && overallWin < 0.48 | 双重恶化（严重） | 紧急排查 leader 池质量 + 门控失效，必要时 `enable-leader-rolling-gate=true` 主动收紧 |

**第三步**：调阈值（仅持续 ≥ 7 天异常时）
- `audit.leader-rolling-aggregation-warn-cooling-ratio`（默认 0.40）
- `audit.leader-rolling-aggregation-warn-min-overall-win-rate`（默认 0.48）

**对开单影响**：⚪ 无直接影响（监控 Job 只读 + 写 audit 表）。**但**该告警是 W2-T2 灰度→prod 决策的核心信号；持续 OK ≥ 7 天才考虑 `enable-leader-rolling-gate=true` 上 prod。

---

## 2. 跨告警关联决策

某些场景下多个 AlertType 会同时 fire，含义比单个 alert 更丰富：

| 告警组合 | 含义 | 优先处理 |
| --- | --- | --- |
| `AUDIT_LIVE_REPLAY_DIFF.pnlExceeded` + `AUDIT_SLIPPAGE_POSTFACT` 同时 | 实际滑点 > 估算滑点，门控失效 | 调 `priceImpactDowngradeBps` |
| `AUDIT_LEADER_ROLLING_HEALTH=WARN_LOW_WIN_RATE` + `AUDIT_SHADOW_VS_LIVE.shadowMinusLive < 0` | 整体放行过松，被拒信号本来是对的 | 收紧门控（回切 W2-T3 HARD_GATE） |
| `AUDIT_LEADER_DRIFT` SEVERE 多 + `AUDIT_RESOLUTION_TRIDIFF` UMA_VS_GAMMA 多 | 可能 polymarket 平台异常 | 上报 Polymarket / 等平台恢复 |
| `AUDIT_SETTLEMENT_SOURCE.pendingSettlementOverdue` + `AUDIT_LEADER_ROLLING_HEALTH=WARN_HIGH_COOLING` | 卡 settlement 锁资金导致新仓被挤出，cooling leader 滞留 | 优先跑 settlement sync 释放资金 |

---

## 3. 通用回退预案

每个 `AUDIT_*` 都对应 1-2 个配置开关，统一一行配置零重启回退：

| AlertType | 主要回退字段 |
| --- | --- |
| `AUDIT_LIVE_REPLAY_DIFF` | `audit.live-replay-diff-enabled=false` |
| `AUDIT_SHADOW_VS_LIVE` | `audit.shadow-weekly-report-enabled=false` |
| `AUDIT_SETTLEMENT_SOURCE` | `audit.settlement-source-audit-enabled=false` |
| `AUDIT_SLIPPAGE_POSTFACT` | `audit.slippage-post-fact-enabled=false` |
| `AUDIT_LEADER_DRIFT` | `audit.leader-drift-aggregation-enabled=false` + `signal-risk.enable-leader-drift-gate=false`（双层关） |
| `AUDIT_RESOLUTION_TRIDIFF` | `audit.resolution-tridiff-enabled=false` |
| `AUDIT_LEADER_ROLLING_HEALTH` | `audit.leader-rolling-aggregation-enabled=false` |

> **重要**：上述回退仅关闭审计告警，不影响 hot path 风控门 (W2-T2/3/4/5/6 等)。
> 如要真正关闭风控门，对应字段是 `signal-risk.enable-*-gate=false`，
> 详见 `audit-observability/w2-risk-gates.md`。

---

## 4. 上报 / 协作约定

- 任一 `AUDIT_*` 持续 ≥ 3 天未恢复 → on-call 在 #ops 频道 @ 量化负责人评审
- 任一 `AUDIT_*` 连续 ≥ 7 天 → 主决议增加专项节，记录调阈值 / 改算法 / 是否回退新批改动
- `AUDIT_LEADER_ROLLING_HEALTH=WARN_BOTH` 紧急级别等同 prod incident（双重恶化）
- 告警 resolve：运维在 `strategy_alert` 表手动置 `is_resolved=1, resolved_at=NOW()`，
  或等 audit Job 下次跑发现 health 已恢复 OK 时由人手动关 (Job 不会自动 resolve)

---

## 5. 第十九批新增：统一 health 总览端点

第十九批 #5 新增 `GET /smart-wallet/audit/copy-trading-health` 把 W2-T2 LeaderRolling trend
+ W2-T6 LeaderDrift 实时聚合 + W1-T1 LiveReplayDiff 最新一日 整合为单一面板视图。

- 返回字段 `overallStatus = OK / WARN`（任一子源 WARN → WARN）
- 任一子源失败 → 该字段 null + `errors` 数组记录原因，不阻塞另外两源
- 推荐 on-call 收到任何 `AUDIT_*` 后第一步：先 curl 该端点拿整体盘面再下钻

```
GET /smart-wallet/audit/copy-trading-health?historyDays=14&driftLookbackHours=24
```

---

> *最后更新*：2026-05-16 第十九批 — AUDIT_* 告警 onboarding 决策树落档
