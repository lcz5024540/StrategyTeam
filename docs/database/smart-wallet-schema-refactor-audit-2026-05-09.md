# Smart Wallet 表结构重构审计

审计时间：2026-05-09  
数据库：`coin_strategy`  
范围：只处理 `smart_wallet_*`、`wallet_*`。本轮排除 `single_exchange_*` 和 `weather_*`。

## 1. 总结

当前主要问题不是“表太多”本身，而是三类数据混在一起：

1. 事实表、派生表、研究表、运维表没有边界。
2. 部分字段是未来能力预留，线上长期为空或全 0。
3. 市场维度已经抽到 `smart_wallet_market_info`，但历史分析/事件表仍保留大量市场快照字段，造成重复。

最建议的调整方向：

- 保留核心事实表：`trade_event`、`follow_task`、`order`、`fill`、`position`、`cash_ledger`。
- 明确 `trade_order`、`daily_report`、`order_analysis`、`leader_trade_outcome` 是派生/分析表，不能作为交易事实源。
- `smart_wallet_market_info` 作为市场维度主表，其他表优先只存 `market_info_id` 或必要历史快照。
- 把 `smart_wallet_treatment_control_sample` 标记为 `RESEARCH_RESERVED`，当前不进入核心链路。
- `smart_wallet_market_snapshot` 线上为空，但代码有强依赖，应先修采集链路，不建议直接删除。

## 2. 真实数据规模

核心大表：

| 表 | 行数 | 大小 | 定位 |
|---|---:|---:|---|
| `smart_wallet_order_analysis` | 637k+ | 450 MB | 历史闭环分析派生表 |
| `wallet_follow_candidate` | 94k+ | 35 MB | entity enrichment 候选信号 |
| `smart_wallet_market_info` | 55k+ | 51 MB | 市场维度表 |
| `smart_wallet_follow_task` | 47k+ | 72 MB | 跟单任务 |
| `smart_wallet_trade_event` | 39k+ | 25 MB | leader 事件入口 |
| `smart_wallet_leader_trade_outcome` | 32k+ | 37 MB | leader outcome 复盘 |

小但核心的执行表：

| 表 | 行数 | 定位 |
|---|---:|---|
| `smart_wallet_order` | 131 | 下单事实 |
| `smart_wallet_fill` | 131 | 成交事实 |
| `smart_wallet_position` | 69 | 持仓事实 |
| `smart_wallet_trade_order` | 69 | 生命周期派生视图 |
| `smart_wallet_cash_ledger` | 298 | 资金流水 |

当前空表：

| 表 | 建议 |
|---|---|
| `smart_wallet_market_snapshot` | 保留，修采集链路；它被入场风控、交易效果标签、市场监控依赖 |
| `smart_wallet_treatment_control_sample` | 标记 `RESEARCH_RESERVED`；没有生产生成/消费闭环 |

## 3. 推荐逻辑关系

```text
Leader / Wallet Dimension
  smart_wallet_profile
  smart_wallet_behavior_profile
  wallet_entity_map
  wallet_entity_feature_snapshot

Signal Intake
  smart_wallet_trade_event
    -> wallet_follow_candidate
    -> smart_wallet_follow_task

Market Dimension
  smart_wallet_market_info
  smart_wallet_market_concentration_snapshot
  smart_wallet_market_snapshot

Execution Facts
  smart_wallet_order
  smart_wallet_fill
  smart_wallet_position
  smart_wallet_cash_ledger

Derived / Analytics
  smart_wallet_trade_order
  smart_wallet_daily_report
  smart_wallet_order_analysis
  smart_wallet_leader_trade_outcome
  smart_wallet_trade_effect_label
  smart_wallet_wallet_feature_daily

Ops Queues
  smart_wallet_settlement_run
  smart_wallet_settlement_retry
  smart_wallet_claim_run
  smart_wallet_claim_retry
  smart_wallet_manual_allowlist

Research Reserved
  smart_wallet_treatment_control_sample
```

事实源规则：

- 订单事实以 `smart_wallet_order` 为准。
- 成交事实以 `smart_wallet_fill` 为准。
- 持仓事实以 `smart_wallet_position` 为准。
- 资金事实以 `smart_wallet_cash_ledger` 为准。
- `smart_wallet_trade_order` 只做生命周期展示/查询，可从 facts 重建。

## 4. 字段级问题

### 4.1 可以考虑归档或降级的字段

这些字段当前线上全空或全 0，且更像未来能力预留：

| 表 | 字段 | 当前情况 | 建议 |
|---|---|---|---|
| `smart_wallet_cash_ledger` | `balance_after` | 298/298 NULL | 若不做余额快照，移到后续 ledger balance 扩展 |
| `smart_wallet_order` | `fee_pol`, `gas_fee_usdc`, `poll_attempts`, `next_poll_at` | 全 0 或全 NULL | 如果当前 CLOB 执行不产 gas/POL，可先从核心查询模型隐藏 |
| `smart_wallet_fill` | `fee_pol`, `gas_fee_usdc` | 全 0 | 同上，保留 `trading_fee_usdc`/`fee_usdc` 即可覆盖当前会计 |
| `smart_wallet_position` | `fee_pol_total` | 全 0 | 与 order/fill fee 字段统一处理 |
| `smart_wallet_trade_order` | `exit_fee_usdc` | 全 0 | 若 exit fee 已进 fill/order，派生表不必单独维护 |
| `smart_wallet_profile` | `manual_recommended_exit_lane` | 73/73 NULL | 保留在人工覆盖模型，但不要进入核心入场逻辑 |
| `wallet_entity_map` | `funding_source_address`, `behavior_sim_score` | 全 NULL | 标记为 entity clustering v2 预留 |
| `wallet_follow_candidate` | `rejection_reason` | 全 NULL | 当前没有拒绝原因闭环，建议改为可选调试字段 |

### 4.2 不能仅凭空值删除的字段

这些字段虽然空值率高，但代表状态型字段，空是业务含义：

| 表 | 字段 | 原因 |
|---|---|---|
| `smart_wallet_follow_task` | `executed_at`, `external_order_id` | 只有实际执行成功才有值 |
| `smart_wallet_follow_task` | `primary_skip_reason`, `skip_reason_tags` | 只有跳过任务才有值 |
| `smart_wallet_order` | `error_message`, `exit_reason` | 只有失败或退出订单才有值 |
| `smart_wallet_settlement_run` / `claim_run` | `error_message` | 正常运行为空是合理状态 |
| `smart_wallet_leader_trade_outcome` | `settlement_price`, `is_win`, `resolved_outcome` | 未结算市场为空是合理状态 |

### 4.3 明显未闭环的分析字段

`smart_wallet_trade_effect_label` 当前 10,149 行里，以下字段全部 NULL：

- `mid_t_10s`, `mid_t_30s`, `mid_t_60s`, `mid_t_5m`, `mid_t_15m`
- `last_trade_t_10s`, `last_trade_t_30s`, `last_trade_t_5m`
- `paper_exit_price_30s`, `paper_exit_price_5m`, `paper_exit_price_rule`
- `paper_pnl_30s`, `paper_pnl_5m`, `paper_pnl_rule`
- `net_pnl_30s`, `net_pnl_5m`, `net_pnl_rule`
- `is_positive_ev_30s`, `is_positive_ev_5m`, `is_positive_ev_rule`
- `realized_slippage_bps`, `realized_slippage_usdc`

根因更可能是 `smart_wallet_market_snapshot` 没有数据，所以 T+10s/T+30s/T+5m 的市场路径无法计算。这里不要先删字段；应先决定是否继续做交易效果标签：

- 如果继续做：先修 `MarketSnapshotCollectorService` 的调度和采集量，再补 label backfill。
- 如果不继续做：把 `trade_effect_label` 降级为实验表，核心评分不要依赖这些字段。

### 4.4 `wallet_feature_daily` 的占位特征

以下字段全 0：

- `followable_rate_30d`
- `late_market_ratio_30d`
- `low_liquidity_ratio_30d`
- `pnl_30d`

代码里 `WalletArchetypeClassifierService` 和 `WalletBehaviorProfileService` 会读取 `followable_rate_30d`，所以当前“全 0”会压低行为画像质量。建议先修计算逻辑，再决定是否保留字段。

## 5. 表级处理建议

### 5.1 立即标记为非核心

`smart_wallet_treatment_control_sample`

- 当前 0 行。
- 代码层基本只有 Entity、Mapper、reset 清理。
- 建议从核心表文档、重置流程和 dashboard 里移出，标记 `RESEARCH_RESERVED`。

### 5.2 保留但修链路

`smart_wallet_market_snapshot`

- 当前 0 行。
- 代码依赖非常多：入场时机风控、交易盈利评分、position mark、trade effect label 都会读它。
- 建议先检查采集 job 是否启用、CLOB book 接口是否可用、是否因为市场/outcome 标准化导致查不到快照。

### 5.3 合并/瘦身候选

`smart_wallet_order`、`smart_wallet_fill`、`smart_wallet_position`

- `fee_pol/gas_fee_usdc/fee_pol_total` 当前全 0。
- 短期不要直接删列，因为 Controller/View/AccountingService 仍引用。
- 推荐先在 API 返回和前端展示层隐藏，再在一个版本后做代码移除和 DDL drop。

`smart_wallet_order_analysis`

- 最大表，450 MB。
- `opened_at/hold_minutes/market_end_at/exit_lead_minutes` 空值率 97%+。
- 建议拆成：
  - 必填闭环结果：wallet、market、outcome、closed_at、pnl、roi、is_win。
  - 可选生命周期扩展：opened_at、hold_minutes、exit_style、exit_lead_minutes。
- 如果保留单表，至少按 `closed_at` 做归档策略。

## 6. 建议执行顺序

1. 跑只读审计脚本，保存当前基线。
2. 在文档和代码常量里给表分层：`CORE_FACT`、`CORE_DIM`、`DERIVED`、`OPS`、`RESEARCH_RESERVED`。
3. 修 `smart_wallet_market_snapshot` 采集链路，否则 `trade_effect_label` 和 `wallet_feature_daily` 会继续产占位值。
4. 把 `treatment_control_sample` 从核心 reset/report/dashboard 中移出。
5. 隐藏 order/fill/position 中当前全 0 的 fee-pol/gas 字段展示。
6. 一个版本观察后，再做 DDL drop 或拆表迁移。

## 7. 审计脚本

只读脚本已放在：

`scripts/sql_audit/smart_wallet_schema_usage_audit.sql`

它输出：

- 表规模
- 空表
- 每列 NULL / 空字符串 / 0 值比例
- 高风险占位字段
- 重复字段名分布
