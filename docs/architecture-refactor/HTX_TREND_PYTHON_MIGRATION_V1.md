---
title: "HTX 趋势策略 Python 迁移计划 v1"
type: execution-plan
status: active
created: 2026-10-08
tags: [htx, trend, python, migration, execution]
---

# HTX 趋势策略 Python 迁移计划 v1

## 目标与边界

将当前已部署的 `codex/htx-model-routing@dd2e1417` 中**HTX USDT 合约趋势策略**迁移到独立 Python 运行时。迁移目标是建立可回放、可纸面验证、可与 Java 逐笔比对的 Python 实现，最终再决定切换真实执行。

不迁移：移动网格、现货做市、资金费套利、Polymarket、Web 页面、Java 通用客户端、无关数据库表。Java 服务在 Python 回放、纸面和灰度验收完成前继续是唯一真实交易执行者。

## SDK 决定

已核验 HTX 官方仓库 [`huobiapi/huobi_Python`](https://github.com/huobiapi/huobi_Python)，基线 commit `8a96761845302daf585010784150779da5edc206`。它以现货 V3 为主，合约仅保留旧 `/linear-swap-api/v1/swap_cross_order`，没有当前 Java 趋势系统所需的 V5 仓位、委托、杠杆、撤单和 `reduce_only` 完整接口。

因此 Python 工程不把旧 SDK 当作真实执行依赖，而是实现窄范围 `HtxV5Client`：保留 HTX HMAC-SHA256 规范、当前已验证的 V5 端点和 typed 请求模型。该 client 默认禁止所有变更请求；真实执行必须经后续灰度显式开启。

## 迁移顺序

| 阶段 | 迁移内容 | 验收 | 实盘边界 |
|---|---|---|---|
| P0 | Python 工程、官方 SDK 适用性结论、V5 client、密钥与默认禁交易 | 请求签名、端点、`reduce_only`、禁交易单测 | 不部署、不下单 |
| P1 | K 线、盘口、资金费、合约精度、实时价格输入和运行 K 线归档 | 与 Java 同时点输入逐字段一致 | 只读 |
| P2 | EMA/ATR/ADX/ROC、Donchian、Fibonacci、市场广度、多周期状态 | 冻结 K 线上的因子和候选逐项对齐 | 回放/纸面 |
| P3 | 候选排序、模型专属 Gate、冷却解冻、价格计划与仓位 sizing | 同一 `run_id` 的开仓/拒绝原因对齐 | 纸面 |
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

P3 已完成第一部分：Python 已实现模型专属的开仓前检、候选过期检查、点差/可选成交额/资金费成本 Gate、基于实际费率与预估滑点的净 R、不同模型的建议订单方式及价格区间、风险和名义金额双重约束的张数 sizing。亏损冷却默认严格，只有极强的新结构可提前解冻；盈利/保本冷却可由强信号提前解冻，并生成可记录的原因。所有输出仍是 `GateResult`，没有下单权。

P4 已完成第一部分：Python 已增加 `fib-exit-engine-v4` 的纯决策实现，包含原有状态阶段、模型/初始止损优先级、持仓趋势状态、持久化武装的利润保护、MFE 成本地板、保护价穿越确认、趋势衰竭、动态剩余风险敞口、资金费成本退出及未武装亏损预算。它仍只输出 `ExitDecision`，不触发 HTX 请求。当前单测只证明关键状态机分支，尚未使用生产事故 K 线重放，因此不能声称与 Java 逐笔等价。

下一步进入 P5：迁移订单、成交、仓位、账户与镜像账本；构建冻结数据回放和纸面执行器，在同一输入上生成 Java/Python 的差异报告；在此之前不连接生产密钥、不启动 Python 服务、更不改变 Java 的真实执行权。

## 不可变约束

- Python 真实开仓与平仓默认关闭，不能因部署自动变为 live。
- 每次策略决策必须保留输入 K 线、因子、模型、Gate、价格计划与退出证据。
- Java 与 Python 不得同时对同一账户、同一策略身份发送订单。
- 任何切换都先过冻结 K 线回放、纸面执行和单账号小仓灰度；不以单日盈利替代验证。
