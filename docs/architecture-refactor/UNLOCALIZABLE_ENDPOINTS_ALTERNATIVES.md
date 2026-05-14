# 不能本地化端点的替代方案

> 对应 Priority 3 中"不能本地化"15 个端点（11 个 job 进程状态 + 4 个 CLOB 第三方实时 API）。
> 思路：**接受 N 分钟延迟换数据落地** + **job @Scheduled 定时快照写 DB** + **web 读 DB**。
> 与 Priority 4（定时驱动改造）一并设计——既解决"不能本地化"的展示问题，又把 job 写入路径完整收敛到定时驱动。

---

## 一、端点分级处置

### Tier A：保留为"内网运维端点"，前端不展示（建议直接下线）

5 个仅为运维/调试用，前端从未调用：

| 端点 | 用途 | 处置 |
|---|---|---|
| `/config/live-validation-debug` | 显式调试接口，读 properties + environment | 仅运维内网 curl，前端永久下线 |
| `/config/signal-risk-presets` | 风控 preset 列表 | 同上 |
| `/diagnostics/signal-health` | 信号健康诊断 | 同上 |
| `/wallets` | 钱包列表（in-memory seed） | 同上 |
| `/markets` | 市场列表（in-memory seed） | 同上 |

**改动**：
- 前端代码删掉对它们的引用（grep 后确认）
- web `SmartWalletReadProxyController` 的 `MIGRATED_PATH_PATTERNS` 把这 5 条**加进迁移清单并标注 INTERNAL_ONLY**，proxy 命中即 404
- 部署侧 Priority 5 网络隔离已经堵住外网访问，双保险

### Tier B：用"定时快照 → DB → web 读"替代实时调用（推荐）

8 个端点的实时性可以放宽到 1-5 分钟，由 job @Scheduled 把当前状态/查询结果写入新建的快照表，web 端只读 DB。

| 端点 | 实时来源 | 替代方案 |
|---|---|---|
| `/config/baseline-model` | `SmartWalletProperties.baselineModel` 进程绑定 | job 启动后 + 每次配置变更后写 `strategy_config_snapshot` 表（key=BASELINE_MODEL, value=json）；web 读最新 |
| `/config/runtime` + `/catalog` | `StrategyRuntimeConfigService` 进程内 definitions + DB 覆盖 | 1) 把 catalog（definitions）改为静态资源 yaml 让 web 也能读；2) 当前 effective config 写 `strategy_config_snapshot`（key=RUNTIME_EFFECTIVE） |
| `/live-validation/status` | job 进程内 AtomicReference 状态 | job @Scheduled (e.g. 30s 一次) 把状态写 `smart_wallet_live_validation_status` 单行表；web 读 |
| `/execution-wallet/status` | 读执行钱包私钥 + Python 桥 + 实时 Polymarket API | 1) **钱包私钥永不下沉到 web**——保留 INTERNAL_ONLY；2) status 字段（enabled/configured/liveReady 等）由 job 启动后写 `execution_wallet_runtime_status` 表；web 读非敏感字段 |
| `/execution-wallet/live-state` | 实时余额 + 持仓 | 同上 + balance/positions 定时刷快照（每 30s/60s） |
| `/consensus-signals` | 共识信号实时计算 | job @Scheduled 每 N 秒写 `smart_wallet_consensus_signal_snapshot` 表；web 读 |

**新建 DB 表清单**（建议加到一个 SQL 脚本）：

```sql
-- 通用配置快照
CREATE TABLE strategy_config_snapshot (
    config_key VARCHAR(64) PRIMARY KEY,
    config_value_json TEXT NOT NULL,
    snapshot_at DATETIME NOT NULL,
    updated_by VARCHAR(64)
);

-- live validation 状态
CREATE TABLE smart_wallet_live_validation_status (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    enabled BOOLEAN,
    preset VARCHAR(32),
    suspended BOOLEAN,
    suspended_until DATETIME,
    suspension_reason VARCHAR(256),
    running BOOLEAN,
    last_run_at DATETIME,
    last_run_summary_json TEXT,
    snapshot_at DATETIME NOT NULL
);

-- 执行钱包非敏感状态（不存私钥！）
CREATE TABLE execution_wallet_runtime_status (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    enabled BOOLEAN,
    configured BOOLEAN,
    live_ready BOOLEAN,
    chain VARCHAR(32),
    masked_address VARCHAR(64),
    funder_masked_address VARCHAR(64),
    min_order_usdc DECIMAL(20,4),
    max_order_usdc DECIMAL(20,4),
    slippage_rate DECIMAL(10,6),
    notes_json TEXT,
    snapshot_at DATETIME NOT NULL
);

-- 执行钱包余额/持仓（30s 刷新）
CREATE TABLE execution_wallet_live_state_snapshot (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    wallet_address VARCHAR(64),
    condition_id VARCHAR(64),         -- nullable 整体快照
    balance_usdc DECIMAL(20,4),
    positions_json TEXT,
    snapshot_at DATETIME NOT NULL,
    INDEX idx_snapshot_at (snapshot_at DESC)
);

-- 共识信号快照
CREATE TABLE smart_wallet_consensus_signal_snapshot (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    market_id VARCHAR(128) NOT NULL,
    outcome VARCHAR(16) NOT NULL,
    side VARCHAR(16),
    window_seconds INT,
    consensus_score DECIMAL(10,4),
    deduped_entity_count INT,
    last_event_at DATETIME,
    payload_json TEXT,
    snapshot_at DATETIME NOT NULL,
    INDEX idx_market_outcome (market_id, outcome),
    INDEX idx_snapshot_at (snapshot_at DESC)
);
```

**对应的 job @Scheduled 任务**：

| Job 类 | 频率 | 输出表 |
|---|---|---|
| `LiveValidationStatusSnapshotJob` | 30s | `smart_wallet_live_validation_status` |
| `ExecutionWalletStatusSnapshotJob` | 60s | `execution_wallet_runtime_status` |
| `ExecutionWalletLiveStateSnapshotJob` | 60s | `execution_wallet_live_state_snapshot` |
| `BaselineModelConfigSnapshotJob` | 60s + 配置变更后立即触发 | `strategy_config_snapshot` |
| `RuntimeConfigSnapshotJob` | 60s + 配置变更后立即触发 | `strategy_config_snapshot` |
| `ConsensusSignalSnapshotJob` | 60s | `smart_wallet_consensus_signal_snapshot` |

### Tier C：CLOB 4 个端点——保留 INTERNAL_ONLY + 业务讨论是否需要展示

| 端点 | 实时来源 | 处置 |
|---|---|---|
| `/clob/markets/{conditionId}` | Polymarket CLOB API 实时 | INTERNAL_ONLY（不必展示给前端用户） |
| `/clob/markets/{conditionId}/price-reference` | 同上 | 同上 |
| `/clob/orders/{orderId}` | 同上 | 业务方需求评估：如果"用户要看自己单子状态"，把 `smart_wallet_order` 表的 status/filled_qty 等已有字段返给前端，不必直访 CLOB |
| `/concentration/{conditionId}` | Polymarket holders 实时 | **已有 `smart_wallet_market_concentration_snapshot` 表**（被 `MarketConcentrationSnapshotService` 用），web 直接读快照，不直访 |

`/concentration/{conditionId}` 实际上已经有快照表了——web 加一个 GET 端点读 `MarketConcentrationSnapshotService.batchGetLatestSnapshots(...)` 就完事。

---

## 二、执行顺序建议

按 ROI 高 → 低：

1. **Tier A 立即下线（零代码或一行配置）**：proxy 命名禁单 + 前端 grep 清理。15 分钟搞定。
2. **`/concentration/{conditionId}`**（Tier C）：已有快照表，加 1 个 web GET 端点 + 接入 MIGRATED_PATH_PATTERNS。30 分钟。
3. **Tier B 的 6 个端点**：每个需要 a) 建表 b) 写 @Scheduled c) 写 web read service d) 加 GET 端点。每个约 1-2 小时，共约 8-12 小时工作量。可以分批做：
   - 先做最简单 1 个端点验证模板（推荐 `/live-validation/status`，只有一个状态行）
   - 模式通了再批量做
4. **CLOB orders/markets**：业务方拍板要不要展示 + 是否接受快照延迟。

---

## 三、与 Priority 4（定时驱动）的关系

本方案**直接落实 Priority 4**：
- 这 6 个 Tier B 的 @Scheduled 任务就是 job 的"定时驱动"
- 不需要 MQ
- web 端不再需要 HTTP 调 job——读 DB 即可

---

## 四、不动 job 端原 service 的承诺

所有 Tier A/B 的实现里，**job 端原 service 不动**：
- `LiveValidationService.status()` 等方法继续在 job 内部使用（job 自己 @Scheduled 读它的状态）
- 新建的 @Scheduled job 是"采集者"——读 job 内部状态、写 DB 表
- web 读 DB，行为等价于"延迟 N 分钟的快照"

这跟 Phase 3 Read/Write 拆分思路完全一致。

---

## 五、敏感数据隔离

执行钱包/Polymarket 私钥**永远只在 job**：
- `ExecutionWallet.privateKey` 字段不下沉到 DB 快照表
- `execution_wallet_runtime_status` 表只存非敏感的"是否配置完成"、masked address 等
- 前端看到的"liveReady"是布尔值，不暴露 private key 任何线索
