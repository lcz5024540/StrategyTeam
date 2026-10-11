---
title: CoinStrategy 趋势退出系统技术设计 v5
type: technical-design
status: implementation-in-progress
created: 2026-10-09
author: Codex / Ray
tags: [htx, trend, exits, java, python]
---

# CoinStrategy 趋势退出系统技术设计 v5

**设计结论：以入场模型的持仓合同管理仓位；硬风险实时执行，软退出由入场后的完整 K 线推进；达到经成本与波动共同约束的盈利阶段后，才启用利润管理。** 单独 FLAT、成本刚覆盖、盘中一次软线穿越均不构成全平条件。

| 项 | 内容 |
|---|---|
| 文档类型 | 技术设计，交易规则和系统实现合同 |
| 状态 | M1 输入审计与 M2-M4 L0 纯核已交付；M5 持久化、迁移、回放结果和上线未交付 |
| 授权 | 用户选择 B 全面重设计并要求入仓；不等同批准本文候选参数实盘运行 |
| 作者 / 日期 | Codex / Ray；2026-10-09，Asia/Shanghai |
| 目标读者 | 策略负责人、Java/Python 开发、执行与账本负责人、测试人员 |
| 前置文档 | [ADR：全面重设计](../docs/design-docs/adr-20261009-redesign-trend-exits.md) |
| 事实基线 | [短持仓证据](../docs/architecture-refactor/evidence/HTX_TREND_SHORT_HOLDING_20261009.md)；源码 `support_htx_python@0f07a492` |
| 配套图示 | 图 11-1 架构、11-2 决策、11-3 执行泳道、11-4 发布；§11.1 有文件链接 |
| 目标里程碑 | 实施按 §10 顺序；当前完成无 I/O 的 Java/Python 合同与 golden，发布日期未承诺 |
| 成功标准 | 扣费后表现与风险共同改善；不能用更长持仓或更少订单单独判定成功 |

## 1. 背景

### 1.1 业务背景

HTX 趋势策略已使用组合模型产生候选，Java 管理实际仓位。Python 分支已有 v4 退出与运行平台，但此前只读检查未发现部署的 Python worker unit。本设计约束两种实现，不授予第二个实盘执行者权限。

近期 LTC、DOGE、BTC 等订单持有几分钟便平仓；其中 LTC/DOGE 的线上决策证据指向“成本保护已武装 + 基础信号 FLAT + 实时越线”，没有完整 K 线越线确认或独立反转证据。策略需要统一生命周期，而非只抬高一个利润金额。

### 1.2 现状与差距

| 编号 | 已观察事实 / 差距 | 设计对应 |
|---|---|---|
| G-1 | 组合入场与基础信号持仓判定不同 | 冻结主模型合同，模型适配器持续评估 |
| G-2 | 小额浮盈触发成本线，数十秒回吐即可全平 | 显著盈利阶段、双门槛、关闭隐式成本止损 |
| G-3 | WEAKENING 实时分支先于两根确认返回 | 唯一优先级表，软动作统一经过成熟/确认检查 |
| G-4 | reason=TREND_EXHAUSTION 与证据不足并存 | 原因、证据组、拒绝理由逐项输出 |
| G-5 | 镜像 ACCEPTED 缺成交事实，收益口径差异 | 执行与策略状态分离，逐笔成交核算 |
| G-6 | 同一结构重新产生候选可能重复付费 | 结构身份、再入场确认和累计预算 |
| G-7 | 版本迁移可能混用旧状态 | 入场绑定版本，未平仓禁止无合同热切 |

### 1.3 本次交付范围

覆盖趋势持仓从入场合同落库、持有、止损、利润管理、减仓、全平、对账到再入场。模型范围为现有 Fibonacci、Donchian、EMA/VWAP 三类；只有完成各自合同和验收的模型可进入 v5 LIVE。套利与现货网格的收益逻辑不套用趋势退出规则。

本轮产出为设计文档、ADR、事故证据与验收合同；未来实现变更见 §10。保留历史 v1–v4 文档和代码用于审计，本设计成为新开发目标，不重写历史事实。

### 1.4 商业性思考

| 编号 | 维度 | 判断 |
|---|---|---|
| BIZ-G-1 | 目标 | 减少无充分证据的短周期开平，保留有效趋势及必要止损 |
| BIZ-C-1 | 成本 | 研发需覆盖双语言、账本、回放和上线接管；工时待实施排期，不能编造 ROI |
| BIZ-C-2 | 收益 | 以净收益、尾部损失、资金占用、手续费负担联合衡量；未验证前不承诺提升 |
| BIZ-M-1 | 替代 | 比较现有 v4、局部修改与 v5；不引入新交易所或外购执行平台 |
| BIZ-R-1 | 风险 | 确认延迟可能扩大回吐，不能以减少手续费掩盖更大的交易亏损 |

## 2. 需求与约束

### 2.1 功能需求

| 编号 | 需求 | 可执行验收 |
|---|---|---|
| FR-1 | EntryThesis 冻结入场理由、初始风险与版本 | 开仓意图无合同拒绝；修改旧合同哈希被拒绝 |
| FR-2 | 结构评估与动量评估分离 | FLAT 单独输入不改变 thesis=VALID 为 INVALID |
| FR-3 | 双时钟，硬风险始终有效 | 未满一根 K 线仍可硬止损；重复 tick 不增加收盘确认数 |
| FR-4 | 盈利阶段只由合格 post-entry bar 推进 | 盘中小额浮盈不激活；同事件激活不退出 |
| FR-5 | 软线突破需要收盘与独立证据 | 0 根确认或不足证据时输出 HOLD/EXIT_WATCH |
| FR-6 | 合法减仓，保留有效 runner | 不满足最小张数/名义额时不偷偷全平 |
| FR-7 | 退出执行幂等及重启恢复 | 重试、超时、双进程竞争不增加目标平仓张数 |
| FR-8 | 再入场控制 | 同一失效结构、缺新确认或耗尽预算均拒绝 |
| FR-9 | 逐笔费用与镜像独立核算 | 未知成交不记零收益，不以主账户均价替代镜像 |
| FR-10 | Java/Python 同输入同决策 | golden fixtures 全字段一致，包括不动作的理由 |
| FR-11 | 观测、纸面、实盘权限隔离 | OBSERVE/PAPER 不可调用真实下单 SPI |
| FR-12 | 每次 HOLD 和 EXIT 可解释 | 持仓页面显示主理由、证据、成熟度及执行状态 |

### 2.2 非功能需求

| 编号 | 类别 | 目标 / 验证 |
|---|---|---|
| NF-1 | 确定性 | 同输入、状态、版本、配置哈希输出一致；决策核不读系统时钟/网络/随机数 |
| NF-2 | 一致性 | position epoch + decision key + CAS + execution fencing；重复意图断言为 0 |
| NF-3 | 时效性 | 研究初值：可执行行情年龄≤3s、账户≤10s；通过目标机实测后冻结 |
| NF-4 | 延迟 | 硬风险从有效事件到发送意图 P99 目标≤2s（不含交易所确认），压测验证；不是已达事实 |
| NF-5 | 容灾 | 超时不能判无仓，重启恢复原版本、armed、在途订单和再入场锁 |
| NF-6 | 可观测性 | 每个真实动作有合同、输入摘要、理由、费用来源、订单及成交关联 |
| NF-7 | 隔离 | 单账户执行权唯一；镜像异常不伪造主账户完成或修改其收益 |

### 2.3 架构约束

| 编号 | 约束 | 说明 |
|---|---|---|
| C-1 | 不扩大初始风险 | 已成交仓位不因亏损扩大硬止损距离；实际跳空损失不能保证不超过预算 |
| C-2 | 只管理归属明确的仓位 | 手工/其他策略仓位不接管；net-position 混用账户应先划分可识别所有权 |
| C-3 | reduce-only | 所有退出请求必须限制为减仓；不允许平仓错误变反向开仓 |
| C-4 | 单一决策所有者 | v5 动作裁决后不落回 v4/legacy FLAT/成本夹紧分支 |
| C-5 | 不伪造历史 | 缺历史盘口或成交数据则降为逻辑回放，不能宣称净收益改善 |
| C-6 | 版本不可热混 | policy_hash 与 entry_contract_hash 在仓位生命周期内固定 |
| C-7 | 无硬编码绝对利润目标 | “赚0.2U就保护”不成为通用条件；尺度随张数、成本、波动计算 |

### 2.4 假设与依赖

| 编号 | 假设/依赖 | 不满足时 |
|---|---|---|
| A-1 | 可获取带时间戳的行情、订单、账户和费率 | 禁新入场；软退出停算，保留可验证的硬保护与风险告警 |
| A-2 | 模型能产出稳定结构身份 | 模型仅 OBSERVE，不能通过随机 run_id 绕过再入场限制 |
| A-3 | 交易所原生保护能力经验证 | 未验证前不进入无人值守 v5 LIVE，不能宣称进程故障仍受保护 |
| A-4 | 实际 DB 方言与迁移工具已核验 | §5 DDL 为设计草案，实施阶段先兼容测试，不由应用启动执行 |
| A-5 | 冻结数据覆盖旧退出后的反事实区间 | 不足则实验结果为 INSUFFICIENT_EVIDENCE |

## 3. 总体架构

> 本章配图：[图 11-1 · 架构与事实边界](../docs/design-docs/diagrams/trend-exit-v5-architecture.drawio)。

### 3.1 架构原则

| 编号 | 原则 | 落地 |
|---|---|---|
| P-1 | 入场理由贯穿持仓 | EntryThesis + ModelHoldingAdapter |
| P-2 | 风险与获利使用不同时间尺度 | TickHardRiskEvaluator / ClosedBarEvaluator |
| P-3 | 行情指标与实际盈亏分离 | indicative mid-price 仅显示；退出评估使用方向盘口和真实费率 |
| P-4 | 先决定目标剩余张数，再执行差额 | Decision → Intent → fills → confirmed state |
| P-5 | 先冻结合同，再实验 | 所有候选参数、数据集、切分与门槛预注册 |

### 3.2 逻辑分层

`EvidenceAssembler` 负责时间、质量和归属；`ExitKernelV5` 只计算；`ExitStateStore` 原子保存；`ExitExecutor` 处理真实 I/O；`FillReconciler` 形成实际仓位与收益；`ReentryGuard` 检查新入场。

它们是逻辑模块，不要求新增微服务。先在现有 Java HTX 服务内实现，Python 实现相同合同；避免为了重构拆出网络依赖。

### 3.3 系统上下文

49 机器运行策略和数据库，43 机器运行 Web API/前端。Web 只展示持仓与证据，不在查询请求中驱动退出。Python 与 Java 可以同时观察，相同账户/策略只能一个持有真实交易执行权。数据源见图 11-1。

### 3.4 运行时视图总览

| 编号 | 数据流 | 结果 |
|---|---|---|
| DF-1 | 入场候选 → 合同 → 执行意图 → 实际成交 | 建立初始数量、成交价、费用、归属与硬保护 |
| DF-2 | tick/风险事实 → 硬风险核 | HOLD 或硬退出；不推进软确认数 |
| DF-3 | closed-bar → 模型/利润核 | HOLD、观察、更新软线、减仓、全平 |
| DF-4 | 决策 → 持久化意图 → 交易所 → fills | 实际张数收敛，不将 ACCEPTED 当 FILLED |
| DF-5 | 完成/失效事件 → 结构锁 → 新候选 | 允许、等待重新确认或拒绝再入场 |

## 4. 模块详细设计

> 本章配图：[图 11-2 · 决策流程与唯一动作仲裁](../docs/design-docs/diagrams/trend-exit-v5-decision.drawio)。

### 4.1 模块划分及旧行为处置

| 模块 | 职责 | 旧行为如何处理 |
|---|---|---|
| EntryThesisFactory | 冻结模型理由、结构、风险、持仓周期 | 禁止缺合同时猜测模型 |
| ModelHoldingAdapter | 返回 VALID / DETERIORATING / INVALID / UNKNOWN | 不复用 FLAT→WEAKENING 作为模型失效 |
| HardRiskEvaluator | 初始止损、保证金、显式损失上限 | 不受软成熟期/冷却影响 |
| ProfitStageEvaluator | 计算成熟度、显著利润、软线候选 | NET_POSITIVE_MFE_COST_FLOOR 不再自动武装 |
| ExitArbiter | 按固定优先级产出唯一目标剩余量 | 禁止多个 if 分支分别直接下单 |
| ExitIntentExecutor | 幂等、围栏、reduce-only、确认 | v4/legacy 退出路径仅处理自己版本仓位 |
| ReentryGuard | 结构重置与累计风险/成本预算 | run_id/进程重启不能成为新结构 |

### 4.2 EntryThesis 入场持仓合同

合同在发送 ENTRY 前持久化，使用候选时可知事实；成交后追加 `entry_fill_revision`，保留候选与成交两个版本。不得事后按盈利路径重新选择 swing 或结构目标。

| 字段组 | 必需字段 | 语义 |
|---|---|---|
| 身份 | account_id、strategy_instance、position_epoch、model_id、structure_id | 结构身份跨运行稳定；不能只用 run_id |
| 版本 | contract_schema、engine_version、policy_hash、model_adapter_version | 一个仓位一个版本 |
| 时间 | decision_time、entry_bar_open/end、timeframe_sec、first_fill_time | 软成熟计数从实际入场后开始 |
| 模型 | frozen_anchors、support_zone、failure_zone、target_zone、confirmation_sources | 结构区域和独立来源有精确定义 |
| 风险 | immutable_initial_stop、initial_risk_usdt、approved_notional、max_loss_budget | 初始止损与 sizing 一致 |
| 成交 | weighted_entry_price、filled_qty、contract_size、entry_fees | 只能由 fills 更新 |
| 盈利 | volatility_reference、cost_policy、partial_policy、horizon_policy | 全部进入 policy_hash |

结构身份建议由 `model + symbol + timeframe + side + anchor bar IDs + zone version` 的规范 JSON 哈希生成。价格取交易所 tick 网格，按方向舍入；相同锚点小数噪声不能产生新身份。

### 4.3 模型持仓合同

初始硬止损采用开仓前风险线；以下软失效边界更靠近结构且必须明确区分。若模型不能给出不同的软边界，软模型失效功能关闭，仅保留硬止损与经验证的其他退出。

| 模型 | VALID | 独立结构失败证据 E-S | 再确认 |
|---|---|---|---|
| Fibonacci | 入场 swing 锚点有效，回踩区域未被确认破坏 | 多单收盘低于冻结回踩区下沿，空单高于上沿；连续合格 bar 确认；硬 swing 止损仍实时 | 新形成且已确认的 swing/回踩结构，或预注册的同结构重新站回并确认；禁止仅重复同候选 |
| Donchian | 突破后收盘维持在突破区域，或允许范围内回测成功 | 连续收盘回到冻结突破通道内部并越过 failure_zone | 新的收盘突破事件和重新通过 entry gate，锚点必须可解释 |
| EMA/VWAP | 入场方向的已收盘排列和冻结交易区间有效 | 连续收盘越过入场定义的 EMA/VWAP 失败带；EMA、VWAP 同组只计一次 | 收盘重新建立排列且回踩/延续条件通过，不凭单次交叉 |

EMA/VWAP 必须冻结 VWAP 锚定方式与重置时间；24×7 市场不能隐式使用机器本地自然日。未指定模型锚定口径时该适配器不允许 v5 LIVE。

### 4.4 三种互相独立的状态

| 维度 | 状态 | 说明 |
|---|---|---|
| 模型有效性 | VALID / DETERIORATING / INVALID / UNKNOWN | 描述开仓假设；FLAT 只作诊断字段 |
| 利润阶段 | BUILDING / ESTABLISHED / HARVEST / RUNNER / CLOSED | 收盘推进，已达到的阶段不因瞬时价格回落倒退 |
| 执行状态 | IDLE / INTENT / SENT / PARTIAL / CONFIRMED / UNKNOWN | 描述订单，不等于策略阶段 |

`EXIT_WATCH` 是待确认退出候选标记，不是一个能跳过成熟期的利润阶段。`DATA_DEGRADED` 是证据质量标记；不将 UNKNOWN 自动等同 INVALID。

### 4.5 双时钟、成熟期与证据独立性

合格软评估 bar 必须满足 `bar.open_time >= last_entry_fill_time`、`bar.end_time + close_grace <= event_time`。首轮禁止加仓，后续若支持加仓必须新建 epoch 或重新定义成熟期，不能复用旧利润状态。

只有新的 `(symbol,timeframe,bar_end)` 能推进一次软状态；乱序 bar 保存审计但不倒退状态。入场所在半根 K 线不计成熟、不用于峰值激活。高周期证据必须是当时已收盘且未过期的 bar。

| 证据组 | 示例 | 计数规则 |
|---|---|---|
| E-S 结构 | 冻结回踩/通道/VWAP 失败区被收盘破坏 | 模型最直接证据，必须在入场后确认 |
| E-H 高周期 | 一个指定高周期收盘反向且达到预注册强度 | 同一高周期的 EMA、MACD 不算两个证据 |
| E-B 市场 | BTC/ETH 锚点与市场广度的联合反向状态 | 锚点与广度合为一组，避免相关性重复计数 |
| E-F 延续失败 | 在合格 bars 内无新有效推进且多次收盘退回失败区 | 必须与 E-S 的同一判定去重，不能一件事计两票 |
| 诊断项 | FLAT、单个 ADX 下降、盘中 wick、原始信号缺方向 | 不单独计入反转票数 |

独立性是数据来源分组约束，不声称统计独立。保存每组 source_id、bar_end、predicate_id；同源、同谓词的重复 evidence_id 去重。

研究初值 `maturity_bars=2`、软保护 `confirmation_bars=2`；只是候选配置。成熟期只约束软退出，任何硬风险仍立即生效。

### 4.6 成本、波动与利润阶段

本版公式限定 USDT 线性合约；反向合约必须独立单位适配，不能直接复用。

```text
s = +1 (LONG), -1 (SHORT); Q = remaining_contracts × contract_size
Pexec = 买盘可执行价(平多) / 卖盘可执行价(平空)，含深度估算
Cexit = estimated_close_fee + residual_slippage_reserve
Nrem = s × (Pexec - Pentry) × Q - allocated_entry_fee - Cexit
       + allocated_net_funding
Crt = allocated_entry_fee + estimated_close_fee + residual_slippage_reserve
Dcost = Crt / Q
Darm = max(k_arm × ATRentry, k_cost × Dcost)
Arm = mature && favorable_completed_close_move >= Darm
      && Nrem_at_completed_close >= k_net × Crt
```

`Pexec` 已包含盘口/深度价差，不再重复扣一遍 spread；residual_slippage_reserve 仅覆盖未包含的延迟与冲击。资金费按实际归属记账；未来资金费预测单列成本风险，不混进已经实现的利润。

`ATRentry` 固定为入场可见的已收盘 ATR；软线噪音宽度使用 `max(ATRentry, ATRclosed_current)`，防止波动收缩让保护线突然贴价。所有金额使用 decimal，不用浮点二进制直接比较阈值。

| 阶段 | 进入条件 | 允许行为 |
|---|---|---|
| BUILDING | 成交确认，尚未满足 Arm | 硬止损、结构观察；成熟后的明确模型失效/时间失效可退出 |
| ESTABLISHED | Arm 在一个合格 bar 收盘成立 | 激活软保护，自下一个合格 bar 起生效 |
| HARVEST | 已到冻结目标区且存在独立的延续衰减证据，经济条件通过 | 一次部分减仓；仍有效则继续持有 |
| RUNNER | 部分减仓已成交并且剩余张数可管理 | 收盘结构/ATR 软跟踪；硬保护继续独立 |
| CLOSED | 已核验真实剩余量为零且在途退出单收敛 | 完结收益与再入场状态 |

不要求所有仓位按此顺序经历 HARVEST；可以 ESTABLISHED 持续运行直到确认全平。`1R/2R` 作为报告坐标，不能因初始止损宽导致必须跑到数倍 ATR 才首次进入利润管理。

### 4.7 软保护线及动作门槛

软线基于入场后合格完整 bar 的最高有利收盘价 `BestClose`、已确认结构和噪音宽度计算；不以入场前 high/low 或盘中最大价激活。

```text
LONG:  trail = BestClose - k_noise × max(ATRentry, ATRclosed_current)
SHORT: trail = BestClose + k_noise × max(ATRentry, ATRclosed_current)
若存在已确认的新结构保护线：按持仓方向选择更紧候选；
候选距离最新收盘价不足 noise_min 时，延后更新而非强夹紧；
有效软线只向有利方向移动，但永远不替代初始硬止损。
```

成本保本价仅展示。若软线投影净收益仍不为正，记录 `PROTECTION_NOT_NET_POSITIVE`，可保留观察线；不得以“保利润”名义自动全平。明确模型失效仍可亏损退出，原因记录为 MODEL_INVALIDATION。

对新 bar 先检查上一事件生效的软线；再计算新线，下一个合格 bar 生效。越线确认连续、去重、可重置：收盘恢复线内则确认数归零；软线更新不把旧 bar 用新线重新计数。确认期间冻结该候选的 boundary_id，避免移动目标导致自触发。

### 4.8 唯一退出优先级

所有规则先生成候选再仲裁，最终最多一个动作；后续执行器不得追加隐式退出判断。

| 优先级 | 条件 | 动作 | 成熟/确认要求 |
|---|---|---|---|
| R-0 | 数据/归属不确定 | DATA_DEGRADED + 禁新仓，进入对账 | 不对未知持仓盲下平仓单；已验证硬保护继续有效 |
| R-1 | 有效价格触及初始硬止损或明确风险预算边界 | FULL_CLOSE / 交易所保护单触发 | 无软成熟期；数量必须可归属 |
| R-2 | 达到预注册的保证金/灾难回撤硬边界 | FULL_CLOSE | 无软确认等待；与费用保本线分开 |
| R-3 | 模型 E-S 失效确认 | FULL_CLOSE，MODEL_INVALIDATION | 合格 bars 达确认要求；无需盈利 |
| R-4 | 已武装软线收盘确认 + E-H/E-B 等一组不重复失败证据 | FULL_CLOSE，CONFIRMED_TREND_FAILURE | 成熟且确认成立；不能仅 FLAT |
| R-5 | 目标到达 + 一组延续衰减证据 + 减仓经济条件 | PARTIAL_CLOSE，STRUCTURE_HARVEST | 合格收盘；每个目标阶段一次 |
| R-6 | 预定义持仓期限届满且预期延续未发生 | FULL_CLOSE，THESIS_TIMEOUT | 按模型期限，不按全局几分钟；硬止损不延迟 |
| R-7 | 预测资金费预算超限且数据有效 | PARTIAL_CLOSE/FULL_CLOSE，CARRY_BUDGET | 使用独立预算和同一目标量仲裁；不伪称利润保护 |
| R-8 | 仅软线越线、仅 FLAT、证据不足 | HOLD + EXIT_WATCH | 不发送订单 |
| R-9 | 无退出候选 | HOLD / UPDATE_SOFT_BOUNDARY | 记录保持持仓的理由 |

R-0 不是“有缺失就停止一切”：若只有高周期指标缺失但实时价/归属/数量可靠，R-1/R-2 仍执行。若私有账户查询失败，使用已验证的交易所原生保护并告警，不把失败解释为仓位为零。

灾难回撤线为 ESTABLISHED 之后另行验证的高幅度损失控制，初始研究不启用；启用前定义 `catastrophic_drawdown_atr` 和最大可承受权益回吐，不得复用成本线或 0.15ATR 微小回吐。需要单独对比其尾部风险作用。

### 4.9 减仓、单张与剩余风险

首轮仅一次目标减仓，候选比例 1/3；向下取整到合法张数，余量必须≥最小张数及名义额。更多分段在验证前不引入。

`partial_net = qty_fraction × price_gain - allocated_entry_fee - estimated_exit_cost + allocated_funding`；必须为正且满足预注册成本倍数。曾经达到峰值不代表当前能以峰值成交，执行前用新鲜盘口重验。

| 场景 | 动作 |
|---|---|
| 1 张不可拆 | 普通目标止盈 HOLD；等待独立全平条件，不自动替换为 FULL_CLOSE |
| 部分成交 | 累积唯一 fill，重新计算剩余量；未完成目标不提前标记 RUNNER |
| 可用张数小于意图量 | 先对账归属，按实际可减量夹紧；拒绝反向开仓 |
| 粉尘余量 | 只有执行合同允许且有明确 dust 清理理由才处理；记录实际额外成本 |
| 手续费使部分止盈无意义 | HOLD 或按独立失效规则退出，不追逐名义盈利 |

分摊按成交数量比例进行，最后一笔吸收舍入尾差；保证所有分段开仓费之和等于实际开仓费。总权益包含已实现分段与剩余浮动，不因减仓重置最大不利/有利轨迹。

### 4.10 再入场约束

退出后锁的作用域是 `account + strategy family + symbol + side + structure_id`，累计风险预算另按 `account + symbol + side + time window` 约束，防止换结构 ID 绕过预算。

| 上次退出类型 | 重新入场所需证据 | 禁止做法 |
|---|---|---|
| 硬止损/结构失效 | 新结构或预注册的完整重建、合格收盘确认、风险预算未耗尽 | 原来那条候选再次到达队首就重开 |
| 正常趋势全平 | 新结构或新延续触发，重新通过全部 entry gate | 自动反手、只等固定秒数 |
| 目标减仓 | 仍视为同一仓位，不新增 ENTRY | 用“补回目标仓位”恢复刚减掉数量 |
| UNKNOWN/在途退出 | 先收敛成交与实际仓位 | 认为 DB CLOSED 就可再开 |

同结构重入默认关闭；若实验开启，必须同时满足重新确认与 `same_structure_attempts<=1` 的研究上限，跨重启持续保存。按结构累计实际损失+实际费用达到入场冻结预算则禁入，正收益不无限补充该预算。

### 4.11 参数登记与失败关闭

以下是 **R0 研究起点**，不是已优化参数，也不写入生产 env。所有运行必须引用完整且签名/审批留痕的 policy_hash，缺字段拒绝新仓；历史仓位继续自己合同。

| 参数 | R0 候选 | 实验目的 |
|---|---:|---|
| maturity_bars | 2 | 避免开仓前信号立即终止持仓 |
| structure_failure_confirm_bars | 2 | 模型软失效确认 |
| soft_breach_confirm_bars | 2 | 软线确认；不控制硬止损 |
| arm_atr_multiplier | 1.0 | 形成有效价格推进 |
| arm_cost_distance_multiple | 2.0 | 避免成本附近反复管理 |
| arm_net_cost_multiple | 2.0 | 净收益储备，成本独立约束 |
| noise_atr_multiplier / noise_min_atr | 1.0 / 0.5 | 容纳正常回测；值待验证 |
| partial_fraction / max_partial_stages | 1/3 / 1 | 限制交易碎片化 |
| partial_net_cost_multiple | 2.0 | 部分动作需具备经济意义 |
| no_extension_horizon_bars | 16（15m 候选） | 入场后四小时未形成确认延续的假设超时 |
| same_structure_reentry_enabled | false | 禁旧结构重复开平 |
| catastrophic_trail_enabled | false | 单独实验，不混入基线 |

期限的“延续”定义为入场后合格收盘达到 `Darm`，不能用一根盘中 wick 重置。达到一次后该 no-extension 期限不再应用；最长资金占用期限另由模型合同指定，不能无限延期或自动续期。

## 5. 数据模型

> 本章配图：[图 11-1 · 架构与事实边界](../docs/design-docs/diagrams/trend-exit-v5-architecture.drawio)，展示新增合同/状态与已有成交账本的关联。

### 5.1 逻辑模型

沿用 `htx_trend_position/order/exit_decision` 与统一 ledger 的真实成交事实。增加不可变入场合同、v5 状态、持久化动作意图、结构锁；不在既有 position 上继续堆叠无版本解释的布尔字段。

现有 exit_decision.evidence_json 记录 schema=v5 的完整输入/输出摘要与 reason_codes。已有字段无法表达者放版本化 JSON；不截断关键证据。未来增长到独立事实存储时通过 evidence_hash 引用，不本轮引入新中间件。

### 5.2 物理模型（设计 DDL，未执行）

以下 SQL 定义目标结构，不是部署迁移脚本。实施时按实际 MySQL 版本生成可重复执行的 migration；先检查已存在结构是否完全一致，不用 IF NOT EXISTS 隐藏漂移。TEXT 内 JSON 由应用 schema 验证，保留旧 MySQL 兼容路径。时间统一 UTC datetime(3)，UI 转 Asia/Shanghai。

```sql
CREATE TABLE htx_trend_entry_contract (
  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  position_id BIGINT NOT NULL,
  position_epoch BIGINT NOT NULL,
  account_id BIGINT NOT NULL,
  strategy_instance VARCHAR(64) NOT NULL,
  engine_version VARCHAR(32) NOT NULL,
  policy_hash CHAR(64) NOT NULL,
  contract_hash CHAR(64) NOT NULL,
  structure_id CHAR(64) NOT NULL,
  contract_json MEDIUMTEXT NOT NULL,
  created_at DATETIME(3) NOT NULL,
  UNIQUE KEY uk_position_epoch (position_id, position_epoch),
  KEY idx_structure (account_id, structure_id)
);
```

```sql
CREATE TABLE htx_trend_exit_state_v5 (
  position_id BIGINT NOT NULL PRIMARY KEY,
  position_epoch BIGINT NOT NULL,
  revision BIGINT NOT NULL,
  contract_id BIGINT NOT NULL,
  owner_token BIGINT NOT NULL,
  last_bar_end BIGINT NULL,
  last_event_key VARCHAR(160) NOT NULL,
  phase VARCHAR(24) NOT NULL,
  execution_state VARCHAR(24) NOT NULL,
  confirmed_remaining_qty DECIMAL(36,18) NOT NULL,
  state_json MEDIUMTEXT NOT NULL,
  updated_at DATETIME(3) NOT NULL,
  KEY idx_phase (phase, updated_at)
);
```

```sql
CREATE TABLE htx_trend_exit_intent_v5 (
  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  decision_key VARCHAR(160) NOT NULL,
  position_id BIGINT NOT NULL,
  position_epoch BIGINT NOT NULL,
  state_revision BIGINT NOT NULL,
  owner_token BIGINT NOT NULL,
  client_order_id VARCHAR(64) NOT NULL,
  target_remaining_qty DECIMAL(36,18) NOT NULL,
  status VARCHAR(24) NOT NULL,
  payload_json MEDIUMTEXT NOT NULL,
  created_at DATETIME(3) NOT NULL,
  updated_at DATETIME(3) NOT NULL,
  UNIQUE KEY uk_decision (decision_key),
  UNIQUE KEY uk_client_order (client_order_id),
  KEY idx_pending (status, updated_at),
  KEY idx_position (position_id, position_epoch)
);
```

```sql
CREATE TABLE htx_trend_reentry_guard_v5 (
  guard_key CHAR(64) NOT NULL PRIMARY KEY,
  account_id BIGINT NOT NULL,
  strategy_family VARCHAR(32) NOT NULL,
  contract_code VARCHAR(32) NOT NULL,
  side VARCHAR(8) NOT NULL,
  structure_id CHAR(64) NOT NULL,
  revision BIGINT NOT NULL,
  exit_reason VARCHAR(64) NOT NULL,
  status VARCHAR(24) NOT NULL,
  evidence_json MEDIUMTEXT NOT NULL,
  updated_at DATETIME(3) NOT NULL,
  KEY idx_scope (account_id, contract_code, side, status)
);
```

EntryThesis 与初始 position INTENT 同事务分配 ID 并落库，网络发送在提交之后；不存在“先成交，再补写无来源合同”的窗口。跨表一致性在事务中保证，历史表不强加会破坏既有数据的外键。

执行权租约需要 account/strategy scope、单调 owner_token、lease_until、renewed_at；优先复用并验证现有 strategy platform/lease 能力。若现有 schema 缺字段，实施评审补充独立 migration，不把上面四张表声称为全部执行权基础设施。

### 5.3 状态与事件字典

| 名称 | 保存内容 | 推进条件 |
|---|---|---|
| state_json | last_eligible_bar、mature_count、armed_bar、boundary_id、active/pending_boundary、breach_count、partial_stage | 完整事件去重与 CAS 成功 |
| execution_json | target、confirmed_fills、pending_qty、order_ids、retry_deadline、query_result | 真实交易所事实 |
| evidence_json | model groups、fee source、price source、freshness、reject reasons、input_hash | 每次可复算决策 |
| ReentryGuard | last_exit、anchor lineage、attempts、realized loss/fees、reset evidence | 已确认退出后更新；未知则锁住 |
| FILL_CONFIRMED | exchange、account、order_id、trade_id、qty、price、fee、timestamp | 唯一 trade_id 幂等 |

主仓与镜像使用不同 account_id、合同、执行权和成交状态。共享方向信号不共享价格、费用、数量或利润保护武装事实。

## 6. 接口设计

> 本章配图：[图 11-3 · 执行与确认泳道](../docs/design-docs/diagrams/trend-exit-v5-execution.drawio)。

### 6.1 对外 API 契约表

下面为 **拟新增/扩展接口**，不是当前可调用服务。

| 编号 | 接口 | 合同 |
|---|---|---|
| API-1 | GET `/strategy/api/htx-trend/positions/{id}/exit-v5` | account授权过滤；返回版本、模型、软硬线、阶段、确认进度、最新执行状态 |
| API-2 | GET `/strategy/api/htx-trend/positions/{id}/exit-decisions?cursor=...&limit=...` | 默认50、最大200；游标按id，不返回全历史超大JSON |
| API-3 | 现有 htx-trend dashboard 扩展 | 显示 `accountingComplete`、收益口径、缺成交数量、当前持仓原因 |

不存在由 GET 触发重算/平仓的副作用。首轮不新增公网“点击全平”接口。执行权限管理通过既有受控运维入口并记录审计。

API-1 的成功响应必含 `positionId/accountLabel/engineVersion/policyHash/asOf/dataQuality`、`modelState/phase/executionState`、`hardStop/activeSoftBoundary/pendingSoftBoundary`、`eligibleBars/requiredBars/breachBars/requiredBreachBars`、`remainingQty/pendingExitQty`、`reasonCodes/evidenceGroups/accountingComplete`。不适用的软线为null，不能用0表示。

API-2 返回 `items/nextCursor/asOf`；每项包含 `decisionKey/eventTime/eventKind/action/targetRemainingQty/reasonCodes/inputHash/executedAction/orderStatus`。游标绑定账户范围与排序末尾ID，未知/过期游标返回400，不自动改成第一页。API-1不存在或无查看权限统一404；未认证401；存储暂时不可用503并带requestId。错误不回传数据库、密钥或原始私有响应。端点沿用现有鉴权和响应包装，实施阶段对齐Controller约定后生成OpenAPI。

### 6.2 事件契约

无需新增 Kafka。数据库中的 intent 即可靠待执行工作项。以下为 JSON schema 逻辑形状；实现需正式 schema、最大字段长度与向后兼容测试。

```json
{
  "schema": "trend-exit-v5",
  "decisionKey": "position:epoch:event:policy-hash",
  "eventKind": "CLOSED_BAR",
  "positionEpoch": 1,
  "inputHash": "sha256",
  "policyHash": "sha256",
  "modelState": "VALID",
  "phase": "BUILDING",
  "action": "HOLD",
  "reasonCodes": ["SOFT_MATURITY_NOT_REACHED"],
  "evidenceGroups": [],
  "targetRemainingQty": "78",
  "priceSource": "BOOK_EXECUTABLE",
  "accountingComplete": true
}
```

schema 验证拒绝未知动作枚举；字段扩展允许时必须保持相同旧字段含义。金额字符串为规范 decimal；时间 epoch 毫秒 UTC；数量以合约步长归一化。

### 6.3 内部 SPI 与跨语言一致性

决策动作枚举固定为 `HOLD / UPDATE_SOFT_BOUNDARY / PARTIAL_CLOSE / FULL_CLOSE / RECONCILE_REQUIRED`。`ARM_TRAILING` 是v4历史值；v5激活以状态推进加 `UPDATE_SOFT_BOUNDARY` 表达，不能下单。`EXIT_WATCH / DATA_DEGRADED` 为标记或理由，不扩充订单动作。只有 PARTIAL_CLOSE/FULL_CLOSE 可创建执行意图。

目标量满足 `0 <= target_remaining_qty <= confirmed_remaining_qty`；FULL_CLOSE目标为0，HOLD保持当前确认量。pending_qty 单独计算，dispatcher 查询在途订单后发送差额，不能再次将全部 confirmed_remaining_qty 当可发量。数量归一化后违反边界则输出RECONCILE_REQUIRED并拒绝发送。

```text
ModelHoldingAdapter.assess(entryThesis, closedBars, context) -> ModelEvidence
ExitKernelV5.decide(contract, priorState, evidenceEvent, policy) -> Decision
ExitStateStore.compareAndAppend(expectedRevision, decision, intent?) -> revision
ExitExecutor.reconcileAndDispatch(intent, ownerToken) -> ExecutionObservation
FillReconciler.applyUniqueFill(fill, expectedPositionEpoch) -> ConfirmedState
ReentryGuard.evaluate(candidate, priorStructureHistory, budget) -> GateResult
```

Java BigDecimal 与 Python Decimal：乘除保留18位小数，内部比较不先显示舍入；价格最终按 tick_size、数量按 step_size 方向化舍入。阈值相等含义统一采用 `>=`；soft breach 使用方向性 `close <= line`（多）/ `close >= line`（空）。每个规则的等号边界必须有 fixture。

hash 规范：UTF-8、键排序、decimal无指数表示、时间整数、显式 null 与缺字段区分。Java/Python 同一规范化序列再哈希；不是各自 serializer 随意输出后比较。

## 7. 运行时视图

> 本章配图：[图 11-2 · 决策](../docs/design-docs/diagrams/trend-exit-v5-decision.drawio)、[图 11-3 · 执行泳道](../docs/design-docs/diagrams/trend-exit-v5-execution.drawio)。

### 7.1 正常持仓与动作执行

1. 读取版本合同、实际成交仓位、执行权 token、最后已消费事件。
2. 硬风险路径持续接收有效事实；closed-bar 到达时去重，评估上一有效软线，再推进下一根生效的新线。
3. 事务内 CAS 更新状态、写 decision、可选写 intent；冲突方重读，不能继续发送旧结果。
4. dispatcher 查询真实仓位与已有 client_order_id，计算目标剩余量差额；持有效 token 才发送 reduce-only。
5. 超时查询同一 client_order_id，唯一 fills 更新数量和费用；未收敛不推进到“已全平”。

### 7.2 重启、并发与更高优先级风险

普通软决策每个 position epoch 最多一个非终态 intent。新硬风险覆盖“目标剩余量”，不与旧减仓意图并行盲发。先查询/撤销或确认旧单，扣除已成交与可执行的在途量，再发送剩余差额。

围栏不只存在 DB：dispatcher 在真正网络发送前复验租约；失去权限的 worker 不再发送。HTX 不接受内部 owner_token，无法单靠 DB token 阻止已离开进程的旧请求，因此接管必须包含在途窗口隔离、确定性 client_order_id 查询和账户级单发送者约束。

若无法确认旧请求状态，持仓进入 UNKNOWN；停止新的软订单，保留已确认的交易所风险保护。确需紧急 reduce-only 全平时使用专门硬风险接管规则，并将既有在途量纳入上限，不用随机新 order ID 无限重试。

### 7.3 交易所原生保护

原生止损承担进程中断/网络故障时的最后防线；其 API、触发价类型、reduce-only、撤改单原子性、与仓位模式兼容性均为上线前验证项。当前项目文档曾记录未接入，本文不声称已具备。

替换保护单采用经验证的交易所机制；若“先撤旧再下新”存在裸奔窗口且无替代保障，禁止更换。部分减仓后同步保护数量，不能因为残留保护单在空仓后再开仓而误伤新 epoch。

### 7.4 异常与容灾矩阵

| 异常 | 决策/执行 | 恢复依据 |
|---|---|---|
| 未收盘/重复/乱序 bar | 不增加成熟或确认数 | event_key、last_bar_end |
| 基础信号 FLAT、结构有效 | HOLD | EntryThesis + ModelEvidence |
| 缺盘口但有有效原生硬保护 | 禁新入场/软动作；风险告警 | 行情恢复后重算，不补发过期软意图 |
| 私有查询超时 | UNKNOWN，不判空仓 | 同订单ID查询和仓位对账 |
| 主账户已成交、镜像拒绝 | 两账户分别结算，镜像告警 | 各自 fills，禁止伪造跟随成功 |
| 服务重启在 SENT 后 | 不生成新ID | 已持久化意图查单恢复 |
| 交易所仓位与本地不一致 | 冻结新增、按归属对账 | 实际仓位/订单/交易流水 |
| DDL/新schema缺失 | v5 新入场失败关闭 | 旧版本持仓管理继续可用 |
| 软退出放大利润回吐 | 实验/观测记录风险 | 达硬风险阈值仍执行；不临时放宽止损 |

## 8. 非功能设计

### 8.1 性能与可用性

每个 bar 共享模型输入快照，避免每笔持仓重复拉相同 K 线；风险轮询与候选扫描使用独立预算，长时间候选扫描不能占住唯一锁而阻塞硬风险。实施需测 NF-3/NF-4，而不是沿用默认10秒轮询就声称满足2秒。

执行限流按账户与 API 预算处理。风险订单优先但不忽略 rate limit；提前接入原生保护，避免以加速重试代替容灾。

### 8.2 一致性与存储

事务只包含数据库读写，不持锁等待交易所响应。以 CAS revision 与唯一 decision/client/fill keys 处理至少一次投递；不宣称跨 MySQL 与交易所 exactly-once。订单发送的不可判定窗口由查询和风险保护兜底。

### 8.3 可观测性

| 指标 | 分组 | 用途 |
|---|---|---|
| holding_duration、sub_bar_soft_exit_rate | version/model/account | 发现快速软退出；硬止损单独统计 |
| net_pnl、gross_pnl、fees、funding、slippage | 同上+regime | 检查降费是否被更大亏损抵消 |
| MFE_capture、MAE、giveback | 全仓生命周期/分段 | 分析延长持仓的代价 |
| soft_breach_unconfirmed、FLAT_only_hold | rule/reason | 验证不存在提前返回捷径 |
| duplicate_intent、unknown_order_age、owner_conflict | account/instance | 执行安全门 |
| same_structure_reentry、round_trip_cost | structure lineage | 发现重复开平 |
| input_age、risk_dispatch_latency | source/instance | 事实质量与风险时效 |

页面必须同时显示“为何继续持有”和“什么条件会退出”，区分初始硬止损/软观察线/尚未生效候选线。没有完整费用或镜像成交时显示未知，不用0填补。

### 8.4 安全、容灾与审计

日志不输出 AK/SK、签名 URL、数据库密码。证据记录行情、费用和脱敏账户ID即可。policy_hash、合同、真实动作和人工变更具有来源与时间。数据保留按现有磁盘预算分级；原始执行证据不能在实验结束前被日志轮转清掉。

## 9. 部署架构

> 本章配图：[图 11-4 · 发布、执行权切换与回滚](../docs/design-docs/diagrams/trend-exit-v5-rollout.drawio)。

### 9.1 部署形态

保持49策略/DB、43Web/前端拓扑。第一阶段 v5 作为 OBSERVE 纯决策器读取 v4 同步冻结输入；不允许 OBSERVE 写入真实仓位 armed 状态。纸面状态另命名空间隔离。

### 9.2 环境配置矩阵

| 模式 | 允许写入 | 真实订单 | 新旧持仓 |
|---|---|---|---|
| OBSERVE | 独立观测 decision/state | 禁止 | v4继续拥有已有仓位 |
| PAPER | 仿真成交与独立权益 | 禁止 | 同数据独立资金路径 |
| CANARY | 已批准账户、模型、预算范围 | 仅有效执行权者 | 新仓绑定v5，旧仓仍按原版本 |
| LIVE | 已通过验收合同的明确范围 | 受预算/围栏约束 | 不自动扩大到其他账户模型 |

版本选择配置与 live 权限配置分开；`engine=v5` 不自动获得交易权限。所有实盘变更审计记录批准范围、包哈希、配置哈希、owner token和开始时间。

### 9.3 依赖清单

数据库结构、原生保护能力、执行权租约、逐笔对账、价格/费用来源、跨语言 fixtures、独立实验结果均是依赖。缺一项不能把“设计完成”升级为“实盘可用”。

### 9.4 消费方接入约束

Web按版本展示。Python只能在同一账户旧执行者确认退出、在途请求收敛后取得执行权。镜像按各自余额、张数和费用合同执行；主账户 partial 不直接复制绝对张数给镜像。

### 9.5 上线流程

依图11-4依次完成 schema兼容检查 → 逻辑回放 → 独立经济实验 → OBSERVE → PAPER → 小仓CANARY → 明确范围LIVE。具体晋级门槛见 [验收合同](Trend-Exit-v5-Validation.md)。每一步留下可复查报告，不用连续自动部署替代批准。

### 9.6 回滚方案

触发任何执行安全断言失败，立即冻结新仓，查询订单和真实剩余量，保持有效风险管理者。v5旧仓由兼容v5状态的管理器继续管理或经批准安全退出；空仓、无在途和原生保护残留后才回退新入场选择。绝不把已有利润线和 armed 状态解释成另一版本意义。

## 10. 演进路线

> 本章配图：[图 11-4 · 发布与所有权切换](../docs/design-docs/diagrams/trend-exit-v5-rollout.drawio)。

### 10.1 里程碑分解

每项为单一验收单元，实施按WIP=1推进；以下均为待实施，非本次已完成事项。工作量和日期在实施启动时由负责人填入，不伪造排期。

| 编号 | 单元 / 负责人角色 | 依赖 | 验收 |
|---|---|---|---|
| M1 | 冻结输入与费用/成交对账；数据/账本负责人 | 设计 | 事故与独立数据完整性报告，缺口逐项登记 |
| M2 | EntryThesis、模型适配器；策略/Java | M1 | 三模型合同 fixtures，未知输入失败关闭 |
| M3 | v5纯决策核；Java | M2 | 全部规则、优先级、时间及数量断言 |
| M4 | Python同合同核；Python | M3 | golden全字段一致，不只比较动作 |
| M5 | 状态/意图/再入场持久化；执行/DB | M3 | 并发、崩溃、乱序、超时注入无重复平仓 |
| M6 | 原生保护与镜像对账；执行 | M5 | 原生保护真实接口验证及独立fills |
| M7 | 经济实验；策略研究 | M4/M5及完整数据 | 按预注册留出集报告，无偷看选参 |
| M8 | Web解释与监控；Web/前端 | M5 | 分页、未知收益、软硬线与原因展示 |
| M9 | 观察/纸面；运维/策略 | M6/M7/M8 | 零真实订单权限越界、验收报告 |
| M10 | 小仓及上线评审；用户/策略负责人 | M9 | 批准预算、版本、范围与回滚演练 |

### 10.2 长期方向

在稳定合同上才考虑分模型学习退出、更多分段、动态资金分配。不能用机器学习输出替代硬风险边界或用更复杂评分遮蔽证据不足。

## 11. 附录

### 11.1 图示索引

| 编号 | 图名 | 用途 | 章节引用 | 文件 |
|---|---|---|---|---|
| 图11-1 | 架构与事实边界 | 组件/双机/状态事实 | §3/§5 | [draw.io](../docs/design-docs/diagrams/trend-exit-v5-architecture.drawio) |
| 图11-2 | 唯一决策流程 | 时间、优先级、软硬风险 | §4/§7 | [draw.io](../docs/design-docs/diagrams/trend-exit-v5-decision.drawio) |
| 图11-3 | 执行与确认泳道 | DB/执行器/交易所/账本协作 | §6/§7 | [draw.io](../docs/design-docs/diagrams/trend-exit-v5-execution.drawio) |
| 图11-4 | 发布与所有权切换 | 晋级/回滚/在途隔离 | §9/§10 | [draw.io](../docs/design-docs/diagrams/trend-exit-v5-rollout.drawio) |

### 11.2 术语表

| 术语 | 含义 | 不能混淆 |
|---|---|---|
| EntryThesis | 冻结的入场与持仓假设 | 不是持续改写的最新指标 |
| 成熟期 | 软判断的入场后完整bar证据期 | 不是禁止硬止损的最低持仓期 |
| MFE / MAE | 最大有利/不利轨迹 | 历史峰值不是当前可成交收益 |
| 软保护线 | 需收盘与证据确认的退出候选 | 不是交易所原生止损 |
| owner_token | 单调执行权围栏 | 交易所本身不会理解此内部字段 |
| INITIAL R | 入场冻结风险尺度 | 不是所有模型的固定止盈触发器 |

### 11.3 未决问题与关闭责任

| 编号 | 问题 | 关闭责任 / 截止门 |
|---|---|---|
| OQ-1 | HTX原生保护当前能力、触发价模式与部分仓位兼容 | 执行负责人，M6验收前 |
| OQ-2 | 历史盘口、旧退出之后轨迹是否完整 | 数据负责人，M1与M7前；不足输出证据缺失 |
| OQ-3 | 最终阈值与模型期限 | 策略负责人，M7预注册及独立验证；不得先上线后调 |
| OQ-4 | 单张和最小名义额实际限制 | 执行负责人，M5按交易所元数据固定 |
| OQ-5 | TradeX历史成交与两种收益口径差额 | 账本负责人，M1；不将未知填0 |
| OQ-6 | 实际部署分支与本地基线映射 | Java/运维，M3开工前核包/源码manifest |
| OQ-7 | 现有租约字段能否支持单调围栏与接管隔离 | 执行/DB负责人，M5设计审查前 |
| OQ-8 | EMA/VWAP锚定与重置合同 | 模型负责人，M2；缺失则该模型不启v5 |

### 11.4 参考与设计校验

- [事故证据](../docs/architecture-refactor/evidence/HTX_TREND_SHORT_HOLDING_20261009.md)
- [历史退出设计 v1](../docs/architecture-refactor/HTX_TREND_EXIT_SYSTEM_REFACTOR_PLAN_V1.md)：历史背景，不把其中固定R减仓直接当作v5合同。
- [同源回放合同](../docs/architecture-refactor/HTX_TREND_SAME_SOURCE_REPLAY_CONTRACT_V1.md)：v5保留其证据完整性边界。
- [现有 Java v4 核](../coin-strategy-htx/src/main/java/com/metaverse/coin/strategy/htx/service/HtxTrendExitDecisionEngine.java)
- [现有 Python v4 核](../htx-trend-python/src/htx_trend/exits.py)
- [v5 验证与实验](Trend-Exit-v5-Validation.md)

本设计未使用外部收益结论或未经验证的“最佳参数”。codegraph本会话不可用，已在ADR声明精确符号检索替代及影响分析边界。
