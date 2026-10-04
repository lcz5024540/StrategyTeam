---
title: "HTX 趋势同源执行回放合同 v1"
type: execution-replay-contract
status: active
created: 2026-10-04
tags: [htx, trend, bt-01, bt-02a, replay]
---

# HTX 趋势同源执行回放合同 v1

`BT-01` 的“同源”不是拿 K 线收盘价重新模拟一次交易。它必须同时重放生产信号、组合候选与 Fibonacci v4 退出决策，并冻结当时真正决定能否成交的执行事实。

公共 K 线只够验证候选和市场 Gate；没有下列事实时，回放只能输出 `INSUFFICIENT_EVIDENCE`，不能输出 PF、胜率、净收益、回撤或政策结论：

1. 候选全量集合与稳定 `run_id`，包括被 Gate 拦截的候选；
2. 历史最终 Gate 的结论和来源；
3. 账户有效费率、盘口、深度、保证金、杠杆、挂单与仓位事实；
4. 开仓/平仓订单 ID、实际均价、张数、费用与部分成交；
5. 每轮 v4 退出引擎的全部输入，尤其 MFE、ATR、保护价、信号、高周期证据和资金费观察。

## 输入合同

离线文件为 `htx-trend-frozen-execution-evidence-v1`，固定绑定一个 `datasetKey`。只有同时满足以下条件才允许统计交易结果：

- `inputIntegrity=COMPLETE` 且 `universeComplete=true`；
- 每笔交易都有已通过的历史最终 Gate 与五类预检来源：费率、盘口、保证金、杠杆、仓位状态；
- 开仓与全部平仓均有订单 ID、实际成交价、张数和费用；
- 每笔都有一个或多个完整的 v4 退出输入；
- 同一 `run_id` 在冻结 K 线中能够重新生成同方向、同主模型的生产组合候选。

完整 `BT-02A` 还要求每个候选记录四条由同一 Gate 重放生成的结论：`COMBINATION_ONLY`、当前生产 Gate、当前 Gate 加多周期实验、仅反向大盘风险 Gate。四组都使用完全相同的冻结成交与 v4 退出输入；这样才可以比较候选数、漏单数、扣费后收益、PF、期望和回撤，而不会把 Gate 差异和成交假设混在一起。

合同读取器 `HtxTrendFrozenExecutionEvidence` 对缺失字段 fail-closed；它不会从实时接口或数据库补值。

## 运行

```bash
java -Dloader.main=com.metaverse.coin.strategy.htx.replay.HtxTrendFrozenExecutionReplayCommand \
  -cp coin-strategy-htx/target/coin-strategy-htx-0.0.1-SNAPSHOT.jar \
  org.springframework.boot.loader.PropertiesLauncher \
  --data-dir=htx-trend/research-data/<dataset> \
  --manifest=htx-trend/research-data/<dataset>/dataset-manifest.json \
  --configuration=htx-trend/research-config/<frozen-config>.json \
  --evidence=htx-trend/research-data/<dataset>/execution-evidence.json \
  --output=htx-trend/research-results/<run>/same-source-execution.json
```

结果中的 `COMPLETE` 只代表输入、生产候选和 v4 决策可复现；它仍不能自动改 Dashboard 政策或开启真实交易。`BT-02A` 只有在这份候选全量执行账本存在时，才能按同一成交与退出假设比较 Gate 的漏单率、扣费后收益、PF、期望、回撤、MFE/MAE。
