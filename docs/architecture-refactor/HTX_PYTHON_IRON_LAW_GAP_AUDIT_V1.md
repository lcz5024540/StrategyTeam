# HTX Python 策略铁律符合度与冻结数据闭环审计 V1

> 2026-10-11 更新：本文件记录实施前差距，运行冻结断点已由 [`HTX_PYTHON_RESEARCH_FRAMEWORK_IMPLEMENTATION_20261011.md`](HTX_PYTHON_RESEARCH_FRAMEWORK_IMPLEMENTATION_20261011.md) 修复。十二阶段仍因完整生命周期回测和至少14天 PAPER 缺失而保持未准入。

> 审计日期：2026-10-11  
> 审计基线：`support_htx_python@db458da3`  
> 规范基线：`support_htx/docs/architecture-refactor/COINSTRATEGY_STRATEGY_DEVELOPMENT_IRON_LAW_V1.md`  
> 范围：只检查 Python 策略、研究工具和既有数据契约；不改 Java、SQL、策略参数或交易行为。

## 1. 结论

当前 Python 策略**具备较完整的模块化骨架，但尚未形成符合铁律的运行证据闭环**。

最关键的事实是：`RuntimeKlineArchive` 已实现，`PaperTrendScanner` 也会调用它；但是实际启动入口 `python -m htx_trend.worker` 创建的 `TrendObservationWorker`、`LiveTrendWorker` 和 `LivePositionManager` 都没有接收或调用该 archive。因此，当前直接运行 Python OBSERVE 或 LIVE 时，策略用于计算的 K 线不会由 Python 自动冻结。

这使当前状态呈现三层差异：

| 层次 | 结论 | 含义 |
|---|---|---|
| 模块设计 | **大部分已存在** | 决策核、准入、冻结文件、数据集校验、回放、PAPER、MySQL 交易账本均已拆分。 |
| 运行接线 | **未闭环** | 正式 worker 没有接入 K 线冻结；anchor、退出和完整执行上下文也未形成同一证据包。 |
| 铁律准入 | **未通过** | 当前 `HTX_TREND_V1` 通过兼容分支绕过 admission；仓库内没有它对应的 12 阶段 sealed admission artifact。 |

因此不能把“模块已经写好”解释成“当前策略已经符合铁律”。按铁律的二元判定，当前 V1 没有可验证的 12 阶段准入产物，正式符合度是 **未准入**。若只衡量工程骨架，12 个阶段均能找到代码或文档起点，但其中 **1 项接近完整、10 项仍是部分完成、1 项缺正式产物**；也就是 **11/12 个阶段仍需补证据或补运行闭环**。

## 2. 审计口径

本报告把三种状态严格分开：

- **模块存在**：有独立类、工具或数据契约。
- **运行生效**：`worker.py` 的 OBSERVE/LIVE 主调用链实际调用该模块。
- **铁律通过**：有冻结输入、hash、实验与结果证据，并由 sealed `StrategyAdmissionArtifact` 验证。

铁律是准入规则，不采用“代码完成百分比”替代通过结论。下面的数量用于表达剩余工作，不代表允许上线。

## 3. 十二阶段差距矩阵

| 阶段 | 当前能力 | 运行/证据状态 | 结论 | 主要缺口 |
|---|---|---|---|---|
| 1 策略卡片 | 运行说明、策略总结和版本字段已存在 | 没有当前 V1 的机器可校验策略卡片及 hash | 部分 | 固化品种、周期、方向、杠杆、假设、失效场景和版本 |
| 2 数据冻结 | `evidence.py`、运行 K 线 SQL、DB 导出与 freeze 工具存在 | Python OBSERVE/LIVE 未调用 archive；只保存标准化 K 线，不保存 HTX 原始响应 | 部分 | 接入所有决策路径，保存 raw + normalized + 请求元数据 |
| 3 数据质量 | `datasets.py` 检查 hash、顺序、重复、连续性、OHLC、负成交量 | runtime archive 不生成完整质量报告；未记录缺口详情、跳价和异常成交量 | 部分 | 在线标记 + 批次质量报告 + fail-closed promotion gate |
| 4 特征字典 | 当前运行说明记录公式和用途 | 没有机器可校验、版本化、带滞后/缺失/准入面的 feature dictionary | 部分 | 独立 artifact 与 config/source hash 绑定 |
| 5 信号状态机 | 共用 `HtxStrategyDecisionEngine`，候选、policy、Gate、订单生命周期已拆分 | 决策输入/config/输出有 hash，状态边界较清楚 | 接近完整 | 将市场上下文和退出输入也纳入同一重放合同 |
| 6 仓位与风险 | 新版本支持风险优先 sizing；组合预算与预占模块存在 | V1 保留名义金额兼容；缺冻结后的风险参数与账户事实全量重放 | 部分 | 版本化风险合同和逐笔可复算证据 |
| 7 成本与执行 | 真实 book/funding/合约规格在 LIVE 读取，PAPER 有手续费 | PAPER 同根 K 线成交过于理想；缺无成本/乐观/基准/保守四场景闭环 | 部分 | next-bar、spread、slippage、funding、partial fill、expiry/requote |
| 8 同源回测 | `dataset-replay` 复用共同决策核 | 只统计 signal/candidate；不重放完整 Gate、sizing、entry、exit、fill 与资金曲线 | 部分 | 全生命周期 backtest ledger、equity、drawdown、MFE/MAE |
| 9 消融与敏感性 | 有 indicator/entry regime/expected-R 等工具 | 结果未封装为当前策略版本的一套完整 sealed experiment ledger | 部分 | 单变量矩阵、同成本口径、完整执行结果绑定 |
| 10 样本外与稳健性 | 有 research report、policy gate、部分 OOS 工具与报告 | 当前 V1 没有可验证的一揽子 train/validation/test/walk-forward 准入证据 | 部分 | 固定切分、walk-forward、四成本、regime 分层 |
| 11 PAPER | `PaperTrendRuntime`、`PaperExchange`、`PaperTrendScanner` 存在 | `worker.py` 不注册 PAPER；fill 模型不具备生产执行真实性 | 部分 | 可运维 PAPER worker、成交回传、错失率、退出复盘 |
| 12 研究报告与准入 | admission 类型、阶段门和 artifact hash 校验已实现 | 没找到当前策略的 sealed artifact；V1 明确绕过 authorization | 缺失 | 真实证据文件、12 阶段 PASS、允许作用面和上线审批 |

量化表达：

- **正式准入：0/1**。没有 sealed 12 阶段 artifact，当前策略不能声明通过铁律。
- **阶段闭环：1 接近完整、10 部分、1 缺失**。11 个阶段仍有未闭合项。
- **需要优先修复的工程断点：6 个 P0，4 个 P1**，见第 8 节。

## 4. 运行期 K 线是否冻结

### 4.1 现有模块

`htx-trend-python/src/htx_trend/evidence.py` 提供异步、内容寻址的本地归档：

1. 每根标准化 Candle 按 `content_hash` 写入 `candles/<hash>.json`；
2. 每个计算窗口写入 `snapshots/<hash>.json`；
3. snapshot 保存 stage、contract、period、target bar、窗口范围、预期/实际数量、window hash、run/factor/position 关联；
4. 写入采用临时文件替换，避免半文件。

这部分模块边界是合理的，能够去重相同 Candle，也能把一次计算窗口关联到 run 或 position。

### 4.2 真实接线

当前只有 `PaperTrendScanner._scan_one()` 调用：

```text
ENTRY_SCAN          -> archive.capture(entry 15m window)
TREND_CONFIRMATION  -> archive.capture(trend 60m window)
```

正式入口的实际情况：

| 入口/阶段 | 是否冻结 | 证据 |
|---|---|---|
| `worker.py` → OBSERVE entry scan | 否 | `TrendObservationWorker` 只读取 entry window、计算并写 signal/factor |
| `worker.py` → LIVE entry scan | 否 | `LiveTrendWorker` 保存 observation/candidate，但未调用 archive |
| LIVE breadth | 否 | 从数据库读取历史统计，没有把本次所见 universe 固化到决策包 |
| LIVE BTC/ETH anchor | 否 | 运行时另取多周期 K 线，未归档这些窗口 |
| LIVE entry gate/book/funding/account facts | 部分 | 结果散落在 factor、candidate、order、position 记录，未形成单一不可变输入包 |
| LIVE position management/exit | 否 | `LivePositionManager` 读取退出窗口，但没有 archive 依赖 |
| 独立 `PaperTrendScanner` | 是，部分 | 只冻结 entry/trend 标准化 K 线；它不是 `worker.py` 可启动的 PAPER 模式 |

直接回答：**如果现在运行当前 Python 策略的标准 worker，运行期间的 K 线不会由 Python 自动冻结保存。** 已有冻结模块只在独立 PAPER scanner 中生效。

### 4.3 冻结内容与铁律要求的差距

| 铁律要求 | 当前本地 archive | 差距 |
|---|---|---|
| HTX 原始响应 | 无 | `HtxMarketData.kline_window` 先转为 Candle，原始 envelope 丢失 |
| 标准化 OHLCV | 有 | 已满足基本字段，含 amount/volume/turnover/trade_count |
| exchange/market type/contract size | 不完整 | snapshot 固定 `HTX_PUBLIC_RUNTIME`，没有 market type 和 contract size |
| 时间范围/时区 | 部分 | 保存 epoch 范围，没有显式 timezone/闭合边界语义 |
| 请求参数/分页 | 无 | 没有 size、请求时间、分页边界、HTTP/HTX 状态 |
| 内容 hash | 有 | Candle 与 window hash 已有 |
| manifest/数据版本 | 无运行批次 manifest | DB 离线 freeze 工具有 manifest，但 Python worker 不产源数据 |
| 失败/缺口/重复/倒序/异常 | 部分 | 只有 complete/partial 和计数；没有逐项异常清单 |

### 4.4 可靠性与完整性风险

`RuntimeKlineArchive` 明确采用 fail-open：队列满时丢弃，写失败只增加内存计数，策略继续运行。这适合观测 telemetry，但不足以直接充当正式研究数据源。

`replay.load_snapshots()` 读取文件时也没有重新校验：

- Candle 文件内容是否真的匹配文件名 hash；
- 重新计算的 window hash 是否匹配 snapshot；
- snapshot 自身是否有 hash 或 manifest；
- 所有 expected bars 是否连续且完整；
- snapshot 引用是否来自同一 strategy/config/policy/source commit。

因此，当前本地 archive 可以辅助排查，但还不能单独证明“这就是当时决策所用的完整、未篡改输入”。

## 5. 是否模块化保存

答案是：**已经模块化拆分，但保存模型仍是两套半链路，尚未统一。**

| 层 | 模块 | 已保存内容 | 当前断点 |
|---|---|---|---|
| 行情采集 | `market_data.py` | 内存中的标准化 `KlineWindow` | raw response 未保留 |
| 运行证据 | `evidence.py` | 本地 Candle CAS + window snapshot | 未接 OBSERVE/LIVE/exit；fail-open |
| 数据集 | `datasets.py` | 校验外部 COMPLETE manifest 和 HTX envelope | 不负责从当前 Python runtime 生成 batch |
| 回放 | `replay.py` / `validation.py` | snapshot callback、决策摘要 | 未做完整 integrity 与全生命周期复现 |
| 决策 | `strategy_decision.py` | input/config/decision hash | 未包含 breadth/anchor/book/account/exit 全上下文 |
| 准入 | `strategy_admission.py` | 12 阶段证据 hash 与作用面 | 当前 V1 绕过；无真实 sealed artifact |
| 执行账本 | `mysql_store.py` / `ledger.py` | signal、factor、candidate、order、position、fill、exit decision | 与 K 线快照没有由 Python 统一提交/绑定 |
| 研究工具 | `htx-trend/tools/*` | freeze、quality、ablation、OOS、report、archive | 多为离线工具，未被 runtime 自动编排 |

已有 SQL 中存在 `htx_trend_runtime_kline_candle` 和 `htx_trend_runtime_kline_snapshot`，Java 侧也有 recorder。Python 的 MySQL repository 没有对应写入方法，`validate_runtime_schema()` 也没有要求这两张表。由于本项目限定只改 Python，后续可以复用现有表契约，在 Python 增加 repository adapter，而不需要改变 Java 策略。

## 6. 回测为什么还不能完整复现当前运行

目前有两种 replay：

1. `datasets.py` 从 COMPLETE manifest 读取冻结 K 线，调用共同 entry decision core；
2. `replay.py` 从本地 snapshot 重建 `KlineWindow`，交给 callback。

它们都没有覆盖完整实盘调用所需的上下文：

- 市场 breadth 的原始 universe 与截面时间；
- BTC/ETH anchor 的 15m/30m/60m/4h 输入；
- policy 版本、排序结果及当时启用状态；
- top-of-book、spread、funding、contract spec；
- 账户权益、可用保证金、已有仓位、风险预占；
- Gate 每一项输入和判定；
- entry intent、交易所回执、partial fill、reconcile；
- 持仓期间 15m 和多周期退出窗口、v4 state、MFE/MAE、费用；
- 完整交易账本、资金曲线、回撤、月度收益和成本场景。

所以当前 replay 可以回答“相同 K 线经过 entry core 会产生什么 signal/candidate”，还不能回答“当时为什么最终下单/未下单、以什么数量成交、之后为什么退出、净收益是否可复现”。

## 7. 当前 PAPER 的差距

`PaperExchange` 的成交假设很简化：

- MARKET 直接按传入 Candle 的 close 成交；
- LIMIT 只要价格落在同一根 Candle 的 high-low 内就全量成交；
- 不模拟 next-bar、盘口价差、滑点、排队优先级、部分成交、超时、撤单或重报价；
- funding 和市场冲击没有进入 fill；
- `worker.py` 没有 PAPER 分支，无法按正式 supervisor 生命周期持续运行。

这套实现适合确定性单元测试，不足以完成铁律第 11 阶段的外部模拟执行验收。

## 8. Python-only 修复顺序

### P0：先让一次真实决策可冻结、可验证、可重放

1. **统一采集契约**：新增 raw response + normalized window 的 `MarketEvidenceBundle`，包含 exchange、market type、contract spec、period、request size、request/capture time、闭合边界和 source commit。
2. **接入正式 worker**：把 evidence sink 注入 `TrendObservationWorker`、`LiveTrendWorker`、`LivePositionManager`，覆盖 ENTRY_SCAN、TREND_CONFIRMATION、MARKET_ANCHOR、ENTRY_GATE、POSITION_MANAGEMENT、EXIT_DECISION。
3. **持久化适配器**：定义 `EvidenceStore` protocol，提供本地 CAS 与现有 MySQL 两种实现；生产默认写 MySQL，本地研究写文件，两者使用相同 canonical hash。
4. **批次 manifest 与质量报告**：按 run/bar/position 生成不可变 manifest，列出缺口、重复、倒序、OHLC、负量、异常量和跳价；任何 PARTIAL 只能用于诊断，禁止进入正式指标。
5. **严格 replay verifier**：加载时重算 candle/window/snapshot/manifest hash，校验连续性和引用闭包；缺任何上下文直接报错。
6. **补 EXIT admission gate**：把 admission 传入 `LivePositionManager`，新策略在退出决策前显式 `require(EXIT)`；V1 保持兼容，不改变当前行为。

### P1：把研究和 PAPER 变成完整生命周期

7. 建立 `ReplayContext`，冻结 breadth、anchors、policy、config、book、funding、account/risk facts 和 contract spec。
8. 扩展同源 backtest，复用 entry/gate/sizing/exit 合同，输出 trade ledger、equity、drawdown、MFE/MAE、月度收益和四成本场景。
9. 给 `worker.py` 增加受 admission 约束的 PAPER mode，使用 next-bar 与可配置执行模型，持续记录订单生命周期和错失率。
10. 为新策略版本生成真实 strategy card、feature dictionary、experiment ledger、OOS/report，并最终封印 `StrategyAdmissionArtifact`。

建议拆成四个可独立验收的里程碑：

| 里程碑 | 范围 | 验收结果 |
|---|---|---|
| M1 Runtime Evidence | P0.1–P0.4 | OBSERVE/LIVE/exit 每个决策都有完整 manifest；丢失可观测 |
| M2 Deterministic Replay | P0.5–P0.6 + P1.7 | 选定 run 可从冻结输入复算所有 decision hash |
| M3 Same-source Backtest/PAPER | P1.8–P1.9 | 输出完整账本和四成本；PAPER 可长期运行 |
| M4 Admission Closure | P1.10 | 新策略 12 阶段真实 PASS；按 surface 准入 |

## 9. 目标调用逻辑

目标链路应以一次“不可变决策包”为中心：

```text
HTX response
  -> RawCapture
  -> Normalizer / Closed-bar filter
  -> Candle CAS + WindowSnapshot
  -> ContextSnapshot
       [breadth, anchors, policy, config, book, funding,
        contract spec, account facts, risk reservations]
  -> DecisionEngine
  -> Gate / Sizing / Entry or Exit
  -> Order / Fill / Position events
  -> DecisionBundle manifest + hashes
  -> QualityAudit
       COMPLETE -> replay/backtest/PAPER evidence
       PARTIAL  -> diagnosis only, never formal metrics
  -> 12-stage admission artifact
  -> allowed surface
```

对应 draw.io：[`diagrams/htx-python-iron-law-gap-audit-v1.drawio`](diagrams/htx-python-iron-law-gap-audit-v1.drawio)。图中红色虚线表示当前断点，绿色实线表示已有并接通的调用，蓝色表示目标闭环。

## 10. 当前可安全使用的边界

- 可以继续用当前 Python 模块做 OBSERVE、单元测试和局部 entry decision replay。
- 可以把现有 runtime freeze、quality、ablation、research report 工具作为后续闭环的组成部分。
- 不能把本地 archive 的存在当成当前 OBSERVE/LIVE 已冻结。
- 不能用仓库中 `PARTIAL` manifest 产出正式 PF、胜率或参数定标。
- 不能把 `HTX_TREND_V1` 的兼容 admission bypass 表述为通过铁律。
- 新的 entry、exit、sizing 或指标版本在 M4 完成前不能进入 LIVE 决策面。

## 11. 审计依据

- `htx-trend-python/src/htx_trend/worker.py`
- `htx-trend-python/src/htx_trend/strategy_platform.py`
- `htx-trend-python/src/htx_trend/live_worker.py`
- `htx-trend-python/src/htx_trend/position_manager.py`
- `htx-trend-python/src/htx_trend/market_data.py`
- `htx-trend-python/src/htx_trend/evidence.py`
- `htx-trend-python/src/htx_trend/replay.py`
- `htx-trend-python/src/htx_trend/datasets.py`
- `htx-trend-python/src/htx_trend/strategy_decision.py`
- `htx-trend-python/src/htx_trend/strategy_admission.py`
- `htx-trend-python/src/htx_trend/paper.py`
- `htx-trend/tools/htx_trend_runtime_kline_freeze.py`
- `htx-trend/tools/htx_trend_runtime_evidence_archive.py`
- `htx-trend/backtest-runs/20260901-21-mtf-profitability/dataset-manifest.json`
- `sql/htx/2026-09-30b__htx_trend_runtime_kline_evidence.sql`
