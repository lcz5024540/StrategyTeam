# Priority 4：用 job @Scheduled 替代 MQ 的定时驱动改造方案

> 用户要求：暂不引入 MQ，用 job 服务自己的 @Scheduled 完成写入触发改造。
> 现状：web 已有大量"命令模式" controllers（`strategy_manual_trigger` 表 + `StrategyManualTriggerConsumer`），这部分**保留不动**，因为 Consumer 本质上就是 job 内的 @Scheduled 任务在轮询表+消费——已经满足"job 自己处理"的要求。
> 本文档只关注**剩余仍是 HTTP 透传的写入路径**该怎么改成纯 @Scheduled 或纳入现有 trigger 表机制。

---

## 一、Priority 4 当前现状（再确认）

### 已是"job 自己定时处理"模式的写入路径

#### A. 命令模式（写表 → Consumer 轮询消费）

| Controller | 表 → 触发 action | Consumer 处理 |
|---|---|---|
| `SmartWalletSettlementController` | strategy_manual_trigger / SETTLEMENT_* | `StrategyManualTriggerConsumer` |
| `SmartWalletClaimController` | / CLAIM_* | 同上 |
| `SmartWalletAdminController` | / ADMIN_* | 同上 |
| `SmartWalletProfileWriteController` | / PROFILE_* | 同上 |
| `SmartWalletAccountingController` | / ACCOUNTING_* | 同上 |
| `SmartWalletFollowTasksController` | / FOLLOW_TASKS_* | 同上 |
| `SmartWalletAllowlistController` | / ALLOWLIST_* | 同上 |
| `SmartWalletBlocklistController` | / BLOCKLIST_* | 同上 |
| `SmartWalletTradeEventsWriteController` | / TRADE_EVENTS_* | 同上 |
| `SmartWalletTradeOrdersWriteController` | / TRADE_ORDERS_* | 同上 |
| `SmartWalletWalletFeaturesWriteController` | / WALLET_FEATURES_* | 同上 |
| `SmartWalletClobWriteController` | / CLOB_* | 同上 |
| `SmartWalletPolymarketWriteController` | / POLYMARKET_* | 同上 |

> Consumer 本质：job 内 `@Scheduled(fixedDelayString = "5s")` 轮询 `strategy_manual_trigger` 表 status=PENDING 的行，处理后更新 status=DONE/FAILED。
> **这就是"job @Scheduled 自己处理"**。命令模式 = web 写表 + job 轮询。

#### B. 纯 @Scheduled（不需要前端触发）

| Job 类 | 频率 | 用途 |
|---|---|---|
| `WeatherMarketDataCollectorJob` | 1h | weather 数据采集 |
| `SmartWalletPositionSyncService` | 15s | 持仓同步 |
| `SmartWalletSettlementSyncService` | 5min | 结算同步 |
| `SmartWalletClaimService` | 1min | claim 处理 |
| `WalletFeatureDailyService` | 1d | 钱包特征日表 |
| `PolymarketWalletDiscoveryService.refreshLeaderboardWatchlist()` | 5min | 排行榜刷新 |
| `LeaderTradeOutcomeRebuildService` | 长周期 | leader 真实闭环数据重建 |
| `MarketMetadataRefreshJob` | 5min | market metadata 刷新 |
| `MarketConcentrationSnapshotRefreshJob` | 3min | 集中度快照刷新 |
| `ClosedPositionFullResyncJob` | 1d | 已平仓头寸全量重新同步 |
| `ClosedPositionIncrementalSyncJob` | 5min | 增量同步 |
| `SmartWalletLeaderTruthMaintenanceJob` | 长周期 | leader 真实值维护 |
| `CapitalRecycleExitService` | 周期性 | 资金回收退出 |
| `StrategyHealthMonitorJob` | 周期性 | 策略健康监控 |
| `HealthSnapshotScheduler` | 周期性 | 健康快照 |

---

## 二、剩余需要改的写入路径

### 2.1 兜底 proxy 仍可能透传的写入

- `SmartWalletWriteProxyController`（catch-all `POST /smart-wallet/**`）
- `JobApiProxyController`（catch-all `/binance/**`，Binance 段按你要求不动）

#### `SmartWalletWriteProxyController` 评估

它的兜底逻辑是：所有未被前面具名 controllers（settlement、claim、admin 等）拦截的 `POST /smart-wallet/**` 都转发到 job。

**实际兜底路径有哪些？**

需要扫一次 job 端 `SmartWalletController.java` 里所有 `@PostMapping`，看哪些没被 web 端命令模式覆盖。预计有以下几类：

| job 端 POST 端点 | 处理建议 |
|---|---|
| `/scan` | 看场景：如果是用户主动扫描，纳入命令模式；如果是 cron 调用，改 @Scheduled |
| `/execute/{signalId}` | 已有 settlement 命令模式可参考，纳入即可 |
| `/wallets` (upsert) | 写 in-memory map，运维操作 → 改为运维内网 curl，前端不调用 |
| `/markets` (upsert) | 同上 |
| `/polymarket/market-data/mock-event` | 测试用，标 INTERNAL_ONLY |
| `/polymarket/events` 等其它 polymarket 写入 | 已有 SmartWalletPolymarketWriteController 命令模式覆盖 |
| `/config/baseline-model` (POST) | 改命令模式（CONFIG_BASELINE_UPDATE action）+ 同步触发 BaselineModelConfigSnapshotJob 立即刷快照 |
| `/config/runtime` (POST) | 同上（CONFIG_RUNTIME_UPDATE action） |
| `/config/runtime/reset` (POST) | 同上 |
| `/live-validation/process` | 改 @Scheduled（已经在做定时验证，无需前端触发） |

---

## 三、改造步骤

### 步骤 1：扫描清单

```bash
cd coin-strategy-job
grep -nE "@PostMapping|@PutMapping|@PatchMapping|@DeleteMapping" \
  src/main/java/com/metaverse/coin/strategy/job/smartwallet/controller/SmartWalletController.java
```

把所有 POST 端点列出，逐个标注：
- **A. 已有命令模式覆盖** → 验证 web proxy 不再走它
- **B. 纯运维/测试用** → 标 INTERNAL_ONLY，前端永久下线，proxy MIGRATED_PATH_PATTERNS 加禁单
- **C. 应该纳入命令模式** → web 新建一个 `*WriteController`（写 trigger 表），job consumer 加 action 处理
- **D. 应该改 @Scheduled** → 删除 HTTP 端点，加 `@Scheduled` 方法到对应 service

### 步骤 2：逐个改造

每个端点工作量约 30 分钟 - 2 小时。建议批次推进：

| 批次 | 端点 | 估算工时 |
|---|---|---|
| 1 | `/config/baseline-model` POST → 命令模式 | 1h |
| 2 | `/config/runtime` + `/reset` POST → 命令模式 | 1h |
| 3 | `/live-validation/process` POST → 删除 HTTP，纯 @Scheduled | 30min |
| 4 | `/execute/{signalId}` → 命令模式 | 1h |
| 5 | `/scan` → 改命令模式或 @Scheduled（看业务定位） | 30min-1h |
| 6 | `/wallets`、`/markets` POST → INTERNAL_ONLY | 15min |
| 7 | `/polymarket/market-data/mock-event` → INTERNAL_ONLY | 15min |

### 步骤 3：删除 `SmartWalletWriteProxyController`

所有 POST 都有具名 controller 处理后，写代理就可以删了。

---

## 四、与 Priority 5 的衔接

- Tier A 的 INTERNAL_ONLY 路径（`/wallets`、`/markets`、`mock-event` 等）依然需要 Priority 5 的网络隔离保证它们外网不可达。
- 删除 `SmartWalletWriteProxyController` 后，web 端就完全没有"对外暴露的写入透传"，符合"web 只查询/报表/页面/修复入口"的目标。

---

## 五、风险与权衡

1. **命令模式 vs 同步响应**：原来用户调 POST 立即拿到结果，改命令模式后变成 "ACCEPTED + triggerId"，前端需要轮询 `GET status/{triggerId}`。这是 settlement/claim 已有的 UX，沿用即可。
2. **配置变更延迟**：`/config/baseline-model` POST 改命令模式后，"修改 → 生效"会有几秒延迟（Consumer 轮询间隔）。前端可在 ACCEPTED 后立即跳 status 接口轮询，UX 影响可忽略。
3. **`/live-validation/process` 删除 HTTP 后**：如果前端有"立即触发一次验证"按钮，按钮可改为"加快下次定时"（写 trigger 表，Consumer 处理后调 service.processNow），或者干脆隐藏按钮，让定时任务自己跑。
