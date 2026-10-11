---
title: HTX Python 策略铁律调用链 v1
status: implemented-framework-research-incomplete
created: 2026-10-11
updated: 2026-10-11
scope: htx-trend-python
---

# HTX Python 策略铁律调用链 v1

## 结论

Python 趋势策略现在以 `HtxStrategyDecisionEngine` 作为唯一候选生成入口。冻结数据回放、运行时证据回放、OBSERVE、PAPER 和 LIVE 均调用同一 `java_entry_contract` 纯计算合同；旧 `models.evaluate_signal/model_candidates` 只保留为历史研究函数，不再被运行链或正式回放调用。

交易事实继续由 HTX 定义：公共 K 线、合约面值与最小张数、盘口、账户费率、资金费率、保证金、订单状态和逐笔成交不能由策略推测或用默认值伪装。

## 新版本准入链

```text
StrategyCard
  -> FrozenDataManifest(COMPLETE + sha256)
  -> DataQualityAudit
  -> FeatureDictionary
  -> SignalStateMachine
  -> PositionRisk
  -> CostExecutionAssumptions
  -> SameSourceBacktest
  -> AblationSensitivity
  -> OutOfSampleRobustness
  -> PaperExecution
  -> ResearchReport
  -> sealed StrategyAdmissionArtifact
```

`StrategyAdmissionArtifact` 固定策略版本、实验编号、源码提交、policy/config/dataset hash、12 阶段证据引用、失败条件、暂停条件和允许作用面。持久化产物必须带自身 `artifact_hash`；任一字段被改写后加载失败。

| 作用面 | 最少通过阶段 |
|---|---|
| OBSERVE | 1–5 |
| BACKTEST | 1–7；随后才能运行阶段 8 |
| PAPER | 1–10；PAPER 结果形成阶段 11 证据 |
| RANKING / GATE / SIZING / EXIT | 1–12 |

现有 `HTX_TREND_V1` 是兼容基线。任何其他 Python `strategy_name` 都必须通过 `HTX_TREND_STRATEGY_ADMISSION_PATH` 指向匹配且已封印的准入产物，否则 worker 启动或作用面调用失败关闭。

## 最终运行调用情况

### OBSERVE

```text
worker.main
  -> _load_admission(strategy_name)
  -> FileEvidenceStore
  -> TrendObservationWorker.tick
  -> HTX raw response + completed K-line window evidence
  -> HtxStrategyDecisionEngine.decide(OBSERVE)
  -> java_entry_contract.evaluate
  -> java_entry_contract.candidates
  -> condition-level DecisionTrace
  -> market breadth/context
  -> policy route observation
  -> signal/factor/candidate evidence
```

### 冻结数据与运行证据回放

```text
dataset-replay
  -> COMPLETE manifest
  -> requested input exists exactly once
  -> raw file sha256 matches
  -> timestamp order/duplicate/OHLC/volume/continuity audit
  -> HtxStrategyDecisionEngine.decide(BACKTEST)
  -> candidate counts and content hashes

replay
  -> immutable COMPLETE KlineWindow
  -> HtxStrategyDecisionEngine.decide(BACKTEST)
  -> htx-trend-python-same-source-replay-v3
```

### PAPER

```text
PaperTrendRuntime.scan
  -> HtxStrategyDecisionEngine.decide(PAPER)
  -> route_candidates
  -> evaluate_gate
  -> risk_per_trade / stop_distance sizing
  -> PaperExchange intent/fill
  -> ledger
  -> v4 paper exit
```

### LIVE 兼容链

```text
LiveTrendWorker.tick
  -> writer ownership
  -> reconcile pending orders
  -> v4 position management and hard-risk exits
  -> HTX completed K-line window
  -> evidence failure blocks new risk
  -> HtxStrategyDecisionEngine.decide(RANKING)
  -> policy selection
  -> HtxTrendEntryExecutor.submit
       -> admission RANKING/GATE/SIZING (new versions)
       -> account/order/position/fee/funding/book facts
       -> evaluate_gate
       -> ordered PASS/FAIL/SKIPPED entry_gate_trace
       -> risk-first sizing (new versions)
       -> leverage
       -> structure claim
       -> position/order intent
       -> HTX order submission
       -> exchange reconciliation
       -> confirmed-fill ledger projection
```

`HTX_TREND_V1` 继续使用 Java policy 名义金额 sizing，以保持现有生产兼容。通过 12 阶段准入的新 Python 策略版本改为 `min(by_risk, by_target, by_max)`，先受单笔风险约束。

### V5 退出

```text
observe_v5(OBSERVE/PAPER)
  -> matching HTX_TREND_EXIT_V5 admission
  -> OBSERVE requires stages 1–5
  -> PAPER requires stages 1–10
  -> decide_v5 pure kernel
  -> observation result
```

V5 仍拒绝 LIVE。`exit_experiment_v5` 的成功状态改为 `STRUCTURALLY_COMPLETE`，只表示执行事实字段完整，不能再被解释为策略已准入。

## 命令

```bash
htx-trend validate-admission --artifact /path/admission.json
htx-trend dataset-replay --data-dir /path/frozen --manifest /path/manifest.json --contract BTC-USDT
htx-trend replay --evidence /path/evidence --output /path/report.json
htx-trend collect-history --evidence /path/cas --symbols BTC-USDT ETH-USDT --start 2026-04-11T00:00:00Z --end 2026-10-11T00:00:00Z
htx-trend collect-tick --evidence /path/cas --symbols BTC-USDT ETH-USDT
htx-trend evidence-entry-replay --evidence /path/cas --manifest-hash <sha256> --symbol BTC-USDT
```

本次只实现本地代码和验证合同；未生成虚假的 PASS 证据，未执行 SQL，未部署，未交易，也未把 V5 接入 LIVE。

图示见 [`diagrams/htx-python-strategy-iron-law-call-flow-v1.drawio`](diagrams/htx-python-strategy-iron-law-call-flow-v1.drawio)。
