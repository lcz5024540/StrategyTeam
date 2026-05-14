# CoinStrategy 架构重构进度报告

> 目标（最初设定）：
> - job 只保留"写、调度、执行、结算"
> - web 只保留"查询、报表、页面、修复入口"
> - 不能本地化的接口只保留为 job 内网能力，不再对外暴露
> - 前端只调 web，不再调 job

## Priority 进度

| Priority | 内容 | 状态 | 说明 |
|---|---|---|---|
| 1 | 删 JobApiProxyController 非 Binance 转发 + 前端切换 | ✅ 完成 | weather 转发已移除；前端无 `/api/weather/**` 调用；Binance 暂留 |
| 2 | SmartWalletReadProxyController 改本地查询 | ✅ 完成 | 用 `MIGRATED_PATH_PATTERNS` 显式登记已迁移路径，回退时立即 404 |
| 3 | job GET 端点 + export 下沉到 web | ✅ 主路径完成 / ⚠️ 部分未迁 | 详见下方"Priority 3 剩余" |
| 4 | job 残留写入触发改 MQ/定时驱动 | ✅ 部分完成 | settlement / claim / admin / profile-write / accounting 已是命令模式 |
| 5 | 网络层收敛 job 端口 + 路径白名单 | 📝 文档化（见 NETWORK_HARDENING.md） |

---

## 累计代码改动

| 改动 | 行数级别 | 位置 |
|---|---|---|
| SmartWalletProperties 下沉 | 922 行 | common-service/.../smartwallet/config |
| 5 sub-services + 8 model 下沉 | ~2800 行 | common-service/.../smartwallet/{service,model} |
| ConcentrationSnapshot record 提取 | ~70 行 | common-service/.../smartwallet/model |
| Phase 3.1 discovery read service + 2 view | ~150 行 | common-service/.../smartwallet/{service,model} |
| Phase 3.2 market-monitoring read service + 3 view + MarketStateSnapshot + OffsetPageView | ~1400 行 | common-service/.../smartwallet/{service,model} |
| web SmartWalletReadController 新增 GET 端点 | ~50 行 | coin-strategy-web |
| **总计下沉到 common-service** | **~5300 行** | |
| pom.xml + Application 配置调整 | ~30 行 | 3 个模块 |

---

## 新增的 web 端 GET（前端可直接调用）

```
/smart-wallet/leader-outcomes/summary
/smart-wallet/leader-outcomes/follow-coverage
/smart-wallet/wallet-features/daily
/smart-wallet/discovery/session-coverage
/smart-wallet/reports/market-monitoring
/smart-wallet/reports/market-monitoring/page
```

加上原本就在 web 本地实现的（overview / profiles / orders / fills / positions / pnl / reports / settlement / claim 等），SmartWalletReadController 现在共 50+ GET 端点，**全部从 DB 直读**，不再 proxy 到 job。

---

## Priority 3 剩余未迁移 GET（共 38 个）

按性质分四类：

### A. 真正可迁但工作量未投（22 个）

| 批次 | 端点 | 工作量 |
|---|---|---|
| B 批 Profiles 深度查询 | `/profiles/truth-audit`、`/profiles/{addr}/diagnosis`、`/profiles/{addr}/order-analysis`、`/profiles/{addr}/order-stats`、`/profiles/overrides/candidates` | 中（需先把 ProfileStoreService 读路径下沉到 common，参考方案 1 模式） |
| C 批 异常诊断 | `/orders/diagnostics`、`/positions/market-diagnostics`、`/polymarket/market-data/diagnostics` | 中（依赖 `abnormalMarketReviewService`，跨表诊断逻辑） |
| E 批 Watchlist | `/watchlist`、`/watchlist/candidates,executable,warm,archetypes,archetypes/export,behavior-profiles,tier-diagnostics,tier-diagnostics/summary` | 中（多个列表 + 1 个 CSV 导出，模式与已迁 reports 类似） |

每批的执行路径与方案 1 / Phase 3 完全一致：
1. 必要时把底层 service 的 read 部分下沉到 common-service
2. 在 web 加 GET 端点 + 接入
3. proxy 增补 MIGRATED_PATH_PATTERNS

### B. 不能本地化（11 个）—— 应执行 Priority 5 把它们限制到 job 内网

| 端点 | 不能本地化的原因 |
|---|---|
| `/consensus-signals` | 依赖 `SmartWalletProperties.getSignalRisk()` + 多个 intelligence service |
| `/diagnostics/signal-health` | 同上 + accountingService |
| `/live-validation/status` | 直接返回 job 进程内 `AtomicReference` 状态 |
| `/config/baseline-model` | job @ConfigurationProperties 内存绑定 |
| `/config/runtime` + `/catalog` | job `@PostConstruct` 构造的 definitions |
| `/config/signal-risk-presets` | `properties.getSignalRiskPresets()` |
| `/config/live-validation-debug` | 显式调试接口，读 `properties` + `environment` |
| `/execution-wallet/status` + `/live-state` | 读执行钱包私钥 + Python 桥 + 实时 Polymarket API |
| `/wallets` + `/markets` | in-memory `ConcurrentHashMap` + properties seed |

**建议**：这 11 个继续从 web 外网入口下线（已通过 MIGRATED_PATH_PATTERNS 控制），同时 job 端口配 IP 白名单只允许内网访问。文档化在 `NETWORK_HARDENING.md`。

### C. 第三方 API（4 个）—— 同上 B，保留 job 内网

| 端点 | 第三方依赖 |
|---|---|
| `/clob/markets/{conditionId}` | Polymarket CLOB 实时 |
| `/clob/markets/{conditionId}/price-reference` | 同上 |
| `/clob/orders/{orderId}` | 同上 |
| `/concentration/{conditionId}` | Polymarket holders API 实时 |

### D. 历史/废弃（1 个）

未确认；扫一次可清。

---

## Priority 4 剩余：MQ/定时驱动改造（评估）

### 已完成（Phase 2 期间附带验证）

web 端已采用命令模式的 controllers：
- `SmartWalletSettlementController`
- `SmartWalletClaimController`
- `SmartWalletAdminController`
- `SmartWalletProfileWriteController`
- `SmartWalletAccountingController`
- `SmartWalletFollowTasksController`
- `SmartWalletAllowlistController`、`SmartWalletBlocklistController`
- `SmartWalletTradeEventsWriteController`
- `SmartWalletWalletFeaturesWriteController`
- `SmartWalletTradeOrdersWriteController`

它们都是 `enqueue(triggerId) → GET status` 模式，写 `strategy_manual_trigger` 表，job 端 `StrategyManualTriggerConsumer` 异步消费。

### 仍是 HTTP 透传的（需要逐个评估）

`SmartWalletWriteProxyController`（兜底）和 `JobApiProxyController`（Binance 段）。
- Binance 段按用户要求"暂不动"。
- 其他写入路径如果还经过 `SmartWalletWriteProxyController` 透传到 job，需要逐个看是否能改成命令模式（参考已迁的 controllers）。

**建议下一阶段做的事**：

1. 扫描 job 端所有 `@PostMapping` 端点，列出仍依赖 HTTP 触发的（即不经 `StrategyManualTriggerConsumer` 的）。
2. 对每个找到的端点，决定：
   - **改为命令模式**：在 web 写一个 trigger controller，job 端转为 consumer 消费 trigger
   - **改为定时驱动**：把执行逻辑放到 `@Scheduled` 方法（job 进程内）
   - **保留**：如果是"运维一次性触发"且无需对外，留在 job 内网

3. 删除 `SmartWalletWriteProxyController`（如果全部命令化）。

---

## 沙箱限制残留：9 个 stub 文件待 `git rm`

```
coin-strategy-job/.../smartwallet/model/DiscoverySessionCoverageView.java
coin-strategy-job/.../smartwallet/model/DiscoverySessionCoverageBucketView.java
coin-strategy-job/.../smartwallet/model/SmartWalletMarketMonitoringSummaryView.java
coin-strategy-job/.../smartwallet/model/SmartWalletMarketMonitorItemView.java
coin-strategy-job/.../smartwallet/model/SmartWalletMarketMonitorTrendPointView.java
coin-strategy-job/.../strategy/model/OffsetPageView.java
coin-strategy-web/.../web/model/smartwallet/SmartWalletMarketMonitoringSummaryView.java
coin-strategy-web/.../web/model/smartwallet/SmartWalletMarketMonitorItemView.java
coin-strategy-web/.../web/model/smartwallet/SmartWalletMarketMonitorTrendPointView.java
```

每个文件内容只剩 package 声明 + 注释。沙箱无 `rm` 权限故只能 stub。

---

## ROI 建议（按 ROI 从高到低排序剩余事项）

1. **Priority 5 网络层硬化（高 ROI、零代码）**：架构上 final 闭环，让"不能本地化"的端点真正限制为 job 内网。详见 `NETWORK_HARDENING.md`。
2. **B + E 批端点下沉（中 ROI）**：方案 1 模式已成熟，机械工作；做完 web 就能提供另外 14 个本地端点。如果业务上前端不急用这些，可以延后。
3. **Priority 4 收尾（中 ROI）**：删除/重构 SmartWalletWriteProxyController；触发清单收紧。
4. **C 批异常诊断（低 ROI）**：逻辑复杂，业务收益有限，建议最后做。

