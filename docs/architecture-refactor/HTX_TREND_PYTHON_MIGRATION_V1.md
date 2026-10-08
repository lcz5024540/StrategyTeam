---
title: "HTX 趋势策略 Python 迁移计划 v1"
type: execution-plan
status: active
created: 2026-10-08
tags: [htx, trend, python, migration, execution]
---

# HTX 趋势策略 Python 迁移计划 v1

## 目标与边界

将当前已部署的 `codex/htx-model-routing@dd2e1417` 中**HTX USDT 合约趋势策略**迁移到独立 Python 运行时。迁移目标是建立完整的、可回放、可纸面验证的 Python 策略闭环，最终再决定切换真实执行。

不迁移：移动网格、现货做市、资金费套利、Polymarket、Web 页面、Java 通用客户端、无关数据库表。Java 服务在 Python 回放、纸面和灰度验收完成前继续是唯一真实交易执行者。

## SDK 决定

已核验 HTX 官方仓库 [`huobiapi/huobi_Python`](https://github.com/huobiapi/huobi_Python)，基线 commit `8a96761845302daf585010784150779da5edc206`。它以现货 V3 为主，合约仅保留旧 `/linear-swap-api/v1/swap_cross_order`，没有当前 Java 趋势系统所需的 V5 仓位、委托、杠杆、撤单和 `reduce_only` 完整接口。

因此 Python 工程不把旧 SDK 当作真实执行依赖，而是实现窄范围 `HtxV5Client`：保留 HTX HMAC-SHA256 规范、当前已验证的 V5 端点和 typed 请求模型。该 client 默认禁止所有变更请求；真实执行必须经后续灰度显式开启。

## 迁移顺序

| 阶段 | 迁移内容 | 验收 | 实盘边界 |
|---|---|---|---|
| P0 | Python 工程、官方 SDK 适用性结论、V5 client、密钥与默认禁交易 | 请求签名、端点、`reduce_only`、禁交易单测 | 不部署、不下单 |
| P1 | K 线、盘口、资金费、合约精度、实时价格输入和运行 K 线归档 | 数据事实可审计、无前视、周期完整 | 只读 |
| P2 | EMA/ATR/ADX/ROC、Donchian、Fibonacci、市场广度、多周期状态 | 每个模型能给出独立结构、失效和目标假设 | 回放/纸面 |
| P3 | 候选排序、模型专属 Gate、冷却解冻、价格计划与仓位 sizing | 覆盖完整开仓合同，逐项说明通过或拒绝 | 纸面 |
| P4 | 持仓趋势判断、MFE/MAE、利润保护、动态减仓、失效与风险退出 | 事故样本和冻结集退出结果可解释 | 纸面 |
| P5 | 订单、成交、仓位、账户和镜像账本；主账号小仓影子比对 | Java/Python 订单、费用和净 PnL 可对账 | 影子 |
| P6 | 单账号小仓灰度与回滚 | 扣费后 PF、最大回撤、执行失败率满足阈值 | 独立批准 |

## Java 到 Python 的核心映射

| Java 能力 | Python 目标模块 | 迁移原则 |
|---|---|---|
| `HtxUsdtSwapService` / `HtxRestClient` | `htx_trend.htx_v5` | 先保持端点与签名兼容，再逐步补 WSS。 |
| `HtxTrendEnsembleSignalCalculator` / 指标观察器 | `htx_trend.indicators` | 只用已收盘 K 线，避免前视。 |
| `HtxTrendCombinationCalculator` / 市场状态 | `htx_trend.models`、`htx_trend.gates` | 模型独立出候选，证据不重复计分。 |
| `HtxTrendFollowingService` | `htx_trend.runtime` | 拆为扫描、执行预检、订单、持仓管理，禁止再形成单个巨型服务。 |
| `HtxTrendExitDecisionEngine` | `htx_trend.exits` | 固定 v4 语义，不私自引入 v5。 |
| `HtxTrendRecorder` | `htx_trend.ledger` | 先复用现有账本语义，后续通过迁移决定独立 schema。 |

## 当前进度

P0 已完成：新建 `htx-trend-python` 独立工程；`HtxV5Client` 已覆盖公开 K 线、盘口、合约信息、资金费，以及私有余额、持仓、开放委托、杠杆、下单、撤单和订单查询接口。签名、公开请求不带密钥、默认阻断订单、以及 V5 `reduce_only` 请求体均有单测。

P1 已完成第一部分：Python 已新增只读的 `market_data` 与异步 `evidence` 模块。所有指标入口只接收已收盘且经过 grace 校验的 K 线；K 线按周期排序、去重、连续性校验。盘口、资金费率和合约精度也已转换为 typed 输入。每个实际计算窗口可异步写入本地、内容寻址的 K 线和快照 JSON；落盘队列满或写入失败只记诊断，绝不阻塞候选扫描或改变交易决定。

P2 已完成第一部分：Python 已实现确定性的 EMA、ATR、ADX、ROC、VWAP、RSI、MACD，以及 Donchian、Fibonacci 回踩、EMA/VWAP 动量三条独立候选生成链。每个候选保留自己的入场价、失效价、目标区、预期 R 和结构证据；市场广度只作显式状态输入。指标和候选尚未声称与 Java 达到逐字段同值，下一步会以冻结 K 线回放产出做差异报告，任何差异均不得进入纸面或真实执行。

P3 已完成策略内核：Python 以相同完成 K 线并行生成 Donchian、Fibonacci、EMA/VWAP 三类候选，再基于市场广度、BTC/ETH 锚点、候选结构质量、确认/冲突证据和剩余风险收益选择本轮主执行假设。开仓 gate 已按运行模式外层 → 数据时效 → 市场状态 → 冷却/结构占用 → Fibonacci 质量 → 盘口/订单状态 → 追价 → 深度/流动性 → 成本 → 保证金/风险预留的顺序拆分，并返回可持久化阶段原因。候选结构身份含模型、方向、当前 bar 和 Fibonacci 的独立突破来源，禁止下一笔同币种交易沿用上一笔结构。`ExecutionFacts` 将真实费率、深度、订单、外部仓位、保证金、杠杆、并发与预留额度设为显式输入；严格模式下任何缺失事实都会拒绝。所有输出仍是纯 `GateResult`，没有下单权。

P4 已完成第一部分：Python 已增加 `fib-exit-engine-v4` 的纯决策实现，包含原有状态阶段、模型/初始止损优先级、持仓趋势状态、持久化武装的利润保护、MFE 成本地板、保护价穿越确认、趋势衰竭、动态剩余风险敞口、资金费成本退出及未武装亏损预算。它仍只输出 `ExitDecision`，不触发 HTX 请求。当前单测只证明关键状态机分支，尚未使用生产事故 K 线重放，因此不能声称与 Java 逐笔等价。

P5 已完成：Python 已具备账户隔离的 SQLite 账本 schema、账户/仓位/订单/成交/决策记录、显式初始化入口、纸面成交器、内容寻址运行证据回放加载器，以及“主账户先成交才允许镜像账户复制”的纸面运行时。纸面运行时已经把开仓、持仓状态、Fibonacci v4 减仓/平仓、Donchian/EMA 兼容 ATR 退出和镜像减仓/平仓串成闭环。SQLite schema 仅由操作员显式调用 `TrendLedger.initialize` 创建，运行时绝不自动执行 SQL。

P5 的生产执行合同现已补齐，但尚未部署：`HtxAccountFactsAdapter` 使用与 Java 相同的账户手续费、合约保证金、持仓、开放订单及五档盘口事实；手续费接口失败时明确标记 `CONFIGURED_FALLBACK`，不伪装为账户实际费率。`HtxTrendEntryExecutor` 固定执行 Java 顺序：杠杆预检 → 结构生命周期 claim → 仓位 intent → 订单 intent → 市价单 → 交易所订单回填；重启后只查询已持久化 intent，不会重复发送。Python 不再把 `strategy_risk_reservation` 作为 Java 没有的拒单 Gate。镜像执行器仅接收已确认的主账户成交量，按增量开仓和 `reduce_only` 减仓。

P6 的代码与验证工具已完成：提供有界并发的公开行情扫描器、异步运行证据归档、冻结 HTX 数据集回放、结构化 Java/Python 决策差异比较、显式账本初始化命令，以及双重授权的真实执行适配器。实际冻结数据已验证 BTC/ETH/XAUT 各 2,000 根 15m K 线均能无前视地完成 1,936 个评估时点，且三条模型都有候选；这证明数据与模型链路可运行，不构成盈利或与 Java 逐笔等价的结论。

**Python 的组合候选开仓合同已对齐，完整策略迁移尚未完成，真实切换尚未发生。** 已用跨语言金样本覆盖完成 K 线信号、组合候选、资金费、时效、追价、成本、张数和市价单参数；Python 还复用 Java 的运行开关、币种清单、窗口、并发、市场广度/锚点及仓位状态口径。Java legacy 非组合开仓路径没有在 Python 重写，因此 Python Live worker 在 `HTX_TREND_COMBINATION_ENTRY_ENABLED=false` 时拒绝启动，避免同配置下运行不同策略。退出引擎的逐笔回放仍未完成，不能据此切换真实执行。当前不部署、不执行生产 SQL、不改变 Java 的真实执行权。

## 不可变约束

- Python 真实开仓与平仓默认关闭，不能因部署自动变为 live。
- 每次策略决策必须保留输入 K 线、因子、模型、Gate、价格计划与退出证据。
- Java 与 Python 不得同时对同一账户、同一策略身份发送订单。
- 任何切换都先过冻结 K 线回放、纸面执行和单账号小仓灰度；不以单日盈利替代验证。

## 2026-10-08：Java 实盘开仓合同对齐完成

本轮不改变 Java 服务、生产数据库或交易开关，只补全 Python 已有真实执行适配器的行为合同并通过单测。对齐范围如下：

| 环节 | Python 对齐结果 |
|---|---|
| 候选路由 | 从同一张 `htx_trend_strategy_policy` 读取启用政策；精确匹配 `primary_model + confirmation_models`，以**原始模型分数**过阈值；市场状态只影响排序与倍率。 |
| 点差、资金费与流动性 | 使用 HTX 五档盘口、方向可成交深度、实时资金费、已收盘 K 线成交额；严格模式缺少任一账户费率、深度、保证金事实即拒绝。 |
| 手续费、保证金、杠杆与仓位 | 读取账户实际 taker 费率和 `margin_available`；按 Java 默认 `5x`、`1.20` 保证金安全系数与 `20U` 最低可用保证金；政策额度先乘 Java `1/1.25/1.5` 评分倍率，再叠加候选/市场自适应倍率，并受策略与账户上限约束。 |
| 下单价格与订单方式 | 当前 Java 实盘趋势路径为 V5 `market`，Python 因此默认同样使用市价；多头以 ask、空头以 bid 作为追价、最差成交价、扣费后预期 R 与合约张数的事实价格。限价探路仅保留为显式实验开关。 |
| 订单、成交、对账与镜像 | 维持 `杠杆 → 结构 claim → position intent → order intent → HTX → 回填` 顺序；仅 `state=filled` 或 legacy `status=6` 的真实成交才激活仓位和写账本。重启只查询既有 intent；镜像仅复制主账户确认成交后的增量，并以 `reduce_only` 跟随确认减仓。 |

Python 现已具备 `LIVE` worker 连接，但只会在 `strategy_runtime_instance.execution_mode=LIVE`、绑定 PRIMARY 账户且 Java 同源开关 `HTX_TREND_ENABLED=true`、`HTX_TREND_OBSERVE_ONLY=false`、`HTX_TREND_ENTRY_ENABLED=true`、`HTX_TREND_COMBINATION_ENTRY_ENABLED=true` 都满足时启动。镜像账户只由主账户确认成交回调触发。默认配置仍是禁用/观察，因此本次“完成”只表示组合候选开仓合同和运行时连接完成，**不表示已部署、已开启或已实盘验证**。
