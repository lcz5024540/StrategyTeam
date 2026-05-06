# Smart Wallet 表结构与策略完整性审计

## 1. 审计结论

当前 Polymarket smart-wallet 服务不是简单的“表没用”，而是存在三类问题：

1. 主交易链路表、风控研究表、运维补偿表混在一起，缺少明确分层。
2. 部分表已有生产读写，但命名像实验表，容易被误判为无用表。
3. 部分表存在重复 DDL 来源，且主 schema、迁移脚本、运行时兜底建表之间有字段漂移。

最重要的结论：

- `smart_wallet_market_snapshot`：已实际使用，不能删。它支撑入场时机、滑点、交易效果标签和盈利概率估计。
- `smart_wallet_trade_effect_label`：已实际使用，不能删。它是交易级 EV、回放回测、地址行为画像的核心标签表。
- `smart_wallet_wallet_feature_daily`：已实际使用，不能删。它是地址行为画像、archetype 分类和 profile analysis 的特征快照。
- `smart_wallet_treatment_control_sample`：目前未进入生产链路。只有 Entity/Mapper/DDL/reset，没有生成、查询、训练或 API 消费，应标记为 `RESEARCH_RESERVED`，不要放在核心链路里。

本轮已做的优化：

- 修正 `sql/polymarket/arbitrage_schema.sql`，把代码实际依赖但只在后续迁移或运行时 DDL 中出现的字段收敛回主 schema。
- 新增 `sql/polymarket/smart_wallet_schema_consolidation_migration.sql`，用于已有数据库幂等补齐字段。
- 对齐 settlement/claim 运行时兜底建表中的错误信息字段长度，降低主 schema 与运行时 DDL 漂移。

## 2. 推荐架构分层

```text
Leader Discovery
  smart_wallet_profile
  smart_wallet_behavior_profile
  smart_wallet_order_analysis
  smart_wallet_wallet_feature_daily

Signal Intake
  smart_wallet_trade_event
  wallet_entity_map
  wallet_entity_feature_snapshot
  wallet_follow_candidate

Market Context / Risk Features
  smart_wallet_market_info
  smart_wallet_market_snapshot
  smart_wallet_market_concentration_snapshot
  smart_wallet_trade_effect_label

Execution / Position Lifecycle
  smart_wallet_follow_task
  smart_wallet_order
  smart_wallet_fill
  smart_wallet_position
  smart_wallet_trade_order
  smart_wallet_cash_ledger

Exit / Settlement / Claim
  smart_wallet_settlement_run
  smart_wallet_settlement_retry
  smart_wallet_claim_run
  smart_wallet_claim_retry

Reporting / Ops
  smart_wallet_daily_report
  smart_wallet_manual_allowlist

Research Reserved
  smart_wallet_treatment_control_sample
```

## 3. 表级使用状态

| 表 | 状态 | 实际用途 | 处理建议 |
|---|---|---|---|
| `smart_wallet_profile` | 核心生产 | leader 准入、watchlist、wallet_score、近期退化冷却 | 保留 |
| `smart_wallet_behavior_profile` | 核心生产 | 地址行为画像、exit lane、replicability | 保留 |
| `smart_wallet_order_analysis` | 分析生产 | 地址历史闭环分析、exit style | 保留 |
| `smart_wallet_trade_event` | 核心生产 | leader 成交事件入口，生成 follow task | 保留 |
| `wallet_entity_map` | 核心风控 | 多钱包实体去重、防伪共识 | 保留 |
| `wallet_entity_feature_snapshot` | 风控画像 | 实体级聚合特征 | 保留，但需要归档策略 |
| `wallet_follow_candidate` | 风控候选 | intelligence enrich 输出，follow_task 前置关联 | 保留 |
| `smart_wallet_market_info` | 核心生产 | 收敛 marketId/question/outcome/tokenId/endAt 等市场维度 | 保留 |
| `smart_wallet_market_snapshot` | 核心风控 | 盘口/价格微观结构，给入场时机、滑点、EV 估计使用 | 保留，增加 TTL |
| `smart_wallet_trade_effect_label` | 核心风控 | leader trade 后价格路径和 paper EV 标签 | 保留，提升质量 |
| `smart_wallet_wallet_feature_daily` | 核心画像 | 7d/30d 画像特征，archetype/behavior/profile 使用 | 保留 |
| `smart_wallet_market_concentration_snapshot` | 风控生产 | 市场集中度监控、防控盘 | 保留 |
| `smart_wallet_follow_task` | 核心生产 | 跟单任务与风控决策结果 | 保留 |
| `smart_wallet_order` | 核心生产 | 订单事实表 | 保留 |
| `smart_wallet_fill` | 核心生产 | 成交事实表 | 保留 |
| `smart_wallet_position` | 核心生产 | 当前/历史持仓事实 | 保留 |
| `smart_wallet_trade_order` | 生命周期视图 | 从 position/order/fill/ledger 聚合的生命周期主单 | 保留，但定位为派生表 |
| `smart_wallet_cash_ledger` | 核心会计 | 资金流水与 PnL 归因 | 保留 |
| `smart_wallet_daily_report` | 报表 | 每日汇总 | 保留 |
| `smart_wallet_settlement_run` | 运维生产 | 结算扫描运行记录 | 保留 |
| `smart_wallet_settlement_retry` | 运维生产 | 结算失败/未决重试队列 | 保留 |
| `smart_wallet_claim_run` | 运维生产 | claim 运行记录 | 保留 |
| `smart_wallet_claim_retry` | 运维生产 | claim 失败重试队列 | 保留 |
| `smart_wallet_manual_allowlist` | 运维风控 | 人工白名单和观察/实盘覆盖 | 保留 |
| `smart_wallet_treatment_control_sample` | 未接入生产 | 原计划做因果验证 treatment/control 样本，但无生成/消费服务 | 标记 research reserved，暂不进入核心链路 |

## 4. 重复定义与漂移点

### 4.1 主 schema 与迁移脚本重复

`arbitrage_schema.sql` 是主 schema，但很多字段只存在于后续 migration：

- `smart_wallet_trade_event.size_shares`
- `smart_wallet_profile.manual_*`
- `smart_wallet_market_info.market_slug`
- `smart_wallet_order.version`
- `smart_wallet_position.source`
- `smart_wallet_position.version`

问题：

- 新库如果只执行 `arbitrage_schema.sql`，Entity 会映射到不存在的列。
- 生产靠服务启动时 `ALTER TABLE` 或历史迁移补救，架构边界不清晰。

本轮处理：

- 已把上述字段补进 `arbitrage_schema.sql`。
- 已新增 `smart_wallet_schema_consolidation_migration.sql`，给已有库幂等补齐。

### 4.2 服务内运行时 DDL 与主 schema 重复

当前仍有服务启动时兜底建表：

- `SmartWalletSettlementSyncService.ensureSettlementTables()`
- `SmartWalletClaimService.ensureClaimTables()`
- `SmartWalletTradeOrderService.ensureTradeOrderTable()`
- `PolymarketSmartWalletMonitorService.ensureTradeEventSchema()`

这类代码的安全价值是“老库自愈”，但架构风险是“DDL 多源定义”。本轮先做低风险优化：

- settlement/claim 的错误字段长度已与主 schema 对齐。
- 不删除运行时 DDL，避免老环境启动失败。

后续建议：

1. 保留一个版本周期的运行时 DDL 作为兼容层。
2. 在所有环境确认执行 `smart_wallet_schema_consolidation_migration.sql` 后，把运行时 DDL 改成只读 schema health check。
3. 最终由 SQL migration 作为唯一 DDL 来源。

### 4.3 `smart_wallet_trade_order` 与 `smart_wallet_position` 的边界

两张表看起来重复，但语义不同：

- `smart_wallet_position` 是实时持仓事实表，用于同步、结算、claim、风控。
- `smart_wallet_trade_order` 是生命周期派生表，把 entry/exit/settlement 合并成一条交易记录，适合前端列表和复盘。

处理建议：

- 不删除 `smart_wallet_trade_order`。
- 明确它是 derived projection，不作为下单、结算、claim 的事实源。
- 任何不一致时，以 `position + order + fill + cash_ledger` 为准，并重建 trade_order。

## 5. 当前策略完整性风险

### 5.1 `treatment_control_sample` 没有闭环

风险：

- 表存在会让人误以为系统已经有因果验证能力。
- 实际没有样本生成、匹配距离计算、训练消费或报表。

建议：

- 短期：在文档和接口层标记为 `RESEARCH_RESERVED`。
- 中期：如果要做因果验证，补 `TreatmentControlSampleService`，从 `smart_wallet_market_snapshot` 中按市场类型、价格、波动率、流动性、time_to_end 匹配 control。
- 如果 30 天内不做因果验证，建议从重置流程和主文档中移到 research 区域，避免误导。

### 5.2 market snapshot 有数据膨胀风险

当前每 45s 对近期活跃市场采集快照，若 watchlist 扩大，会快速增长。

建议：

- 保留 7-14 天热数据。
- 已生成 `trade_effect_label` 的旧 snapshot 可按 market/outcome/time 压缩。
- 增加归档任务或 SQL：

```sql
DELETE FROM smart_wallet_market_snapshot
WHERE snapshot_at < NOW() - INTERVAL 14 DAY;
```

但如果需要回测更长窗口，应先导出到冷存储。

### 5.3 `trade_effect_label` 质量依赖 snapshot 完整度

如果 T+10s/T+30s/T+5m 快照缺失，标签会空值或退化。

建议：

- 对每条 label 增加质量分：`label_quality_score`、`missing_horizon_count`。
- 交易级 EV 模型只使用质量达标标签。
- 对 snapshot 缺失率做监控。

### 5.4 `wallet_feature_daily` 当前部分字段是占位

`low_liquidity_ratio_30d`、`late_market_ratio_30d` 当前实现里填 0，说明特征定义已在表中，但计算未补齐。

建议：

- 接入 `smart_wallet_market_snapshot` 和 `smart_wallet_market_info` 后再真实计算。
- 在未补齐前，不要把这两个字段作为 leader admission 的强门槛。

## 6. 安全性优化建议

### 6.1 DDL 单源化

目标：

```text
sql/polymarket/*.sql migration = 唯一 schema 来源
service @PostConstruct DDL = 临时兼容层，后续降级为 health check
```

落地：

1. 先执行 `smart_wallet_schema_consolidation_migration.sql`。
2. 线上观察一个版本。
3. 将服务内 `CREATE/ALTER TABLE` 改成检查缺列并打印明确告警。

### 6.2 表生命周期标记

建议把 smart-wallet 表分为：

- `CORE_FACT`：订单、成交、持仓、资金、leader 事件。
- `CORE_DIM`：profile、market_info、entity_map。
- `RISK_FEATURE`：snapshot、effect_label、feature_daily、concentration。
- `DERIVED_VIEW`：trade_order、daily_report。
- `OPS_QUEUE`：settlement/claim run/retry。
- `RESEARCH_RESERVED`：treatment_control_sample。

### 6.3 禁止把派生表作为事实源

安全原则：

- 交易事实以 `smart_wallet_order` / `smart_wallet_fill` 为准。
- 持仓事实以 `smart_wallet_position` 为准。
- 资金事实以 `smart_wallet_cash_ledger` 为准。
- `smart_wallet_trade_order` 只用于展示/查询，不用于执行和结算。

### 6.4 删除表前必须满足的条件

任何表删除前必须满足：

- `rg` 无生产服务读写。
- Entity/Mapper/API 已移除或迁移。
- 数据已归档。
- 至少一个版本周期内无 row 增长和无查询。
- 有 rollback 脚本。

当前不建议直接 drop 任何 `smart_wallet_*` 表；`treatment_control_sample` 也先保留但降级。

## 7. 后续优化清单

优先级 P0：

- 执行 `smart_wallet_schema_consolidation_migration.sql` 补齐已有库。
- 确认所有环境 `arbitrage_schema.sql` 新建库可直接启动。
- 给 `smart_wallet_treatment_control_sample` 标记 research reserved。

优先级 P1：

- 为 `smart_wallet_market_snapshot` 增加 TTL/归档策略。
- 为 `smart_wallet_trade_effect_label` 增加标签质量字段。
- 补齐 `wallet_feature_daily.low_liquidity_ratio_30d` 和 `late_market_ratio_30d` 的真实计算。

优先级 P2：

- 把服务内运行时 DDL 收敛为 schema health check。
- 建 `table_lifecycle` 文档或元数据表，明确 owner、事实源、保留期、是否派生。
- 如果要做因果验证，再实现 treatment/control 样本生成；否则 30 天后删除 Entity/Mapper/DDL。
