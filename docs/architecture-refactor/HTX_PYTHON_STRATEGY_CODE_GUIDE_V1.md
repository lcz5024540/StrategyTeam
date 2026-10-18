---
title: HTX Python 当前策略编码指南 V1
author: Codex
date: 2026-10-18
status: active
type: technical-design
code_baseline: support_htx_python@c53c05981cffe56dfd3676330758b2f04e3a3fc4
scope: htx-trend-python
---

# HTX Python 当前策略编码指南 V1

## 1. 文档目的与事实边界

本文解释 `support_htx_python` 分支当前 Python 趋势策略的源码结构和真实调用顺序，重点回答以下问题：

- worker 如何启动，OBSERVE 与 LIVE 如何分流；
- 15m 与 4h 如何生成各自候选、开各自仓位、管理并退出各自持仓；
- 每一根 K 线经过哪些标准化、指标、市场状态、模型、策略政策、Gate 和 sizing 计算；
- 下单前后如何预占风险、持久化意图、处理部分成交和未知状态；
- 硬止损、legacy 兼容退出和 V4 退出的实际路由；
- 行情、决策、订单和研究证据如何保存和回放；
- 当前代码能力、研究结论和服务器实际运行状态之间有哪些边界。

**事实源**是提交 `c53c05981cffe56dfd3676330758b2f04e3a3fc4`。本文没有连接服务器，因此不声明线上实际安装包、环境变量、admission、waiver、持仓或订单状态。判断线上行为时，还必须核对运行包 SHA、配置指纹、数据库记录和 HTX 回查结果。

本文是当前源码说明，不替代十二阶段准入报告，也不授权部署或真实下单。

## 2. 系统总览

### 2.1 分层结构

当前实现把策略拆成八层：

| 层 | 主要模块 | 职责 |
|---|---|---|
| 进程与授权 | `worker.py`、`live_approval.py` | 解析运行模式，校验账户、版本、准入、风险边界和 writer lock |
| 周期与策略规格 | `timeframes.py`、`strategy_profiles.py` | 定义 15m/4h 周期合同、独立参数、策略版本与 hash |
| 行情与证据 | `market_data.py`、`research/store.py`、`research/collector.py` | 拉取闭合 K 线、盘口、合约规格、资金费并形成不可变证据 |
| 指标与市场状态 | `indicators.py`、`research/features.py`、`research/regime.py` | 计算指标、斜率、趋势、波动、流动性和数据完整性 |
| 信号与政策 | `java_entry_contract.py`、`strategy_decision.py`、`policy.py` | 生成模型候选，合并市场共识，输出唯一策略决策 |
| Gate 与仓位 | `execution.py`、`live_approval.py` | 逐项门禁、风险预算、张数和预期收益检查 |
| 订单与对账 | `execution_service.py`、`ledger.py`、`htx_v5.py` | 预占风险、写订单意图、提交 HTX、回查部分成交和 UNKNOWN |
| 持仓与退出 | `position_manager.py`、`exits.py` | 按 profile 管理持仓，执行硬止损、保护退出和减仓 |

整体架构图：

[打开 HTX Python 策略架构图](diagrams/htx-python-strategy-code-guide-v1-arch.drawio)

### 2.2 两个独立生命周期

| Profile | 策略版本 | 触发周期 | 趋势周期 | 确认/风险证据 | 参数前缀 |
|---|---|---|---|---|---|
| `INTRADAY_15M` | `HTX_TREND_15M_V2` | 15m | 60m | 30m、60m、4h、1d | `HTX_TREND_15M_*` |
| `SWING_4H` | `HTX_TREND_4H_V2` | 4h | 1d | 1d | `HTX_TREND_4H_*` |

每个 profile 有独立候选、策略规格、参数、持仓归属、LIVE 开关和准入。持仓行携带 `timeframe_profile`，对应的 position manager 只处理本 profile 的持仓。两个 profile 共用账户时，同一合约仍受账户级 `PROFILE_CONTRACT_MUTEX` 约束，防止 15m 和 4h 同时对同一合约建立相互难以归因的仓位。

## 3. 进程启动与运行模式

### 3.1 启动入口

命令入口最终进入 [`worker.py`](../../htx-trend-python/src/htx_trend/worker.py) 的 `main()`：

```text
CLI 参数
  ├─ --strategy-instance <实例名>
  ├─ --once                 只跑一个 tick
  └─ --interval-seconds 45  supervisor 周期
        ↓
加载 MySQL 配置与 schema 校验
        ↓
读取 strategy instance
        ↓
校验 family == TREND、mode、账户和授权
        ↓
OBSERVE worker 或 LIVE worker
        ↓
supervisor 循环；退出时释放连接和 writer lock
```

### 3.2 OBSERVE

OBSERVE 使用 HTX 公共行情接口，不创建私有交易客户端。单 profile 使用 `TrendObservationWorker`，双 profile 使用 `MultiTimeframeObservationWorker`。它会产生候选、原因和证据，但不进入真实下单队列。

非 legacy 策略仍需加载 sealed admission；OBSERVE 至少要求十二阶段中的前五阶段已满足，使观察到的候选能绑定确定的策略规格和数据口径。

### 3.3 LIVE

LIVE 启动按顺序检查：

1. strategy instance 绑定账户；
2. 账户角色是 `PRIMARY`；
3. 退出兼容/准入开关满足要求；
4. 禁止 mixed market 和 mirror 旁路；
5. 加载启用的 15m/4h profile；
6. legacy 加载 `LegacyLiveWaiver`，V2 加载 profile 专属 `LiveApproval`；
7. 校验 package、source、配置、账户、策略、admission、风险和退出版本 hash；
8. 获取单 writer lock；
9. 创建私有 HTX 客户端、entry executor 和每个 profile 的 position manager；
10. 进入 LIVE tick。

LIVE 硬风险边界由代码强制启用：单笔风险不超过权益 `0.5%`，总开放风险不超过 `1.5%`，最多 `3` 个持仓。普通 worker 配置中的更宽松默认值不能越过正式授权。

每个 profile 还受 `HTX_TREND_<PROFILE>_LIVE_ENABLED` 控制。关闭时仍可记录候选，但动作是 `SHADOW_ONLY`，不会调用真实 entry executor。

## 4. 单个 LIVE tick 的完整调用顺序

[`live_worker.py`](../../htx-trend-python/src/htx_trend/live_worker.py) 的一个 tick 按以下顺序执行：

1. **writer guard**：确认本实例仍持有写锁；丢锁立即停止新增风险。
2. **pending reconciliation**：先回查上次未决、部分成交或 UNKNOWN 的订单。
3. **position management**：在考虑新开仓前管理已有持仓；保护退出不依赖新证据写入成功。
4. **加载 profile policy**：读取 15m/4h 独立开关、策略规格和参数。
5. **判断稳定收盘**：仅在 K 线闭合并越过 close grace 后进行新扫描；同一闭合 bar 使用缓存避免重复计算。
6. **计算 breadth**：使用前一批已经持久化的市场信号，避免当前批扫描改变自身市场背景。
7. **并发扫描合约**：默认最大并发为 6；为每个合约拉 entry、trend 和 confirmation 数据。
8. **计算 BTC/ETH anchor**：聚合 15m、30m、60m、4h 的 EMA8/21 与价格方向。
9. **构造 decision context**：把市场状态、账户前态、profile、规则和证据引用冻结在同一个上下文。
10. **持久化 observation**：保存本轮指标、候选和诊断。
11. **选择 market policy**：确定当前市场对某个模型/方向是支持、反对还是混合。
12. **诊断与候选落库**：无论最终能否下单，都记录 `SHADOW_ONLY`、`PRE_FLIGHT`、`EXPIRED` 等候选状态与拒绝原因。
13. **候选排序**：只对仍有资格的候选按调整后得分排序。
14. **检查开仓窗口**：默认闭合后 15–60 秒；窗口之外进入过期或 carry-over 处理。
15. **执行容量预判**：OBSERVE、profile LIVE 关闭或容量不足时不提交。
16. **调用 entry executor**：重新读取账户事实，再跑正式 Gate、sizing、风险预占和 HTX 提交。
17. **记录 Gate/订单诊断**：保存每个门禁结果、最终动作和引用。
18. **写 heartbeat**：记录 worker 活性、完成时间和错误状态。

这一顺序有两个设计含义：已有仓位的安全处理早于新风险；市场 breadth 使用上一批持久化状态，避免同一轮内部顺序导致结果漂移。

## 5. 行情、K 线冻结与数据质量

### 5.1 闭合 K 线

[`market_data.py`](../../htx-trend-python/src/htx_trend/market_data.py) 把 HTX 返回值标准化为 `Candle`。`kline_window()` 请求 `size + 1` 根数据，完成以下处理：

1. 按 open time 排序；
2. 去除重复时间戳；
3. 剔除尚未闭合的最后一根；
4. 选择最后 `size` 根闭合 K 线；
5. 保存原始响应和标准化窗口；
6. 计算内容 hash，并把窗口引用写入本轮证据。

行情时间与首次观察时间分开保存。同一根 K 线被交易所修订时产生新内容版本，不能覆盖旧证据。

### 5.2 数据不足和证据故障

- 指标预热不足、时间倒序、缺口或高周期缺失返回明确的 `UNKNOWN`/`FAILED`，该 profile 停止新增风险。
- 证据写入失败时，行情对象仍可供已有仓位做硬止损和保护处理，但不能用于新开仓。
- 连续采集器以 15m、30m、60m、4h、1d 为默认周期；每次重叠拉取最后两根闭合 bar，以捕获 HTX 修订。
- 只有完整批次才推进 checkpoint；断点续采不会把半批数据标成 COMPLETE。

### 5.3 非 K 线市场事实

每个候选还会读取：

- 最优买卖价与价差；
- 五档可执行深度；
- 合约面值、最小张数、价格和数量精度；
- HTX 当前资金费率；
- 候选形成时的账户权益、保证金、已有仓位和开放订单。

资金费率来自 HTX 合约事实。回测使用对应合约、对应结算时点的真实 funding event；缺数据时不得自行假设固定 8 小时费率来伪造收益。

## 6. 指标、特征和 Regime

### 6.1 公共指标公式

[`indicators.py`](../../htx-trend-python/src/htx_trend/indicators.py) 是无数据库、无 HTTP 的纯计算层：

| 指标 | 当前实现口径 | 主要用途 |
|---|---|---|
| EMA | 第一根 close 初始化，递推计算，Decimal 18 位 | EMA20/60/120 排列、EMA8/21 anchor |
| Donchian | 指定窗口最高/最低边界 | 突破、箱体和结构失效 |
| ATR | True Range 的简单移动平均 | 波动门禁、止损距离、追价阈值 |
| ADX | legacy rolling DM/TR/DX 均值，不是 Wilder 平滑 | 趋势强度、评分、regime |
| ROC | 当前 close 与窗口起点的端点收益 | 动量方向和强度 |
| VWAP | typical price 按 volume 加权；无 volume 时用 amount | 当前记录/研究 |
| RSI | 平均涨跌幅计算 | 当前记录/研究 |
| MACD | EMA12、EMA26、signal EMA9 | 当前记录/研究 |

研究特征在 [`research/features.py`](../../htx-trend-python/src/htx_trend/research/features.py) 中补充收益率、滚动波动率、量比、K 线结构、`close_slope_20` 和 `ema20_slope_10`。两种斜率都使用线性回归斜率，并除以最新价格或 EMA 做归一化，便于跨币种比较。

### 6.2 live 入场实际使用情况

必须区分“计算了”与“参与动作”：

| 数据 | 计算/保存 | legacy/V2 当前入场评分或门禁 |
|---|---|---|
| EMA20/60/120 | 是 | 是 |
| Donchian | 是 | 是 |
| ROC | 是 | 是 |
| ATR | 是 | 是 |
| ADX | 是 | 是 |
| 量比 | 是 | 是 |
| VWAP | 是 | 否，记录/研究 |
| RSI | 是 | 否，记录/研究 |
| MACD | 是 | 否，记录/研究 |
| close/EMA slope | V2 regime 中计算 | 作为 profile regime 证据；legacy 评分不读取 |

`EMA_VWAP` 是历史模型名，当前 live 候选实现实际检查 EMA 排列、ROC 和 ADX，并没有把 VWAP 值作为该候选的硬条件。排障时不能仅凭模型名称推断 VWAP 已参与开仓。

### 6.3 RegimeSnapshot

[`research/regime.py`](../../htx-trend-python/src/htx_trend/research/regime.py) 针对 profile 的趋势周期生成 `RegimeSnapshot`：

- EMA20/60/120 排列；
- 收盘价和 EMA20 斜率；
- ADX、ROC；
- ATR 百分比及历史分位；
- 成交额、量比；
- spread 和 executable depth；
- 数据完整性状态和缺失原因。

15m 用 60m 判断趋势，30m/60m/4h/1d 提供确认和风险证据；4h 用 1d 判断趋势并由 1d 确认。缺少要求的高周期数据时返回 `UNKNOWN`，不会自动当成中性或通过。

## 7. 候选生成与策略决策

### 7.1 基线评分

[`java_entry_contract.py`](../../htx-trend-python/src/htx_trend/java_entry_contract.py) 保留 Java entry 行为一致基线。默认参数为：

```text
EMA: 20 / 60 / 120
Donchian: 20
ROC: 12
ATR: 14
ADX: 14
Volume ratio: 20
最低 ATR%: 0.001
最低 ADX: 16
最低 |ROC|: 0.0015
最低量比: 1.10
最低总分: 60
多空分差: 12
Box lookback: 32
Fib breakout lookback: 8
```

所需预热长度取最长指标窗口再加 5 根；默认至少 125 根闭合 K 线。

多空分别累计基础分：

| 因子 | 分值 |
|---|---:|
| EMA 完整多头/空头排列 | 30 |
| EMA 部分排列 | 12 |
| ROC 动量 | 最多 20 |
| Donchian 突破 | 20 |
| 符合方向的 ADX | 10 |
| ATR 波动资格 | 10 |
| 量比资格 | 10 |

只有一侧总分不低于 60，且领先另一侧至少 12 分，才形成方向偏置。低 ATR 市场标记为低波动；完整 EMA 排列且 ADX 达标标记为趋势，否则进入 range/mixed 语义。

### 7.2 三类主模型

1. **Donchian**：检查通道突破与 box 结构。通道突破加 12 分，同时满足 box 突破加 15 分；失效位取相反边界，目标用箱体扩展。
2. **Fibonacci**：先确认此前突破，再检查价格是否回踩 38.2%–61.8% 区域并重新站回 EMA20。一个确认加 10 分，两个确认加 20 分；目标区间为 1.272–1.618 扩展。
3. **EMA_VWAP momentum**：检查 EMA 排列、ROC 方向和 `ADX >= 20`，满足加 12 分。当前实现不读取 VWAP 值。

每个候选包含方向、模型、基础分、结构 key、失效价、目标价和 expected R。多个同向模型是对同一结构的确认来源，不能对同一结构重复产生订单。

### 7.3 可重复决策

[`strategy_decision.py`](../../htx-trend-python/src/htx_trend/strategy_decision.py) 是回测、OBSERVE、PAPER 和 LIVE 共用的确定性决策核。输入包括冻结 K 线、profile、配置和 admission surface，输出包括：

- 指标快照与候选；
- `input_hash`；
- `config_hash`；
- `decision_hash`；
- 条件结果和最终候选。

V2 的输入 profile 与策略规格不一致时直接报错。非 legacy 策略缺少 admission surface 时也不能计算可执行动作。

当前有一个需要明确的实现差距：该模块内的 `DecisionTrace` 直接写入逻辑目前只对 legacy V1 开启；V2 虽返回 hash，并在外层保存 observation/candidate/Gate 证据，但没有在这个位置写同等的完整 trace。离线复算时应检查外层证据是否已经闭包，不能只查这一张 trace。

## 8. 市场共识和候选排序

### 8.1 Breadth

Breadth 使用此前已持久化的合约信号：至少 20 个有效样本；同向占比达到 70%，且反向不超过 15%，才判定为广泛上涨或下跌。样本不足保持未知。

### 8.2 BTC/ETH Anchor

BTC 和 ETH 分别在 15m、30m、60m、4h 上比较 EMA8/21 与最新价格方向。至少 4 个信号同向、覆盖至少 2 个 anchor symbol、反向信号不超过 1 个，才形成明确 anchor 共识。

### 8.3 Market policy

Policy 先精确匹配主模型、确认模型、市场方向和基础分阈值，再修改**排名**，不会绕过正式 Gate：

- 模型权重：Donchian `1.2`、EMA `1.0`、Fib `0.8`；
- 与市场共识同向：`+8 × weight`；
- 与市场共识反向：`-12 × weight`；
- 无共识：`-2 × weight`。

Policy sizing 基础倍率为：mixed `0.5`；score ≥ 95 为 `1.5`；score ≥ 90 为 `1.25`；否则 `1.0`。entry executor 再结合 breadth 和信号强度调整，最终倍率限制在 `0.25–1.8` 范围内。

## 9. 开仓全流程

开仓流程图：

[打开开仓流程图](diagrams/htx-python-strategy-code-guide-v1-entry-flowchart.drawio)

### 9.1 扫描阶段

对每个合约和 profile：

```text
闭合 entry K 线 + trend K 线 + confirmations
        ↓
不可变证据写入是否成功？ —— 否 → UNKNOWN/FAILED，禁止新增风险
        ↓ 是
指标快照 + RegimeSnapshot
        ↓
三类主模型计算候选
        ↓
Breadth + BTC/ETH anchor + policy
        ↓
候选去重、排序、开仓窗口检查
        ↓
profile LIVE disabled？ —— 是 → SHADOW_ONLY
        ↓ 否
entry executor 重新读取账户事实并执行正式 Gate
```

### 9.2 正式 Gate 顺序

[`execution.py`](../../htx-trend-python/src/htx_trend/execution.py) 的 Gate 按顺序 fail closed：

1. model score（仅授权的 relaxed 模式可改变）；
2. funding 可用性和不利费率上限，默认 `0.0006`；
3. 行情新鲜度；
4. 冷却：亏损 1h、保本 30m、盈利 15m、强信号重入 10m；
5. 逆市场共识限制；
6. candidate 状态；
7. mixed market 质量；
8. Fibonacci 专属质量；
9. structure key 唯一性；
10. spread 不高于 `0.001`；
11. ATR% 不高于 `0.05`；
12. 交易所已有 open order；
13. 交易所已有外部仓位；
14. profile/账户容量；
15. 追价不超过 `0.2 ATR` 或配置的 chase rate；
16. 价格、数量、合约精度有效；
17. 单笔、组合和持仓数硬风险；
18. 平均成交额资格；
19. 五档深度至少覆盖订单量的 3 倍；
20. 可选 fee protection；
21. 保证金充足；
22. 全部通过后状态为 `READY`。

Gate 产生逐条件结果，保存实际值、阈值、PASS/FAIL/UNKNOWN/SKIPPED 和原因。不能用一个布尔值替代完整门禁轨迹。

### 9.3 Sizing

仓位计算以结构失效位到计划入场价的距离作为止损风险：

```text
每张风险 = |entry - invalidation| × contract_value
风险允许张数 = 账户允许风险金额 / 每张风险
名义额允许张数 = 目标或最大名义额 / 每张名义额
最终张数 = 各风险、名义额、深度、最小张数和授权上限的交集
```

正式 LIVE 使用所有边界的最小值。V1 的 Java compatibility 路径只用于行为一致基线，不能覆盖 hard-live 风险上限。

### 9.4 提交订单

[`execution_service.py`](../../htx-trend-python/src/htx_trend/execution_service.py) 中的 `HtxTrendEntryExecutor` 在真正调用 HTX 前再次执行：

1. 校验 admission action surface 至少允许 `RANKING/GATE/SIZING`；
2. 获取账户级同合约 mutex；
3. 加载本 profile 最近一笔已完成交易，确定冷却；
4. 重新选择精确 policy；
5. 从 HTX 私有 API 读取费率、持仓、开放订单、余额、权益、可用保证金和深度；
6. 执行 Gate，并保存 gate trace；
7. 计算张数和价格计划；
8. 在数据库预占风险；
9. 设置杠杆；
10. claim 本轮执行权；
11. **先写 position 和 order intent**；
12. 调用 HTX 市价下单；
13. 接受后把状态更新为 `ENTRY_SENT`；
14. 后续只按 client order id 回查，不重发相同订单。

如果异常发生在确认“尚未提交”之前，状态记为 `REJECTED` 并释放风险；如果可能已经提交但响应未知，状态记为 `UNKNOWN`，保留风险预占和 structure claim，等待回查。系统不会因为超时自动再发一笔。

## 10. 成交、部分成交和账本

订单回查以 HTX 累计成交量为准，账本只应用新增的成交差额：

- 0 成交：订单保持等待或最终拒绝/撤销；
- 部分成交：建立实际成交数量的持仓，剩余部分继续回查；
- 全部成交：订单完成，持仓进入 ACTIVE；
- 重复回调/回查：由 client order id 和累计量保证幂等；
- 无法判断交易所状态：保持 UNKNOWN，不释放风险，不重复下单；
- 最终全部平仓：释放对应风险预占和 structure claim。

一次开仓到最终全部平仓是一笔完整交易；部分成交和分批退出都是该 position 生命周期的事件，不重复计数。

生命周期泳道图：

[打开订单与持仓生命周期泳道图](diagrams/htx-python-strategy-code-guide-v1-lifecycle-swimlane.drawio)

## 11. 持仓管理和退出

### 11.1 profile 隔离

[`position_manager.py`](../../htx-trend-python/src/htx_trend/position_manager.py) 只查询与自身 `timeframe_profile` 相同的持仓。15m manager 不会依据 15m 信号关闭 4h 持仓，4h manager 也不会依据 4h 信号关闭 15m 持仓。

管理每个仓位前先回查未决 close order。交易所数量必须与本地账本一致，反向持仓必须为 0；不一致时把仓位标记为 UNKNOWN 并停止自主接管。

### 11.2 硬止损优先

硬止损用订单簿可执行参考价和持仓已保存的结构边界判断，优先级高于新模型计算。即使行情证据、资金费或模型刷新失败，已有仓位仍可执行硬止损。关闭仓位前先取消剩余 entry order，避免开仓残单在退出后继续成交。

### 11.3 正常管理输入

正常管理读取 160 根本 profile 闭合 K 线，检查新鲜度，并重新运行同源信号。确认周期使用 EMA8/21 判断方向。状态持续保存：

- 入场后最高价/最低价；
- MFE/MAE；
- 当前保护价；
- 是否已 armed；
- 最大 R；
- flat-loss 和 breach 的连续闭合 bar 计数。

HTX 当前资金费率超过不利阈值 `0.0012` 时可触发资金费退出。该判断使用当前费率，不等同于完整累计 funding ledger；绩效报告仍应依据真实 funding event 对账。

### 11.4 当前真实退出路由

当前源码并非所有模型都进入 V4：

```text
硬结构止损
   ↓ 未触发
exit engine enabled 且 model == FIB？
   ├─ 是 → V4 decision
   │        ├─ engine_live=false：只记录建议，不改变真实仓位
   │        └─ engine_live=true：允许减仓/平仓动作
   └─ 否 → legacy compatibility exit
```

V4 的优先级是：

1. 结构失效/初始止损；
2. flat-loss 独立失效；
3. 资金费成本；
4. 保护价 breach 后的部分或全部退出；
5. profit-at-risk；
6. 否则继续持有或 arm 新保护状态。

legacy compatibility exit 处理硬失效、反向信号和 ATR trailing。

这里存在必须在准入前收口的语义差距：`ProfileStrategySpec.exit_version` 声明 V4，但运行时 Donchian 和 EMA 模型仍走 legacy exit，只有 Fib 进入 V4 决策。文档、admission 和运行代码必须使用同一口径，不能仅凭规格字段声称全部持仓已使用 V4。

### 11.5 退出下单

退出先在账本创建 reduce-only intent，再调用 HTX。响应未知时保持 UNKNOWN 并持续回查；不能把本地写入成功当作交易所已经平仓。分批退出更新剩余数量，只有最终数量归零才结束 position。

## 12. 证据、条件库与可复现性

### 12.1 不可变证据

[`research/store.py`](../../htx-trend-python/src/htx_trend/research/store.py) 使用 SHA-256 内容寻址文件和 SQLite 索引：

- CAS 文件不覆盖；
- 写文件和目录均执行 fsync；
- SQLite 使用 WAL 和 FULL synchronous；
- outbox 支持待发送重试；
- checkpoint 只引用完整批次；
- `verify` 可递归校验 manifest 和内容 hash。

这套证据既保存连续历史数据，也保存 live 决策实际使用的窗口。数据集完整不代表某次决策证据完整，两者分别验证。

### 12.2 条件合同

[`research/contracts.py`](../../htx-trend-python/src/htx_trend/research/contracts.py) 定义：

- `FeatureSpec`：公式、输入、窗口、单位和版本；
- `ConditionSpec`：条件 id、阶段、依赖、参数和用途；
- `ConditionResult`：实际值、阈值、状态、原因和证据引用；
- `StrategySpec`：条件组合、优先级、状态转换和参数；
- `DecisionContext`：决策时可知的行情、账户和持仓事实；
- `DecisionTrace`：状态前后、全部条件和最终动作。

条件状态统一为 `PASS / FAIL / UNKNOWN / SKIPPED`。条件目录覆盖数据资格、市场环境、候选、开仓 Gate、sizing、持仓、退出和安全约束。实验注册表禁止修改订单幂等、账户隔离、reduce-only 等安全条件。

### 12.3 十二阶段 action surface

| 已完成阶段 | 允许作用面 |
|---|---|
| 1–5 | OBSERVE |
| 1–7 | BACKTEST |
| 1–10 | PAPER |
| 1–12 | RANKING、GATE、SIZING、EXIT 等正式动作 |

Legacy LIVE 通过最长 45 天的不可变 waiver 绑定 source/package/config/account/risk/V4。V2 使用单 profile `LiveApproval`，并绑定 spec hash 和 sealed admission。任何绑定项变化都使旧授权失效。

## 13. 回测、研究与当前 R4/R5 状态

### 13.1 全生命周期回测

回测与 LIVE 复用指标、候选、Gate、sizing 和退出核，适配器负责模拟时钟、账户和成交。默认执行规则：

- 闭合 K 线收盘产生信号，下一根可交易 K 线开盘成交；
- 市价加入方向性滑点、费用和真实 funding event；
- 限价必须被真实穿越，否则过期；
- 单根同时触发止损和目标时先执行不利结果；
- 跳空止损取 open 与 stop 中更不利的成交价；
- 单笔模拟成交不超过该 K 线成交量的 1%；
- 支持部分成交、撤单、最小张数和合约面值。

模拟结果类型是 `STRATEGY_SIMULATION`，不能标成真实成交复现。历史决策复现则要求用当时完整证据包重算并比对 decision hash。

### 13.2 研究命令入口

`htx-trend` CLI 当前提供：

- 账本/证据：`init-ledger`、`verify-evidence`、`evidence-entry-replay`；
- 数据：`collect-history`、`collect-market-facts`、`collect-tick`；
- 回放：`replay`、`compare`、`dataset-replay`；
- 授权：`validate-admission`、`validate-waiver`、`validate-approval`；
- 研究：`profile-research`、`candidate-labels`、`condition-audit`；
- 优化：`intraday-optimize`、`swing-optimize`、`exit-optimize`、`combination-study`；
- 独立轮次：`preregister-independent-round`、`audit-independent-data`、`independent-profile-study`。

### 13.3 R4/R5 的当前能力边界

- **R4 / 15m**：研究 Donchian 突破后的 1–3 根 bar retest/reclaim，边界在候选形成时冻结，避免未来数据改变历史标签。
- **R5 / 4h**：研究最近 3 根闭合 1d bar 的趋势持续性；要求至少 123 根日 K，EMA20/60/120、close slope、ROC 同向且 `ADX >= 16`。

当前已经完成的是工程链路和合成样本验收。已有独立证据窗口只有约 7 天，而 R4 要求 60 天、R5 要求 180 天，因此真实 OOS 结果必须 fail closed：没有足够数据时只能给 `INSUFFICIENT_EVIDENCE`，不能把合成测试写成盈利证明。此前 R2 经济性结果不通过，也不能因为工程完成而改写为 PASS。

## 14. 关键状态与排障入口

| 现象 | 首查位置 | 解释 |
|---|---|---|
| 有指标但无候选 | observation、模型诊断 | EMA/ROC/ADX/结构条件未形成候选 |
| 有候选但不下单 | candidate state、profile LIVE 开关 | 可能是 SHADOW_ONLY、PRE_FLIGHT、EXPIRED |
| Gate 拒绝 | gate trace 第一项 FAIL/UNKNOWN | 按真实 Gate 顺序定位，不能只看最终 reason |
| 15m 有仓、4h 不管理 | `timeframe_profile` | 正常隔离行为 |
| 同合约另一 profile 不开 | contract mutex/外部仓位检查 | 账户级互斥生效 |
| 下单超时后不重发 | order state UNKNOWN | 等 client id 回查，防重复订单 |
| 证据库失败但仍在平仓 | position protection path | fail closed 只阻止新增风险，不阻断保护退出 |
| 标称 V4 但看到 legacy reason | position model | 当前只有 Fib 路由到 V4 |
| VWAP/RSI/MACD 有数值但未影响订单 | factor snapshot/score | 当前仅记录或研究用途 |
| 4h 无候选但不再报 TIMEFRAME_UNSUPPORTED | daily regime/数据长度 | 4h 已支持，仍可能因 1d 证据或结构条件失败 |

## 15. 模块索引

| 模块 | 开发者应从这里查看什么 |
|---|---|
| [`worker.py`](../../htx-trend-python/src/htx_trend/worker.py) | 进程入口、运行模式、授权、worker 装配 |
| [`live_worker.py`](../../htx-trend-python/src/htx_trend/live_worker.py) | tick 顺序、扫描、候选排序与提交入口 |
| [`timeframes.py`](../../htx-trend-python/src/htx_trend/timeframes.py) | 15m/4h 周期合同 |
| [`strategy_profiles.py`](../../htx-trend-python/src/htx_trend/strategy_profiles.py) | profile 独立规格、参数和 hash |
| [`market_data.py`](../../htx-trend-python/src/htx_trend/market_data.py) | 闭合 K 线、盘口、资金费、证据写入 |
| [`indicators.py`](../../htx-trend-python/src/htx_trend/indicators.py) | live 公共指标公式 |
| [`research/features.py`](../../htx-trend-python/src/htx_trend/research/features.py) | 研究特征和斜率 |
| [`research/regime.py`](../../htx-trend-python/src/htx_trend/research/regime.py) | 多周期市场状态 |
| [`java_entry_contract.py`](../../htx-trend-python/src/htx_trend/java_entry_contract.py) | 基线评分和三类模型候选 |
| [`strategy_decision.py`](../../htx-trend-python/src/htx_trend/strategy_decision.py) | 同源确定性决策、输入和输出 hash |
| [`execution.py`](../../htx-trend-python/src/htx_trend/execution.py) | Gate、价格计划和 sizing |
| [`execution_service.py`](../../htx-trend-python/src/htx_trend/execution_service.py) | 私有账户事实、风险预占、订单提交和回查 |
| [`position_manager.py`](../../htx-trend-python/src/htx_trend/position_manager.py) | profile 持仓管理、硬止损和退出路由 |
| [`exits.py`](../../htx-trend-python/src/htx_trend/exits.py) | V4/legacy 退出状态与动作优先级 |
| [`research/store.py`](../../htx-trend-python/src/htx_trend/research/store.py) | CAS、manifest、outbox、checkpoint 和校验 |
| [`research/collector.py`](../../htx-trend-python/src/htx_trend/research/collector.py) | 连续行情采集、修订保存和断点续采 |
| [`research/contracts.py`](../../htx-trend-python/src/htx_trend/research/contracts.py) | 特征、条件、策略、上下文和 trace 合同 |

## 16. 当前已知差距与修改原则

### 16.1 已知差距

1. V2 决策返回 hash，但 `strategy_decision.py` 内部的完整 `DecisionTrace` 写入仍只覆盖 legacy V1；应统一 V2 的 trace 闭包。
2. Profile spec 声明 V4，但实际仅 Fib 使用 V4，Donchian/EMA 仍走 legacy exit；需要统一规格、admission 和运行时路由。
3. `EMA_VWAP` 名称与实际实现不一致；VWAP 当前不参与该模型动作。
4. 当前资金费退出依据单次当前费率，累计资金费归因依赖账本/回测事件；两者不能混为同一指标。
5. R4/R5 工程链已完成，但真实冻结数据窗口不足，不能给策略有效性 PASS。
6. 本文没有核验服务器 package、环境变量、数据库或 HTX 订单；线上状态必须另做只读运行审计。

### 16.2 修改策略代码时的约束

- 指标、条件、动作必须分层；新增指标不能悄悄变成 Gate。
- 每个参数必须属于明确 profile 和策略版本，4h 不读取 15m 默认值。
- 新规则创建新 spec/hash/admission；已有持仓继续绑定入场时版本。
- 所有新开仓路径先落证据、意图和风险预占，再调用交易所。
- UNKNOWN 不等于失败，也不等于可重试提交；先按 client id 回查。
- 退出更改必须同时更新状态机、账本、回测、PAPER 和准入证据。
- 实验可开关研究条件，不能关闭幂等、账户隔离、reduce-only 和硬风险。
- 回测盈利不等于 LIVE 可用；必须经过真实数据审计、OOS、Shadow、PAPER 和 formal guard。

## 17. 一次候选到完整平仓的最短调用摘要

```text
worker.main
  → LiveTrendWorker.tick
    → reconcile pending orders
    → ProfilePositionManager.manage_all
    → MarketDataService.kline_window
    → RegimeSnapshot + StrategyDecisionEngine.evaluate
    → breadth/anchor + MarketPolicy
    → candidate persistence/ranking
    → HtxTrendEntryExecutor.execute
      → exchange account facts
      → evaluate_gate
      → size_position
      → reserve risk + persist intent
      → HTX place order
      → reconcile by client order id
    → position ACTIVE
    → next ticks: hard stop first
    → Fib: V4 decision；Donchian/EMA: legacy exit
    → persist reduce-only close intent
    → HTX close + reconciliation
    → final fill → position CLOSED + release risk
    → ledger/evidence/performance/research
```

这条链路是理解当前代码最重要的入口。任何“为什么没开仓”“为什么退出”“为什么回测与 LIVE 不同”的问题，都应沿同一条链查看当时冻结输入、profile/spec hash、第一项失败条件、订单回查状态和实际成交账本。
