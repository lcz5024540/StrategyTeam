---
title: "CoinStrategy 资金、账户、风控与账本体系 v1"
type: capital-risk-ledger-design
status: draft
created: 2026-09-21
updated: 2026-09-21
tags: [strategy, capital, account, ledger, risk, pnl, fee]
---

# CoinStrategy 资金、账户、风控与账本体系 v1

> **上层总纲**：[CoinStrategy 策略总纲 v1](COINSTRATEGY_STRATEGY_MASTER_PLAN_V1.md)  
> **实施路线图**：[CoinStrategy 策略体系实施路线图 v1](COINSTRATEGY_STRATEGY_IMPLEMENTATION_ROADMAP_V1.md)  
> **趋势子系统**：[HTX 趋势策略系统 v1](HTX_TREND_STRATEGY_SYSTEM_V1.md)

> **结论先行**：CoinStrategy 必须先把账户、资金池、策略维度、费用、净收益、仓位风险和账本事件统一，再继续扩大趋势模型或套利模型。当前系统已经有 `htx_trade_account` 账户主表，但订单和仓位仍主要通过 `account_label` 归集，缺少稳定的 `account_id`、`strategy_family`、`strategy_name`、`model_name`、`run_id`、`factor_snapshot_id` 和统一净 PnL 账本。第一版改造目标不是重写交易逻辑，而是把现有交易结果可解释、可审计、可回测、可按账户与策略归因。

---

## 1. 设计目标

本设计解决四个问题。

| 问题 | 当前风险 | v1 目标 |
|---|---|---|
| 多账户归因不稳定 | 订单、仓位和页面主要靠 `account_label` 字符串聚合，后续 10 个/100 个账户会混乱。 | 使用 `account_id` 作为内部主键，`account_label` 只做展示和兼容。 |
| 策略收益不可拆 | 趋势、套利、网格、模型之间缺统一维度，亏损时只能看日志猜原因。 | 所有订单、仓位、候选、账本事件带 `strategy_family / strategy_name / model_name`。 |
| 费用和净收益口径不统一 | 手续费、资金费、滑点、已实现收益容易分散计算，页面可能误读。 | 统一以 `net_pnl_after_fee` 作为默认收益口径，同时保留 gross、fee、funding、slippage 明细。 |
| 风控缺资金视角 | 单策略可能在账户里开仓，但系统不知道资金池、风险预算和相关性占用。 | 建立 capital bucket 和 exposure snapshot，统一限制账户、策略、币种、方向和交易所风险。 |

---

## 2. 当前数据库现状

### 2.1 已存在账户主表

当前 HTX 模块已有账户表：

```sql
htx_trade_account
```

现有关键字段：

| 字段 | 当前作用 |
|---|---|
| `id` | 自增主键，但下游订单/仓位尚未全面引用。 |
| `account_label` | 展示和归集名，例如 `primary`、`TradeX`。 |
| `exchange` | 当前为 HTX。 |
| `market_type` | 当前多为 `USDT_SWAP`。 |
| `account_role` | `PRIMARY` / `FOLLOWER`。 |
| `enabled` | 账户是否启用。 |
| `trend_enabled` | 是否参与趋势策略。 |
| `mirror_enabled` | 是否参与镜像/跟随执行。 |
| `api_key_cipher` / `secret_key_cipher` | 加密后的凭据，不允许明文入库或日志输出。 |
| `sync_leverage` | 是否同步杠杆。 |
| `sort_order` | 页面或执行排序。 |

### 2.2 当前趋势订单和仓位归集方式

当前趋势表已经带 `account_label`：

```sql
htx_trend_position.account_label
htx_trend_order.account_label
```

这能支撑两个账户的基础展示，但不是长期稳定模型。原因是 `account_label` 是展示名，未来可能改名、重复、迁移交易所或拆分角色；稳定关联应使用 `account_id`。

### 2.3 当前缺口

| 缺口 | 影响 |
|---|---|
| 订单/仓位缺 `account_id` | 账户改名或多交易所同名时无法稳定关联。 |
| 账户缺手续费档案 | 不同子账号手续费率不同，策略 sizing 和净收益判断不准。 |
| 订单/仓位缺策略维度 | 趋势模型、套利模型、网格模型收益难以拆分。 |
| 缺统一账本事件 | 开仓、平仓、手续费、资金费、调仓、风控调整没有统一流水。 |
| 缺资金池配置 | 趋势资金、套利资金、缓冲资金无法统一分配。 |
| 缺风险快照 | 页面只能看订单和仓位，不能看风险预算占用。 |

---

## 3. 核心概念

### 3.1 账户 Account

账户是交易所内可下单、可持仓、可计费的资金单元。

| 字段 | 说明 |
|---|---|
| `account_id` | CoinStrategy 内部稳定账户 ID。 |
| `account_label` | 展示名，例如 primary、TradeX。 |
| `exchange` | HTX、OKX、Binance 等。 |
| `market_type` | SPOT、USDT_SWAP、COIN_SWAP。 |
| `account_role` | PRIMARY、FOLLOWER、ARBITRAGE_LEG、RESERVE。 |
| `fee_profile` | maker/taker/资金费结算规则。 |
| `credential_ref` | 加密凭据引用，不在页面和日志展示明文。 |
| `enabled_flags` | trend/arbitrage/grid 是否启用。 |

原则：`account_label` 可以展示，`account_id` 才能做关联。

### 3.2 策略维度 Strategy Dimension

每一笔候选、订单、仓位和收益都必须能落到同一组策略维度。

| 维度 | 示例 | 说明 |
|---|---|---|
| `strategy_family` | TREND / ARBITRAGE / GRID / RESERVE | 一级策略族。 |
| `strategy_name` | HTX_TREND_V1 / HTX_SPOT_PERP_FUNDING_V1 | 可部署策略实例。 |
| `model_name` | BOX_BREAKOUT / FIB_PULLBACK / MTF_MOMENTUM | 具体信号模型。 |
| `run_id` | `HTX-TREND-20260921-150000` | 一次调度扫描或执行批次。 |
| `factor_snapshot_id` | 12345 | 入场或拒绝时对应的因子快照。 |

### 3.3 资金池 Capital Bucket

资金池是策略级资金预算，不等于交易所账户余额。

| 资金池 | 目标 | 可用策略 |
|---|---|---|
| TREND | 收益弹性 | 趋势多模型、下跌做空、上涨做多。 |
| ARBITRAGE | 稳定收益 | 单所现货合约资金费套利、跨所资金费套利。 |
| GRID | 震荡收益 | 明确 RANGE 市场下的区间/网格。 |
| RESERVE | 风险缓冲 | 保证金缓冲、机会资金、异常回补。 |

原则：资金池只定义预算和上限，不直接替交易所划转资金。真实余额仍以交易所账户为准。

### 3.4 统一账本 Ledger

统一账本记录“发生了什么”和“钱从哪里来、到哪里去”。

账本事件包括：

| 事件 | 说明 |
|---|---|
| OPEN_ORDER | 开仓订单创建。 |
| OPEN_FILL | 开仓成交。 |
| CLOSE_ORDER | 平仓订单创建。 |
| CLOSE_FILL | 平仓成交。 |
| FEE | 手续费。 |
| FUNDING_FEE | 资金费收入或支出。 |
| REALIZED_PNL | 已实现盈亏。 |
| UNREALIZED_SNAPSHOT | 未实现盈亏快照。 |
| RISK_ADJUSTMENT | 风控降权、暂停、强制减仓。 |
| CAPITAL_ALLOCATION | 资金池预算调整。 |

---

## 4. 收益和费用口径

### 4.1 默认收益口径

页面、回测和日报默认展示扣费后净收益：

```text
net_pnl_after_fee
  = realized_pnl_gross
  + funding_fee_net
  - trading_fee_total
  - estimated_slippage_cost
```

对单笔已平趋势仓位，第一版可从现有字段计算：

```text
trend_net_realized_pnl
  = realized_profit
  - entry_fee
  - close_fee
```

如果交易所返回的 `realized_profit` 已经包含部分费用，必须在账户费率与成交明细回放中标记来源，不能重复扣费。第一版以“账本事件”为准：手续费单独入账，净收益由统一聚合层计算。

### 4.2 费用类型

| 类型 | 字段建议 | 说明 |
|---|---|---|
| 开仓手续费 | `entry_fee` / `trading_fee_open` | 真实成交后写入。 |
| 平仓手续费 | `close_fee` / `trading_fee_close` | 真实成交后写入。 |
| 预估手续费 | `estimated_entry_fee` / `estimated_close_fee` | 下单前 gate 和 sizing 使用。 |
| 资金费 | `funding_fee_net` | 套利和合约持仓必须单独记录。 |
| 滑点成本 | `slippage_cost` | 回测和实盘可估算，后续从成交均价与基准价计算。 |
| 点差成本 | `spread_cost` | 入场 gate 使用。 |

### 4.3 手续费率来源优先级

手续费率不能只用全局配置，因为多账户费率不同。

优先级：

```text
账户费率表 account_fee_profile
  → 交易所实时查询并缓存
  → 策略配置默认值
  → 保守兜底值
```

账户费率表至少记录：

| 字段 | 说明 |
|---|---|
| `account_id` | 账户。 |
| `exchange` | 交易所。 |
| `market_type` | SPOT / USDT_SWAP。 |
| `maker_fee_rate` | maker 费率。 |
| `taker_fee_rate` | taker 费率。 |
| `source` | DB_FIXED / EXCHANGE_QUERY / FALLBACK。 |
| `effective_from` | 生效时间。 |
| `updated_at` | 更新时间。 |

---

## 5. 数据模型规划

### 5.1 账户表增强

现有 `htx_trade_account` 保留，新增字段采用兼容迁移。

| 字段 | 类型建议 | 说明 |
|---|---|---|
| `external_account_id` | VARCHAR(64) | 交易所子账号 ID 或 UID。 |
| `settle_currency` | VARCHAR(16) | USDT、USDC 等。 |
| `margin_mode` | VARCHAR(16) | isolated / cross。 |
| `credential_status` | VARCHAR(16) | ACTIVE / ROTATING / DISABLED。 |
| `last_health_status` | VARCHAR(32) | API、余额、下单权限健康状态。 |
| `last_health_at` | DATETIME(3) | 最近检查时间。 |

迁移原则：

```text
先保留 account_label 全部兼容逻辑。
新增 account_id 写入后，页面和统计优先用 account_id。
确认稳定后，account_label 降级为展示字段。
```

### 5.2 账户费率表

```sql
coin_account_fee_profile
```

| 字段 | 说明 |
|---|---|
| `id` | 主键。 |
| `account_id` | 内部账户 ID。 |
| `account_label` | 兼容展示。 |
| `exchange` | 交易所。 |
| `market_type` | 市场类型。 |
| `maker_fee_rate` | maker 费率。 |
| `taker_fee_rate` | taker 费率。 |
| `funding_fee_mode` | funding 记录方式。 |
| `source` | DB_FIXED / EXCHANGE_QUERY / FALLBACK。 |
| `effective_from` | 生效时间。 |
| `created_at` / `updated_at` | 审计时间。 |

### 5.3 策略实例表

```sql
coin_strategy_instance
```

| 字段 | 说明 |
|---|---|
| `id` | 策略实例 ID。 |
| `strategy_family` | TREND / ARBITRAGE / GRID / RESERVE。 |
| `strategy_name` | 策略名。 |
| `model_name` | 模型名，可为空表示策略级实例。 |
| `exchange` | 交易所。 |
| `enabled` | 是否启用。 |
| `capital_bucket` | 默认资金池。 |
| `risk_profile` | 风控配置名称。 |
| `description` | 说明。 |

### 5.4 资金池配置表

```sql
coin_capital_bucket
```

| 字段 | 说明 |
|---|---|
| `id` | 主键。 |
| `bucket_code` | TREND / ARBITRAGE / GRID / RESERVE。 |
| `account_id` | 可为空；为空表示全局池。 |
| `allocated_usdt` | 分配预算。 |
| `max_exposure_usdt` | 最大风险暴露。 |
| `min_reserve_usdt` | 最小保留资金。 |
| `enabled` | 是否启用。 |
| `updated_reason` | 调整原因。 |

### 5.5 统一账本表

```sql
coin_strategy_ledger_event
```

| 字段 | 说明 |
|---|---|
| `id` | 主键。 |
| `event_key` | 幂等键。 |
| `event_type` | OPEN_FILL / CLOSE_FILL / FEE / FUNDING_FEE / REALIZED_PNL 等。 |
| `account_id` | 内部账户 ID。 |
| `account_label` | 兼容展示。 |
| `exchange` | 交易所。 |
| `market_type` | 市场类型。 |
| `strategy_family` | 策略族。 |
| `strategy_name` | 策略名。 |
| `model_name` | 模型名。 |
| `run_id` | 调度批次。 |
| `factor_snapshot_id` | 因子快照。 |
| `symbol` / `contract_code` | 标的。 |
| `position_id` | 仓位 ID。 |
| `order_id` | 订单 ID。 |
| `side` | LONG / SHORT。 |
| `amount_usdt` | 事件金额。 |
| `fee_usdt` | 手续费。 |
| `funding_fee_usdt` | 资金费。 |
| `gross_pnl_usdt` | 扣费前收益。 |
| `net_pnl_after_fee` | 扣费后净收益。 |
| `reason` | 开仓、平仓、调整原因。 |
| `occurred_at` | 事件发生时间。 |
| `created_at` | 入库时间。 |

### 5.6 风险快照表

```sql
coin_risk_exposure_snapshot
```

| 字段 | 说明 |
|---|---|
| `id` | 主键。 |
| `snapshot_time` | 快照时间。 |
| `account_id` | 账户。 |
| `strategy_family` | 策略族。 |
| `symbol` | 币种。 |
| `direction` | LONG / SHORT / NET。 |
| `notional_usdt` | 名义暴露。 |
| `margin_used_usdt` | 保证金占用。 |
| `unrealized_pnl_usdt` | 未实现盈亏。 |
| `max_loss_estimate_usdt` | 预估最大损失。 |
| `risk_status` | NORMAL / WATCH / REDUCE_ONLY / HALTED。 |
| `risk_reason` | 风险原因。 |

---

## 6. 写入链路

### 6.1 开仓前

开仓前需要形成完整候选上下文。

```text
扫描行情
  → 生成 market_regime_snapshot
  → 生成 factor_snapshot
  → 生成 model_signal
  → gate 判断
  → sizing 判断
  → 写 OPEN_ORDER intent
  → 发送真实订单
```

必须写入：

| 数据 | 用途 |
|---|---|
| `run_id` | 串联一轮扫描。 |
| `factor_snapshot_id` | 回放开仓依据。 |
| `strategy_family / strategy_name / model_name` | 模型归因。 |
| `expected_net_after_fee` | 确保预期收益覆盖费用。 |
| `entry_gate_reason` | 记录通过或拒绝原因。 |
| `sizing_reason` | 记录为什么开这么大。 |

### 6.2 成交后

成交后以交易所回报为准，写入订单表和账本事件。

```text
订单回报
  → 更新 htx_trend_order
  → 更新 htx_trend_position
  → 写 OPEN_FILL / CLOSE_FILL
  → 写 FEE
  → 已平仓时写 REALIZED_PNL
```

### 6.3 平仓后

平仓必须记录退出原因。

| 退出原因 | 说明 |
|---|---|
| MODEL_INVALIDATION | 模型失效，例如箱体跌回、Fibonacci 失败。 |
| TRAILING_STOP | 追踪止损。 |
| TAKE_PROFIT | 止盈。 |
| RISK_REDUCE | 风控降仓。 |
| ACCOUNT_SYNC_RISK | 账户或交易所仓位不一致。 |
| FUNDING_REVERSAL | 资金费方向变化。 |
| MANUAL_CLOSE | 人工清理。 |

---

## 7. 风控口径

### 7.1 分层风控

| 层级 | 风控项 | 动作 |
|---|---|---|
| 全局 | 总净值回撤、总保证金率、交易所 API 异常 | 暂停新开仓或只减仓。 |
| 账户 | 单账号日亏损、连续亏损、手续费异常 | 降低该账号 sizing 或暂停。 |
| 策略族 | TREND / ARBITRAGE / GRID 各自回撤 | 降低策略资金池权重。 |
| 模型 | 模型胜率、profit factor、平均净收益 | 降权或转观察。 |
| 币种 | 单币连续亏损、点差、流动性 | 冷却或禁止。 |
| 方向 | 全市场多头/空头拥挤 | 限制同向加仓。 |

### 7.2 暂停不是唯一风控

单日已平净亏达到阈值后，不应简单永久暂停新开仓。第一版采用分级动作：

| 条件 | 动作 |
|---|---|
| 轻微亏损但模型质量仍正常 | sizing 降低。 |
| 连续亏损集中在某模型 | 模型降权或转观察。 |
| 连续亏损集中在某币种 | 币种冷却。 |
| 账户手续费异常或成交劣化 | 账户降权。 |
| 账本和交易所仓位不一致 | 全局暂停新开仓，只允许对账和平仓。 |

### 7.3 多头行情加仓口径

当市场宽度显示 70% 以上币种上涨，且 BTC/ETH 多周期锚点同向，趋势资金池可以提高利用率，但必须满足：

| 条件 | 说明 |
|---|---|
| broad market regime = BROAD_UP | 大盘环境确认。 |
| BTC/ETH anchor 同向 | 主流币趋势确认。 |
| 候选模型独立通过 | 不允许仅凭大盘上涨直接开仓。 |
| 账户风险预算足够 | 保证金和日亏损限制未触发。 |
| 单方向暴露未超限 | 防止全仓同向拥挤。 |

---

## 8. 页面读取口径

### 8.1 总览页

总览页只看统一聚合结果，不逐个堆账户卡片。

| 模块 | 数据来源 |
|---|---|
| 总资金分配 | `coin_capital_bucket` + 交易所余额快照。 |
| 今日净 PnL | `coin_strategy_ledger_event.net_pnl_after_fee` 聚合。 |
| 当前暴露 | `coin_risk_exposure_snapshot` 最新快照。 |
| 策略族表现 | 按 `strategy_family` 聚合账本。 |
| 风控状态 | 最新 risk snapshot 和策略状态。 |

### 8.2 账户页

账户页支持 drilldown。

```text
账户池总览
  → 单账号净 PnL / 手续费 / 成交额 / 胜率
      → 该账号的策略族表现
          → 该账号的订单、仓位、账本事件
```

### 8.3 策略页

策略页按策略族和模型看表现。

| 视角 | 内容 |
|---|---|
| TREND | 模型候选、真实仓位、净收益、gate 拦截。 |
| ARBITRAGE | 对冲腿、资金费收益、对冲偏差、退出条件。 |
| GRID | 区间、网格密度、成交额、净收益。 |

---

## 9. 迁移顺序

### 9.1 P1 最小迁移

| 顺序 | 动作 | 验收 |
|---|---|---|
| 1 | 保留 `htx_trade_account`，补账户费率表 | primary、TradeX/后续账号都有 maker/taker 费率。 |
| 2 | 给趋势订单/仓位补 `account_id` 和策略维度 | 新数据同时写 account_id 与 account_label。 |
| 3 | 建统一账本表 | 开平仓、手续费、已实现收益能从账本查。 |
| 4 | 页面聚合改读账本 | 总览和账户页默认看扣费后净收益。 |
| 5 | 保留旧字段兼容 | 老数据通过 account_label 回填或兼容查询。 |

### 9.2 P2 最小迁移

| 顺序 | 动作 | 验收 |
|---|---|---|
| 1 | 建 `run_id` 生成规则 | 一轮扫描可串联市场、候选、gate、订单。 |
| 2 | 建因子快照表 | 每个候选可回放入场条件。 |
| 3 | 建 gate audit 表 | 每个未开仓原因可统计。 |
| 4 | 页面显示候选和拒绝原因 | 不依赖 Codex 定时扫日志。 |

---

## 10. 验收标准

第一版完成后必须满足：

| 验收项 | 标准 |
|---|---|
| 多账户 | 能按 `account_id` 查询 primary、TradeX 和后续账号的订单、仓位、净收益。 |
| 多策略 | 能按 TREND / ARBITRAGE / GRID 聚合收益和风险。 |
| 多模型 | 趋势内能按 BOX_BREAKOUT / FIB_PULLBACK / MTF_MOMENTUM 归因。 |
| 净收益 | 页面默认展示扣费后净收益，不再把 gross realized profit 当主收益。 |
| 手续费 | 每个账户有独立手续费率来源，开仓前预估收益覆盖费用。 |
| 风控 | 能看到账户、策略、币种、方向、交易所维度的风险暴露。 |
| 回放 | 任意一笔开仓能找到当时的因子、gate、sizing 和账户费率。 |
| 页面 | 总览页支持聚合，账户页支持 drilldown，不按账号数量堆卡片。 |

---

## 11. 非目标

| 非目标 | 原因 |
|---|---|
| 本文不直接修改真实开仓策略 | 先定账本和口径，防止策略继续黑箱运行。 |
| 本文不直接设计完整回测引擎 | 回测系统单独由 `COINSTRATEGY_BACKTEST_SYSTEM_V1.md` 定义。 |
| 本文不直接设计套利交易细节 | 套利子系统单独由 `HTX_FUNDING_ARBITRAGE_SYSTEM_V1.md` 定义。 |
| 本文不要求立即废弃 `account_label` | 生产兼容期必须保留展示和回填能力。 |

---

## 12. 下一步实现任务

| 优先级 | 任务 | 产物 |
|---|---|---|
| P0 | 写迁移 SQL 草案 | `htx_trade_account` 增强、账户费率表、账本表、风险快照表。 |
| P0 | 写实体和 mapper | account fee、strategy instance、ledger event、risk snapshot。 |
| P0 | 订单/仓位写入补维度 | 新订单带 `account_id / strategy_family / strategy_name / model_name / run_id`。 |
| P1 | 页面聚合改口径 | 总览和账户 drilldown 默认读净收益账本。 |
| P1 | 回填脚本 | 老订单用 `account_label` 映射 `account_id`，补策略默认值。 |
| P2 | 与因子快照联动 | 开仓账本事件带 `factor_snapshot_id`。 |

