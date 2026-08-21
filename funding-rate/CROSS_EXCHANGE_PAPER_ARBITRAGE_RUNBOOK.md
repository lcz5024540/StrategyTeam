# 跨交易所资金费率套利 V1（Paper）验收手册

## 1. 安全边界

- 仅支持 `DRY_RUN`，代码中不存在 `LIVE` 模式切换。
- 四个交易所 Connector 只调用公共行情 API，私有下单方法固定抛出异常。
- 自动扫描、合约同步、健康检查、行情采集、持仓恢复、持仓监控和模拟结算默认全部关闭。

## 2. 初始化

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 17)
export PATH="$JAVA_HOME/bin:$PATH"
mvn -pl coin-strategy-job -am package -DskipTests
```

数据库迁移必须先确认目标验收库，再由环境负责人执行：

```bash
mysql --host=<验收库地址> --user=<账号> --password < sql/2026-08-20__cross_exchange_funding_arbitrage_v1.sql
```

在本地配置中开启：

```yaml
arbitrage:
  multi-exchange:
    scan-enabled: true
    contract-sync-enabled: true
    health-check-enabled: true
    recovery-enabled: true
    monitor-enabled: true
    settlement-enabled: true
```

如需积累真实公共行情回放数据，在隔离运行目录额外配置：

```yaml
arbitrage:
  multi-exchange:
    capture-enabled: true
    capture-output-directory: "./data/funding-replay"
    capture-symbols: "BTCUSDT,ETHUSDT"
    capture-order-book-depth: 20
    capture-delay-ms: 60000
    # 可选：部署网络需经代理或区域域名访问时覆盖；留空使用官方默认域名。
    base-urls:
      binance: ""
      okx: ""
      htx: ""
      bitget: ""
```

采集文件按 UTC 日期追加，包含 `schemaVersion`、`capturedAt`、`source=PUBLIC_REST`、交易所、资金费率、结算周期、下一结算时间、mark/index price、盘口价格及名义深度。单一交易所失败会写 `ERROR` 行而不会中断其他交易所；`CrossExchangeMarketSnapshotReplayReader` 可将成功行按 `capturedAt + symbol` 还原为统一资金费率和盘口快照，并单独保留失败记录。`CrossExchangeFundingReplayService` 会按时间排序后复用生产机会选择器、成本模型和稳定窗口输出评估统计。生成目录已加入 `.gitignore`，原始采集数据须经脱敏和来源核验后才能选择性入库。

启动 `coin-strategy-job` 后，接口前缀为 `/strategy/api`。

## 3. 验收步骤

1. `POST /strategy/api/arbitrage/funding/scan?symbol=BTCUSDT`，首次可能为空；同方向机会稳定 3 分钟后返回 `APPROVED`。
2. `GET /strategy/api/arbitrage/funding/exchanges/health`，确认每家交易所的成功/失败次数与最近错误可见。
3. `GET /strategy/api/arbitrage/funding/opportunities`，确认机会已落库且包含多空交易所、费率差、净收益和名义容量。
4. 从扫描结果取得服务端生成的 `opportunityId`，调用 `POST /strategy/api/arbitrage/funding/paper/open`，请求体为 `{ "opportunityId": "<id>", "notional": 100 }`。接口不接受客户端拼装机会，过期、未批准的 ID 或非正数 `notional` 会被拒绝。
5. `GET /strategy/api/arbitrage/funding/positions`，确认状态为 `OPENED`，双腿数量相同。
6. `POST /strategy/api/arbitrage/funding/paper/close?positionId=<id>`，确认接口先读取两侧最新公共 mark price，再以带 attempt 标识的幂等 client order ID 模拟平仓并转为 `CLOSED`；行情不可用时不得按开仓价伪成交。
7. `POST /strategy/api/arbitrage/funding/pause?paused=true`，确认新 Paper 开仓被拒绝；传 `false` 恢复。
8. 对采集目录中的 CSV 调用 `POST /strategy/api/arbitrage/funding/replay?fileName=<文件名.csv>`，确认返回帧数、采集失败数、候选/稳定批准数量及按原因聚合的拒绝计数。接口只接受采集目录下的单个 `.csv` 文件名，拒绝绝对路径与目录穿越。
9. 访问 `/strategy/api/actuator/prometheus`，确认以下指标可见：
   - `funding_arb_scans_total`、`funding_arb_opportunities_total`
   - `funding_arb_active_positions`、`funding_arb_unhedged_notional`
   - `funding_arb_rollbacks_total`、`funding_arb_manual_interventions_total`
   - `funding_arb_exchange_healthy{exchange="..."}`
   - `funding_arb_exchange_request_success_ratio{exchange="..."}`
   - `funding_arb_exchange_last_latency_ms{exchange="..."}`
   - `funding_arb_exchange_rate_limits_total{exchange="..."}`

## 4. 数据核验

```sql
SELECT * FROM cross_exchange_arb_opportunity ORDER BY id DESC LIMIT 20;
SELECT * FROM cross_exchange_arb_position ORDER BY id DESC LIMIT 20;
SELECT * FROM cross_exchange_arb_order ORDER BY id DESC LIMIT 40;
SELECT * FROM cross_exchange_funding_settlement ORDER BY id DESC LIMIT 20;
SELECT * FROM cross_exchange_risk_snapshot ORDER BY id DESC LIMIT 20;
```

`cross_exchange_risk_snapshot.check_stage = 'STATE_TRANSITION'` 的记录是 Paper 双腿状态迁移审计，
`reason` 依次记录 `CREATED`、`OPENING_LONG/OPENING_SHORT`、补偿状态以及最终状态。

验收不通过条件：出现真实交易所订单、关键行情字段用常量代替、单腿成交未补偿、重复 client order ID、未记录的活动裸仓，或 Java/Maven 未使用 JDK 17。

## 5. 已知环境差异

2026-08-21 本机只读实测：Binance、Bitget 返回 HTTP 200；OKX、HTX 在 12 秒内连接超时。扫描器按交易所隔离失败并记录健康状态；四所公共 base URL 已支持按部署环境覆盖，默认仍使用官方域名。部署环境需确认四所域名或代理均可访问，才能验证四所共同币种集合。

数据库迁移必须由验收环境负责人执行。本地未配置 `DB_PASSWORD`，且默认地址可能指向共享环境，开发验证不会自动执行 SQL。

## 6. 交付状态总览

状态口径：`✅ 已完成` 表示代码、配置或测试已在当前分支交付；`⏳ 未完成` 表示仍需编码或环境验收；`🚫 非本期` 表示方案明确排除，不属于 V1 缺陷。

| 阶段 | 交付项 | 状态 | 证据或边界 |
|---|---|---|---|
| Phase 0 | JDK 17 构建约束及四所公共 HTTP Connector | ✅ 已完成 | URL、响应解析和统一公共行情 DTO 已下沉 `common-client`；默认官方域名及按环境覆盖的公共 base URL 均已支持，HTTP 200/429 及四所解析有测试 |
| Phase 0 | 删除伪造 mark/last price 与缺失结算时间兜底 | ✅ 已完成 | 关键行情字段缺失时 fail-closed，不形成可交易报价 |
| Phase 0 | 每交易所手续费、滑点和账户成本配置 | ✅ 已完成 | 四所独立 maker/taker/slippage 配置；V1 默认按 taker 保守估算 |
| Phase 1 | Binance、OKX、HTX、Bitget 公共行情 Gateway | ✅ 已完成 | 已实现合约目录、资金费率、mark/index price 和盘口；不含部署环境连通性验收 |
| Phase 1 | symbol、结算周期、精度、最小名义价值归一化 | ✅ 已完成 | 合约目录同步及缺失字段 fail-closed 已实现 |
| Phase 1 | 网关健康、延迟、成功率和限频监控 | ✅ 已完成 | 健康接口及 4 组带 `exchange` 标签的 Prometheus 指标已实现 |
| Phase 2 | 全交易所组合枚举与多空方向选择 | ✅ 已完成 | 同号正/负费率方向、不同周期归一化和第一腿选择已有单元测试 |
| Phase 2 | 手续费、滑点、风险缓冲和净收益排序 | ✅ 已完成 | 被拒候选及完整拒绝原因均落库 |
| Phase 2 | 异号、结算错位、陈旧行情、容量和价格偏离过滤 | ✅ 已完成 | 异号默认观察并拒绝自动开仓；3 分钟稳定窗口已实现 |
| Phase 3 | Paper 双腿执行与幂等 client order ID | ✅ 已完成 | 第二腿使用第一腿实际成交量；每次平仓重试生成新 attempt ID，单次尝试内保持幂等；LIVE 与私有下单硬关闭 |
| Phase 3 | 部分成交、拒单、超时、撤单及补偿失败 | ✅ 已完成 | 可进入 `ROLLED_BACK` 或 `MANUAL_INTERVENTION`，完整状态迁移写风险审计表 |
| Phase 3 | 五张套利表、实体和 Mapper | ✅ 已完成 | SQL、实体、Mapper 及机会/仓位/订单/结算/风险持久化已提交 |
| Phase 4 | 持仓监控、退出、模拟平仓和失败恢复 | ✅ 已完成 | 退出净收益按最新费率差扣除两侧剩余平仓 taker/滑点成本及基差缓冲；自动/手工平仓均使用最新公共 mark price；行情缺失时熔断且不伪成交 |
| Phase 4 | 裸仓超时熔断、重启恢复和结算幂等 | ✅ 已完成 | 结算按两侧最新公共快照独立估值，单所失败隔离；停机跨多周期不拿单个当前报价伪回填历史；隔离数据库运行验收仍为未完成项 |
| Phase 5 | 管理 API、指标、告警规则和运行手册 | ✅ 已完成 | 机会、仓位、健康、扫描、Paper 开平仓、暂停接口已实现；仓库内已交付 5 条 Prometheus 告警规则 |
| Phase 5 | 确定性场景与采集文件回放 | ✅ 已完成 | 四所参与的 8 类固定快照场景可重复运行；CSV 可经管理接口复用生产选择器、成本模型与稳定窗口并汇总拒绝原因；真实交易所历史数据集仍见第 7 节 |
| Phase 5 | 真实历史数据、告警环境演练和 7 天长稳 | ⏳ 未完成 | 见第 7 节 |
| V1 边界 | 真实交易、自动划转、异号自动开仓 | 🚫 非本期 | 代码不得通过配置开启 LIVE |

当前代码验证：显式 JDK 17 跨所核心专项 `33 tests / 0 failures / 0 errors`，Maven package 成功。项目全量 Job 测试为 `575 tests / 22 failures / 0 errors / 11 skipped`，失败仍属于非跨所模块，详见第 7.2 节。

Paper 结算的 `actualAmount` 是“结算任务运行时最新可得公共资金费率 × 最新 mark price”的模拟估值，不是交易所私有账户资金流水。真实实盘收益核对仍需未来开放私有账户能力后，以交易所 funding ledger 为权威来源。

## 7. 未完成任务

### 7.1 V1 验收前必须完成

| 优先级 | 任务 | 当前状态 | 完成标准 |
|---|---|---|---|
| P0 | 在隔离验收库执行五表迁移 | 待环境负责人执行 | 五张表及索引创建成功，应用账号可读写；禁止直接操作未知默认库或共享生产库 |
| P0 | 验证部署环境四所公共 API 连通性 | 本机仅确认 Binance、Bitget 可访问 | Binance、OKX、HTX、Bitget 的合约目录、资金费率、mark/index price、盘口均能持续返回，健康指标无持续失败 |
| P0 | 完成 Paper 连续运行 7 天 | 尚未开始 | 无未记录裸仓、重复订单或无法解释的状态；统计机会数、回滚率、人工干预次数及预期/模拟收益偏差 |
| P0 | 重启恢复与结算幂等实库验收 | 单元测试已覆盖，尚未做隔离库运行验证 | 持有 Paper 仓位时重启应用，仓位只恢复一次；相同交易所/仓位/结算时间不产生重复结算记录 |
| P1 | 在验收环境加载并演练 Prometheus 告警规则 | 代码已完成，环境演练未完成 | 加载 `coin-strategy-job/src/main/resources/prometheus/cross-exchange-funding-alerts.yml`，逐项触发并确认路由、恢复通知和标签正确 |

仓库内告警规则已覆盖 `FundingArbExchangeUnhealthy`、`FundingArbRateLimitSpike`、`FundingArbUnhedgedExposure`、`FundingArbManualIntervention` 和 `FundingArbCircuitOpen`。暂停及熔断状态由持仓监控任务持续刷新 Gauge；规则文件 YAML 解析和关键指标引用均有自动化测试。生产或共享 Prometheus 配置未在开发阶段直接修改。

### 7.2 工程收尾

| 优先级 | 任务 | 当前状态 | 完成标准 |
|---|---|---|---|
| P1 | 建立真实交易所历史资金费率与盘口数据集 | 确定性合成回放、真实公共行情采集器、CSV 读取器及生产选择规则回放接口已完成，四所长期数据尚未采集 | `capture-enabled` 默认关闭；启用后按 UTC 日生成带 `PUBLIC_REST` 来源、采集时间和成功/失败状态的 CSV，回放接口按时间和币种还原快照并统计候选、稳定批准及拒绝原因。仍需在四所均可访问的隔离环境持续采集并完成来源核验 |
| P1 | 将四所 HTTP Connector 实现完整下沉至 `common-client` | ✅ 已完成 | `PerpetualMarketDataClient` 统一合约、资金费率和盘口 DTO；四所 URL 与 JSON 解析均位于 `common-client`，`coin-strategy-core` 不再承载相关 URL 或响应解析 |
| P1 | 清理项目全量测试既有失败 | 已从 29 failures / 4 errors 收敛至 22 failures / 0 errors；剩余集中于 SmartWallet 的 Controller 契约及交易/退出策略语义差异 | `mvn -pl coin-strategy-job -am test` 全绿；不得用忽略或跳过方式掩盖失败 |
| P2 | 动态同步账户手续费等级 | V1 使用每交易所配置的保守 taker 成本 | 私有账户能力开放后，从账户等级接口刷新实际 maker/taker 成本，并保留配置兜底与变更审计 |
| P2 | 提供独立的仓位收益归因查询 | 订单与结算明细已可审计，尚未提供聚合 DTO/API | 按仓位输出多腿价差 PnL、手续费、滑点、资金费及总收益；Paper 与未来 LIVE 数据来源必须明确区分 |

### 7.3 明确不在本期

- 真实资金下单和 `LIVE` 执行模式。
- 跨交易所自动划转、资金调拨和自动归集。
- 正负混合资金费率的自动开仓；当前只观察并以 `MIXED_FUNDING_SIGNS` 拒绝。
- Hummingbot、CCXT 或 NautilusTrader 运行时依赖；本项目只复用其架构思想。
