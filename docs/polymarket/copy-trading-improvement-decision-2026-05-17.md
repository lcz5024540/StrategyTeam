---
title: "跟单策略改进决议备忘录 (2026-05-17 起)"
type: retro
status: active
tags: [polymarket, copy-trading, retro]
created: 2026-05-17
updated: 2026-05-17
---

# 跟单策略改进决议备忘录 (2026-05-17 起)

> **📚 主题导航**：当下事实快照见 [`audit-observability/`](./audit-observability/)；按主题速查
> 用 [`audit-observability-index.md`](./audit-observability-index.md)。
>
> **📜 历史回溯**：2026-05-16 第 1-16 批的完整决议时间线已通过 `git log` 保留，
> 本文档承接第十七批起的新决议；新人入门只需读本文 + 主题精华子文档即可。

---

## 一、Context inherited from archived 2026-05-16

简短状态承接（详细历史请回看归档文档）：

### 1.1 已落地的能力（W1+W2 已交付）

| 类别 | 内容 |
| --- | --- |
| **W1 闭环数据** | trace_id 五表覆盖 / Shadow 记账 + 周报 / Live↔Replay diff 历史 / Resolution audit + 三方 diff |
| **W2 风控门控** | T1 流动性 P30 + T1.4 价差 P70 / T2 leader 滑动窗口 / T3 共识 BOOST_ONLY / T4 价格冲击三段式 + MAKER 路由 / T5 距结算时间 / T6 行为漂移 |
| **Tier 通用化** | A: TraceIds + TraceContext + StrategyAuditAlertSink；B: BehaviorVectorizer + CentroidDriftDetector + SignalRiskGate/Pipeline 契约 |
| **Web BFF 接口** | 20 个端点（trace / shadow / audit / leader / market-liquidity / live-replay-diff / resolution / leader-rolling-aggregation 等） |
| **Audit Jobs** | 10 个：LiveReplayDiff / Shadow*  / SettlementSourceAudit / SlippagePostFact / LeaderDriftAggregation / MarketLiquidityPercentileSnapshot / ResolutionTriDiff / MakerOnlyTimeoutTracker 等 |
| **告警通道** | AuditAlertSink 统一收口；5 个 AUDIT_* AlertType (LIVE_REPLAY_DIFF / SHADOW_VS_LIVE / SETTLEMENT_SOURCE / SLIPPAGE_POSTFACT / LEADER_DRIFT / RESOLUTION_TRIDIFF) |

### 1.2 当前灰度状态

- **test profile 已开**: VWAP estimator / MakerOnly 撤单 / Live refresh / W2-T6 leader-drift / W2-T2 leader-rolling
- **prod profile 已开**: W2-T1 流动性 P30 / W2-T1.4 价差 P70 / W2-T6 leader-drift / VWAP / MakerOnly / Live refresh
- **prod 待开**: W2-T2 leader-rolling（test 灰度 1-2 周稳定后上）
- **跨策略**: 天气 / BTC 5min 待 Tier B/C 抽取完成后排期

### 1.3 文档体系

```text
docs/polymarket/
├── copy-trading-improvement-decision-2026-05-17.md  # 本文件 (当前生效主决议)
├── audit-observability-index.md                      # 主题速查
├── audit-observability/                              # 主题精华子文档
│   ├── w1-data-closure.md            # W1 闭环数据
│   ├── w2-risk-gates.md              # W2 风控门控
│   ├── tier-generalization.md        # 跨策略 Tier A/B/C
│   ├── web-bff-interfaces.md         # 21 个 BFF 端点清单
│   ├── web-frontend-integration.md   # 前端联调辅助
│   ├── audit-alert-onboarding.md     # AUDIT_* 告警 onboarding
│   ├── maintenance.md                # 文档维护流程
│   └── w2-t2-grayscale-sop.md        # W2-T2 灰度 SOP (第十七批 + 第十八批自动化)
├── cross-strategy-reusable-infrastructure.md    # 跨策略基础设施
├── multi-strategy-architecture-design.md         # 多策略架构
├── follow-trade-entry-exit-strategy.md           # 入/出场策略
├── capital-recycle-exit-design.md                # 资金回收
├── smart-wallet-follow-strategy-analysis.md      # 策略分析
└── prod-deployment-checklist-w2-t6.md            # 部署 checklist
```

---

## 二、本批（第十七批 2026-05-17）落地：执行 #1 / #4 / #5（跳过 #2 #3 #6）

按用户「执行 1, 4, 5」指令落地 3 项（跳过飞书邮件 / 天气 backlog / 跨策略原型）。

### 2.1 #5 SignalRiskGate 接口抽取（Tier B 第三组）

**已就位**（linter / 自动化已在第十六批末尾完成）：

- `common-service/.../service/risk/SignalRiskGate.java` — 通用风控门接口（含 `gateId()` / `isApplicable()` / `evaluate()` / `order()`）
- `common-service/.../service/risk/SignalRiskGateDecision.java` — 通用决策 DTO（gateName / Outcome 三态 / stakeMultiplier / reasons / executionHint）
- `common-service/.../service/risk/SignalRiskPipeline.java` — 责任链编排器（按 order 升序 / fail-fast 可选 / 倍率累乘）

本批仅追加 deprecation shim `GateDecision.java`（指向 `SignalRiskGateDecision`，避免重名混淆）。

### 2.2 #1 W2-T2 灰度观察 SOP

新增 [`audit-observability/w2-t2-grayscale-sop.md`](./audit-observability/w2-t2-grayscale-sop.md)：

- **每日 5 min checklist**：抓数据 / 看 3 个核心指标 / top-15 cooling leader review
- **每周 30 min checklist**：rolling + vwap + replay 三方对照 / 周报输出
- **阈值调整决策树**：按 cooling 比例 4 档分支（< 1% / 1-15% / 15-30% / > 30%）+ 整体 winRate 判定
- **上 prod 触发条件**：4 项硬指标全部满足
- **回退预案**：单字段 `enable-leader-rolling-gate=false` 零重启
- **与其他门交互**：W2-T3 / W2-T4 / W2-T6 叠加效果与归因建议
- **SOP 自身月度 review 机制**

### 2.3 #4 主决议归档（第十八批已物理清理）

- 第十七批：旧主决议 `copy-trading-improvement-decision-2026-05-16.md` 头部加 🗄️ ARCHIVED 横幅
- 第十七批：创建本新主决议，第一节 Context 承接 + 第二节起作新批次落地
- **第十八批补充**：根据用户「冗余文档清理」要求，旧主决议（含归档准备评估、子文档 README）一并物理清理；
  16 批历史时间线改为通过 `git log -- docs/polymarket/copy-trading-improvement-decision-2026-05-16.md` 回溯

---

## 三、自动化审视（按调度任务要求 · 第十七批）

| 维度 | #5 接口抽取 | #1 SOP 文档 | #4 主决议归档 |
| --- | --- | --- | --- |
| 开单效率 | ⚪ 无（仅接口契约） | ⚪ 无（运维文档） | ⚪ 无 |
| 资金使用率 | ⚪ 无 | ⚪ 无 | ⚪ 无 |
| 是否会开单 | ⚪ 无 | ⚪ 无 | ⚪ 无 |
| 盈亏点 | ⚪ 无（间接：Tier B 让后续抽 SignalRiskGate 实现更容易） | ⚪ 无（间接：让运维快速发现 W2-T2 异常） | ⚪ 无 |
| 胜率 | ⚪ 无 | ⚪ 无 | ⚪ 无 |
| 回退路径 | 删 GateDecision deprecation shim | 删 SOP 文档 | 旧主决议 ARCHIVED 横幅回退 / 删新主决议 |
| 默认开关 | 仅接口定义（无 bean） | 仅文档 | 文档归档 / 新文档 |

---

## 四、改动清单

**新增 3 个文件**：
- `common-service/.../service/risk/GateDecision.java` — deprecation shim
- `docs/polymarket/audit-observability/w2-t2-grayscale-sop.md`
- `docs/polymarket/copy-trading-improvement-decision-2026-05-17.md`（本文件）

**修改 1 个文件**：
- `copy-trading-improvement-decision-2026-05-16.md` — 头部加 🗄️ ARCHIVED 横幅（第十八批后已清理）

**未动文件**：
- `common-service/.../service/risk/SignalRiskGate.java` + `SignalRiskGateDecision.java` + `SignalRiskPipeline.java`（linter 在第十六批末尾已完成）

**静态校验**：deprecation shim brace OK (2/2)；其它仅文档。

---

## 五、Web 接口现状（共 20 个，未变）

详见 [`audit-observability/web-bff-interfaces.md`](./audit-observability/web-bff-interfaces.md) 与 [`web-frontend-integration.md`](./audit-observability/web-frontend-integration.md)。

---

## 六、下一轮（第十八批）建议

1. **W2-T2 灰度数据观察**（按 SOP 每日 / 每周跑，1-2 周后评估上 prod 时机）
2. **飞书 / 邮件 audit sink**（多次跳过，运维确认是否真需要）
3. **天气策略 backlog 排期评估**（基于 `cross-strategy-reusable-infrastructure.md` 与第十七批已就位的 SignalRiskGate 契约）
4. **跨策略基础设施使用样例**（用 BehaviorVectorizer + CentroidDriftDetector + SignalRiskGate 写"天气 actor drift" 最小原型，验证三件套完整链路）
5. **AlertType 跨策略命名约定**（当前 5 个 AUDIT_* 都是跟单专用，新增天气时如何命名）
6. **主题子文档月度更新**（按 maintenance.md 第三节 checklist，每月 1 日跑）

---

## 七、变更管理（继承自旧主决议第九节）

- **本备忘录是后续决策的基线**。新议题如与本备忘录冲突，需先 update 本文件再实施。
- 文档命名规则：`copy-trading-improvement-decision-YYYY-MM-DD.md`，每次归档新生成同前缀文件并加 archive 引用。
- 归档触发条件 / 流程 详见 [`audit-observability/maintenance.md`](./audit-observability/maintenance.md) 第四节。

---

## 八、第十八批（2026-05-16）落地：W2-T2 灰度数据观察 自动化

按用户「继续执行 W2-T2 灰度数据观察」指令，把第十七批的 SOP 从「每日人工 curl + 表格」升级为「Job + 历史落表 + 异常告警 + trend 端点」一条龙。

### 8.1 背景与目标

第十七批刚落地的 [`w2-t2-grayscale-sop.md`](./audit-observability/w2-t2-grayscale-sop.md) 把「每日运维跑 `/audit/leader-rolling-aggregation` + 手抄 4 列指标到表格」作为灰度→prod 的判断依据。这种 SOP 在批次稀疏、关注度高时可用，但长期会被遗忘 / 数据缺失 / trend 无人看。第十八批升级为：

| 维度 | 第十七批 SOP | 第十八批自动化 |
| --- | --- | --- |
| 跑频 | 每日 09:30 人工 curl | `LeaderRollingMonitorJob` 每日 09:30 (Asia/Shanghai) |
| 存储 | 运维手抄表格 / Excel | `leader_rolling_aggregation_daily` 表 (主键 `as_of_date+lookback_days`) |
| Trend | 翻历史 Excel | `GET /audit/leader-rolling-aggregation/history?historyDays=30` |
| 异常处置 | 看数字 → 微信群通知 | publish `AUDIT_LEADER_ROLLING_HEALTH` (WARN) |
| Health 状态机 | 人脑判断 | `OK / WARN_HIGH_COOLING / WARN_LOW_WIN_RATE / WARN_BOTH` |
| 阈值 | 文档里 | `smart-wallet.audit.leader-rolling-aggregation-warn-*` |
| 回退 | — | 单字段 `leader-rolling-aggregation-enabled=false` |

### 8.2 关键产物

**SQL / Schema**：
- `sql/polymarket/leader_rolling_aggregation_daily_migration.sql` — 新表，主键 `(as_of_date, lookback_days)` 唯一，`idx_health_status (health_status, as_of_date)` 支持「最近 N 天 WARN 行」过滤

**Java（common-model + common-dao）**：
- `LeaderRollingAggregationDailyEntity` — 18 个字段，含 `topCoolingLeadersJson` (TEXT) + `healthStatus` (VARCHAR(32))
- `LeaderRollingAggregationDailyMapper` — 标准 `BaseMapper`

**Java（common-service 配置）**：
- `SmartWalletProperties.Audit` 加 7 个字段（`leaderRollingAggregation*`：enabled / cron / zone / lookbackDays(List) / warnCoolingRatio / warnMinOverallWinRate / topNToCapture / minEvaluatedCount）
- 顺带 cleanup：删除第十一批 W1-T2.2 段重复的 `resolutionTriDiff*` 字段（与同一 `Audit` 类内 Gamma/UMA 段重名 → 之前编译破坏；grep 全仓无消费者，安全删除）

**Java（coin-strategy-job）**：
- `engine/monitor/AlertType.java` 加 `AUDIT_LEADER_ROLLING_HEALTH(AlertLevel.WARN, "Leader 滑动窗口胜率灰度健康异常")`
- `smartwallet/service/LeaderRollingMonitorService.java`（~360 行）— 算法与 web BFF `LeaderRollingAggregationReadService` 完全对齐（windowSize=20 取最新 N 笔 ENTRY / minSamples=8 / minWinRate=0.45），新增：
  - 多 `lookbackDays` 档循环（默认 `[7]`，可改 `[7,14]`）
  - `coolingRatio` 与 `overallWinRate` 双指标 health 判定
  - `topCoolingLeadersJson` 序列化（Jackson，top-N cooling-first 排序）
  - 幂等 upsert（先 `selectOne (as_of_date+lookbackDays)` 再 insert/updateById）
  - 告警 sink publish (sink 关或异常时仅日志)
- `smartwallet/service/LeaderRollingMonitorJob.java` — `@Scheduled cron=0 30 9 * * *` 包装，复用现有 `AtomicBoolean running` 防重入模式（与 LeaderDriftAggregationJob 对齐）

**Java（coin-strategy-web BFF）**：
- `model/smartwallet/LeaderRollingAggregationHistoryView.java` + `Row` — 含 `latestHealthStatus` / `latestAsOfDate` 让前端做 banner
- `service/smartwallet/LeaderRollingAggregationHistoryReadService.java` — 直查 daily 表，按 `asOfDate desc, lookbackDays asc` 排序，`historyDays` 默认 30 / 上限 180
- `controller/smartwallet/SmartWalletAuditController.java` 加端点 `GET /audit/leader-rolling-aggregation/history` (第 21 个端点)

**文档**：
- `audit-observability/w2-t2-grayscale-sop.md` 头部加「零、自动化模式」节，标注一/二节自动化模式下只作复核 / 降级 SOP

### 8.3 health 阈值与状态机

```text
                              ┌──────────────────────────────────────────┐
evaluated < minEvaluatedCount ┤  → OK  (样本不足, 灰度初期常见)            │
                              └──────────────────────────────────────────┘
                              ┌──────────────────────────────────────────┐
否则:                          │  coolingRatio < 0.40 && overallWin >= 0.48 → OK
  warnCoolingRatio = 0.40     │  coolingRatio >= 0.40 && overallWin >= 0.48 → WARN_HIGH_COOLING
  warnMinOverallWinRate = 0.48│  coolingRatio < 0.40 && overallWin <  0.48 → WARN_LOW_WIN_RATE
                              │  coolingRatio >= 0.40 && overallWin <  0.48 → WARN_BOTH
                              └──────────────────────────────────────────┘
```

WARN_* 三态都会 publish 同一类型 `AUDIT_LEADER_ROLLING_HEALTH`，靠 `healthStatus` 字段区分 root cause。

### 8.4 对开单 / 资金 / 胜率影响评估

| 维度 | 影响 | 说明 |
| --- | --- | --- |
| 开单效率 | ⚪ 无 | Job 只读 + upsert audit 表，不在 hot path |
| 资金使用率 | ⚪ 无 | 不参与下单决策 |
| 是否会开单 | ⚪ 无 | LeaderRollingPerformanceService 仍是 hot path 门控 source of truth |
| 盈亏点 | ⚪ 无 | 同上 |
| 胜率 | ⚪ 无（直接） / 🟢 间接正向 | 间接：让 ops 提前看到 leader 池胜率退化 → 主动撤稳 / 调阈值 / 加 leader |
| 资源开销 | ⚪ 极低 | 每日一次扫描 + 一条 (or 多 lookback) insert，window 内 BUY+ENTRY 订单数最多几千行 |

### 8.5 自动化审视

| 维度 | 状态 |
| --- | --- |
| 配置开关 | ✅ `smart-wallet.audit.leader-rolling-aggregation-enabled` 默认 `true` |
| 告警闭环 | ✅ `AUDIT_LEADER_ROLLING_HEALTH` (WARN) 复用 `audit.alert-sink-enabled` 总开关 |
| 测试覆盖 | ⏳ 待 follow-up：MonitorService 单测 / Mock mapper / 三态 health 断言 |
| 文档同步 | ✅ SOP 头部已加「零、自动化模式」节；本节 8 |
| 回退路径 | ✅ `leader-rolling-aggregation-enabled=false` 单字段零重启 |
| 幂等性 | ✅ 主键 `(as_of_date, lookback_days)` + `selectOne→insert/updateById` |
| Job 防重入 | ✅ `AtomicBoolean running.compareAndSet` (同 LeaderDriftAggregationJob) |
| 静态校验 | ✅ 9 个 java 文件 brace + 圆括号全 balanced |

### 8.6 改动清单

**新增 6 个文件**：
- `sql/polymarket/leader_rolling_aggregation_daily_migration.sql`
- `coin-strategy-common/common-model/.../entity/LeaderRollingAggregationDailyEntity.java`
- `coin-strategy-common/common-dao/.../mapper/LeaderRollingAggregationDailyMapper.java`
- `coin-strategy-job/.../smartwallet/service/LeaderRollingMonitorService.java`
- `coin-strategy-job/.../smartwallet/service/LeaderRollingMonitorJob.java`
- `coin-strategy-web/.../web/model/smartwallet/LeaderRollingAggregationHistoryView.java`
- `coin-strategy-web/.../web/service/smartwallet/LeaderRollingAggregationHistoryReadService.java`

**修改 4 个文件**：
- `coin-strategy-job/.../engine/monitor/AlertType.java` — 加 `AUDIT_LEADER_ROLLING_HEALTH`
- `coin-strategy-common/.../config/SmartWalletProperties.java` — 加 7 个 W2-T2 监控字段 + cleanup 重复字段
- `coin-strategy-web/.../controller/SmartWalletAuditController.java` — 加端点 + 注入新 service（第 21 个端点）
- `docs/polymarket/audit-observability/w2-t2-grayscale-sop.md` — 头部加自动化模式说明

### 8.7 下一轮（第十九批）建议

1. **MonitorService 单测**：mock 三个 mapper / 桩 audit sink，断言四个 health 状态正确触发
2. **prod profile 灰度本批 audit Job**：默认 enabled=true 已合理；先观察 test 一周再上 prod
3. **trend dashboard 接入**：让前端把 `latestHealthStatus` 做成首页 banner，rows 出 trend 线图
4. **多 lookback 档上线**：test 7d 稳定后改 `[7,14]`，对比短/长期 trend 一致性
5. **拉齐 LeaderDriftAggregation / ShadowWeeklyReport / W2-T2 三个 trend 端点**到一个 unified `/audit/copy-trading-health` 入口（一次 curl 看三件套）
6. **第十八批同型告警 onboarding 文档**（统一 5 个 AUDIT_* 的"看到告警 → 看哪个 trend → 调哪个字段"决策树）
7. **飞书 / 邮件 audit sink**（依然多次跳过，运维确认是否真需要）

---

> *最后更新*：2026-05-16 — 第十八批（W2-T2 灰度数据观察 SOP→自动化升级）

---

## 九、第十九批（2026-05-16 自动化追加）落地：执行下一轮 #1 / #5 / #6（跳过 #2 / #3 / #4 / #7）

按调度任务"根据该文档中设计的计划去实现"指令对第八节 8.7 下一轮 7 项做盘点。
本批选定以下 3 项纯低风险自动化型工作（其余依赖运行时数据 / 前端联调 / 运维凭证）：

- **#1** `LeaderRollingMonitorService` 单元测试 — mock 三个 mapper + 桩 audit sink，断言 4 个 health 状态正确触发 + upsert 幂等 + sink 关闭路径
- **#5** 统一 `/audit/copy-trading-health` 端点 — 把 LeaderRolling trend + LeaderDrift aggregation + LiveReplayDiff latest 三件套聚合为单一面板视图
- **#6** AUDIT_* 告警 onboarding 文档 — 7 个 AlertType 的"看到告警 → 看哪个 trend → 调哪个字段"决策树落档

### 9.1 #1 落地：LeaderRollingMonitorServiceTest

**新增**：`coin-strategy-job/src/test/.../service/LeaderRollingMonitorServiceTest.java`（~330 行）

覆盖 8 个测试用例：
1. **HEALTH_OK · 样本不足** — 5 leader × 8 笔全胜（evaluated=5 < minEvaluatedCount=10）→ OK + 不告警
2. **HEALTH_OK · 健康分布** — 9 active + 3 cooling（coolingRatio=0.25, overallWin=0.5625）→ OK + 不告警
3. **HEALTH_WARN_HIGH_COOLING** — 6 active + 6 cooling（coolingRatio=0.50, overallWin=0.50）→ WARN + publish 1 次
4. **HEALTH_WARN_LOW_WIN_RATE** — 9 active + 3 cooling（coolingRatio=0.25, overallWin=0.40625）→ WARN + publish
5. **HEALTH_WARN_BOTH** — 5 active + 7 cooling（coolingRatio=0.58, overallWin=0.333）→ WARN + publish
6. **upsert 幂等** — selectOne 命中已存在行 → 走 updateById 而非 insert
7. **dryRun 公共方法** — 不落库 / 不告警, 仅返回 snapshot
8. **sink 关闭** — `alertSinkEnabled=false` 时即便 health != OK 也不 publish

**Tier B 验证维度**：
- Mock 使用 `@ExtendWith(MockitoExtension.class)` + `@MockitoSettings(LENIENT)`，与既有 `SmartWalletAccountingServiceTest` 风格对齐
- Lambda QueryWrapper 直接 `any()`，不模拟 SQL 语义（与既有测试一致）
- Mockito `ArgumentCaptor` 捕获 `AlertType`，验证告警类型正确

### 9.2 #5 落地：统一 `/audit/copy-trading-health` 端点

**新增 2 个文件**：
- `coin-strategy-web/.../web/model/smartwallet/CopyTradingHealthView.java` — 视图 DTO（overall + 3 个 sub-summary + errors）
- `coin-strategy-web/.../web/service/smartwallet/CopyTradingHealthReadService.java` — 聚合 service

**修改 1 个文件**：
- `coin-strategy-web/.../web/controller/smartwallet/SmartWalletAuditController.java` — 加端点 + 注入新 service

**端点签名**：
```text
GET /smart-wallet/audit/copy-trading-health?historyDays=14&driftLookbackHours=24
```

**响应**：
```json
{
  "generatedAt": "...",
  "overallStatus": "OK | WARN",
  "errors": ["leader-rolling:RuntimeException", ...] | null,
  "leaderRolling": {
    "latestHealthStatus": "OK | WARN_HIGH_COOLING | ...",
    "latestAsOfDate": "...",
    "recentWarnDays": N,
    "historyDays": 14,
    ...
  },
  "leaderDrift": {
    "status": "OK | WARN",
    "lookbackHours": 24,
    "driftedRatio": "0.XX",
    ...
  },
  "liveReplayDiff": {
    "status": "OK | WARN",
    "asOfDate": "...",
    "pnlDiffExceeded": true|false,
    ...
  }
}
```

**关键设计点**：
- **不重复算法**：调既有 3 个 read service 拼装；不直查 DB
- **降级容错**：任一子源抛 RuntimeException → 字段为 null + errors 中记录原因；不阻塞另外两源（典型 BFF 聚合模式）
- **overall 判定**：rolling.latestHealthStatus != OK OR drift.status=WARN OR diff.status=WARN → overall WARN，否则 OK
- **drift WARN 条件** 与 job 端 `LeaderDriftAggregationService` 对齐：`(drifted+severe)/evaluated >= 0.15 OR severe >= 3`

### 9.3 #6 落地：AUDIT_* 告警 onboarding 文档

**新增**：`docs/polymarket/audit-observability/audit-alert-onboarding.md`（~210 行）

包含 5 节：
1. **总览**：7 个 AUDIT_* AlertType 列表（含第十八批新增 `AUDIT_LEADER_ROLLING_HEALTH`）+ 调度时间 + 主要含义
2. **应急决策树**：按 AlertType 分支，每个 alert 含三步："看哪个 trend / 决策表 / 调阈值"
3. **跨告警关联**：4 个高频告警组合（如 LiveReplay + Slippage 同时 fire）的根因分析
4. **通用回退预案**：每个 AlertType 对应的 enable-* 配置开关
5. **上报 / 协作约定**：持续时长阈值 / 紧急级别定义 / resolve 流程

### 9.4 对开单效率 / 资金使用率 / 是否会开单 / 盈亏点 / 胜率影响评估（每项独立）

| 维度 | #1 单测 | #5 统一 health 端点 | #6 onboarding 文档 |
| --- | --- | --- | --- |
| 开单效率 | ⚪ 无 — test scope only | ⚪ 无 — 纯只读 BFF 聚合, 不参与下单决策 | ⚪ 无 — 文档 |
| 资金使用率 | ⚪ 无 | ⚪ 无 — 不读 / 不调资金接口 | ⚪ 无 |
| 是否会开单 | ⚪ 无 | ⚪ 无 — 与 SignalRiskService hot path 完全解耦 | ⚪ 无 |
| 盈亏点 | ⚪ 无（间接：让 LeaderRollingMonitorService 行为变更有 baseline 测试保护，回归风险降低） | ⚪ 无（间接：让 ops 1 次 curl 看到三件套状态，加速门控调参回环） | ⚪ 无（间接：让 on-call 收到告警时决策更快 / 调阈值更准） |
| 胜率 | ⚪ 无（间接：保护 W2-T2 灰度自动化的 health 状态机正确性） | ⚪ 无 | ⚪ 无 |
| 回退路径 | 删测试类 | 删 controller 端点 + 删 view + 删 service | 删文档 |
| 默认开关 | test scope 自动加入 CI | 默认生效（只读） | 默认存在 |

**多门叠加风险评估**：本批 **0 处 hot path 改动** — 所有改动都是「新增测试 / 新增 BFF 聚合端点 / 新增文档」，跟单 SignalRiskService / FollowTaskService / Order 路径完全未触及。

### 9.5 安全性 & 资源开销

- **#1 单测**：JUnit 5 + Mockito + LENIENT，8 用例预期 < 300ms（无 Spring 上下文，无 DB）
- **#5 端点**：调用 3 个既有 read service → 各自单次 selectList（百级 - 千级 row）+ 内存聚合；典型响应 < 500ms。降级容错：任一子源异常 → swallow + log.warn + errors 字段记录 → 不阻塞响应
- **#6 文档**：纯 markdown，零代码执行

### 9.6 回归风险

- **`LeaderRollingMonitorServiceTest`**：仅 import 既有 production 类（`LeaderRollingMonitorService` / `SmartWalletProperties` / `SmartWalletOrderEntity` / `SmartWalletPositionEntity` / `LeaderRollingAggregationDailyEntity` / `SmartWalletAuditAlertSink` / `AlertType`）+ Mockito / JUnit；未修改任何 production 代码
- **`CopyTradingHealthView` / `CopyTradingHealthReadService`**：全新文件，无外部 import；`SmartWalletAuditController` 通过 `@RequiredArgsConstructor` 自动注入新依赖（Lombok 自动加构造参数 → Spring 自动装配）；测试 mock 该 controller 时需 mock 新字段（无 grep 命中 `new SmartWalletAuditController(...)` 测试调用）
- **`audit-alert-onboarding.md`**：纯文档，零代码影响

### 9.7 静态校验

| 文件 | brace 平衡 |
| --- | --- |
| `LeaderRollingMonitorServiceTest.java` | OK |
| `CopyTradingHealthView.java` | OK |
| `CopyTradingHealthReadService.java` | OK |
| `SmartWalletAuditController.java` (修改后) | OK |

沙箱无 `mvn`，等 maintainer pull 后跑：

```bash
mvn -pl coin-strategy-common -am install -DskipTests
mvn -pl coin-strategy-job,coin-strategy-web -am compile
mvn -pl coin-strategy-job test -Dtest=LeaderRollingMonitorServiceTest
```

### 9.8 改动清单

**新增 4 个文件**：
- `coin-strategy-job/src/test/.../service/LeaderRollingMonitorServiceTest.java`
- `coin-strategy-web/.../model/smartwallet/CopyTradingHealthView.java`
- `coin-strategy-web/.../service/smartwallet/CopyTradingHealthReadService.java`
- `docs/polymarket/audit-observability/audit-alert-onboarding.md`

**修改 1 个文件**：
- `coin-strategy-web/.../controller/smartwallet/SmartWalletAuditController.java` — 加 2 个 import + 注入新 service + 加端点

**修改主决议**：
- 本节追加

**生产代码 hot path**: 0 处修改

### 9.9 Web 接口最新清单（共 22 个）

| # | 路径 | 状态 |
| --- | --- | --- |
| 1-20 | 见 audit-observability/web-bff-interfaces.md | 第一波到第十六批 |
| 21 | `GET /audit/leader-rolling-aggregation/history` | 第十八批 |
| 22 | **`GET /audit/copy-trading-health`** ⭐ | 本批第十九批 |

### 9.10 阶段性总结

本批是"低风险测试覆盖补齐 + BFF 聚合端点 + 决策树文档落档"型 batch，单元改动只有 0 个生产 hot path 修改 + 4 个新文件 + 1 个 controller 加 endpoint，但完成了:

- **W2-T2 灰度自动化测试覆盖**：第十八批新增的 `LeaderRollingMonitorService` 终于有 8 用例单测保护 health 状态机正确性，防止后续 commit 引入回归
- **运维 SOP 升级**：3 个独立端点 → 1 个聚合端点，让"每日运维 curl 三次"的旧 SOP 落地为"curl 一次"。降级容错让单一子源故障不阻塞另外两源
- **告警 on-call 决策树**：7 个 `AUDIT_*` 告警的"看 trend / 决策表 / 调阈值"统一文档，让 on-call 任意接手都能快速定位根因；含跨告警关联与紧急级别约定
- **零跟单 hot path 影响**：所有改动都在 test scope / BFF / 文档，跟单 SignalRiskService / FollowTaskService / Order / Position 路径完全未触及

### 9.11 下一轮（剩余 + 新增）

按价值密度排序，承接第八节 8.7 剩余项 + 本批新发现：

1. **prod profile 灰度本批 audit Job** — `LeaderRollingMonitorJob` 默认 enabled=true 已合理；先观察 test 一周再上 prod
2. **trend dashboard 接入** — 让前端把 `copy-trading-health.overallStatus` 做成首页 banner
3. **多 lookback 档上线** — test 7d 稳定后改 `[7,14]`，对比短/长期 trend 一致性
4. **飞书 / 邮件 audit sink** — 多次跳过的项；新增 `FeishuStrategyAuditAlertSink implements StrategyAuditAlertSink` 即可，受平台凭证管理约束
5. **`CopyTradingHealthReadService` 单测** — 本批新增 service 未补 test，下批补
6. **AUDIT_* 决策树自动化校验** — 把 `audit-alert-onboarding.md` 的回退字段表与 `SmartWalletProperties.Audit` 字段做 grep 一致性校验，防止字段重命名后文档失效

> *本批最后更新*：2026-05-16 — 第十九批：测试覆盖 + 统一 health 端点 + 告警 onboarding 决策树

---

<!-- kb-backlink -->
> 本文归属：[Polymarket 知识地图](../MOC-Polymarket.md) ｜ [知识库首页](../知识库首页.md)
