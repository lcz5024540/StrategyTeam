# HTX Python R4/R5 全链路工程验收

## 1. 验收结论

R4/R5 的工程实现通过验收。验证覆盖从冻结 readiness、基线样本审计、结构条件、共享
生命周期引擎、四成本开发矩阵、五窗口统计到 P7 nominee 的完整调用链。

本结论只证明程序能按冻结合同正确运行。合成数据只用于触发分支和验证不变量，不是策略
收益证据。真实独立数据仍只有 7 天，因此 R4/R5 的研究结论继续是
`INSUFFICIENT_EVIDENCE / DATA_TIME_GATE_NOT_READY`。

## 2. 验收范围

```text
independent_data_readiness
  → 校验冻结 R3 plan / hypothesis / profile / manifest / TEST=NOT_READ
  → 时间覆盖门禁
  → BASE 开发段完整交易数审计
  → 冻结 baseline + 单一结构条件
  → 共享 ProfileLifecycleResearch
  → ZERO / OPTIMISTIC / BASE / CONSERVATIVE
  → TRAIN / VALIDATION；TEST 不读取
  → 账本 / 指标 / candidate diagnostics / condition trace 一致性
  → 五窗口 + 固定种子置换 + block bootstrap
  → ELIGIBLE_FOR_P7 或 REJECT / INSUFFICIENT_EVIDENCE
```

## 3. 验收矩阵

| 范围 | 验收内容 | 结果 |
|---|---|---|
| 时间门禁 | 7/60 天和 7/180 天时不构造生命周期结果、不读取收益 | PASS |
| 基线样本门禁 | 时间通过但开发段基线少于 30 笔时停止，条件生命周期不运行 | PASS |
| 冻结输入 | readiness 必须绑定原 R3 plan、正确 profile、无参数网格条件 | PASS |
| 四成本矩阵 | 两个 variants × 四成本 × TRAIN/VALIDATION 必须恰好 16 行 | PASS |
| TEST 隔离 | 任一生命周期产物只接受 `TEST=NOT_READ` | PASS |
| 账本闭包 | 每行 `metrics.trades` 必须等于 trade ledger 长度 | PASS |
| 候选诊断 | `available=passed+rejected` 且 `rejected=unknown+failed` | PASS |
| 条件证据 | 结构条件必须有 trace；状态只允许 PASS/FAIL/UNKNOWN/WAITING/INVALIDATED/EXPIRED | PASS |
| R4 状态机 | Long/Short、重复突破独立跟踪、1–3 根、过期、失效优先、下一根开盘语义 | PASS |
| R5 多周期 | Long/Short、123 根日线预热、逐日历史前缀、未来 K 线不改变旧结果 | PASS |
| 共享引擎 | 15m/4h 结构条件均由 `ProfileLifecycleResearch` 调用并输出 trace | PASS |
| 统计链 | 五窗口、固定种子 permutation、block bootstrap 可重复 | PASS |
| 晋级分支 | 合成正例产生 nominee；负收益产生 REJECT 且 nominee 为空 | PASS |
| 缺损产物 | 少一个成本/分段结果即抛错，不形成研究结论 | PASS |

## 4. 关键边界

R4 同一根 K 线既穿越冻结失效位又重新收回突破边界时，先判 `INVALIDATED`，不能把已经
触发硬风险边界的结构恢复为候选。连续突破分别保留自己的冻结边界和失效位，随后同一根
K 线可以对多个 pending setup 留痕；共享执行层仍只选择一个候选并保持单
symbol/profile 持仓约束。

R5 每个日线状态使用该日线自身结束时的历史前缀计算。最后三根闭合日线不是用最新指标
反向覆盖过去三天。少于 123 根已闭合日线时返回 `UNKNOWN`，而不是中性或通过。

生命周期产物必须与计划绑定同一 profile、K 线 manifest、市场事实 manifest 和冻结
variants。工程测试也覆盖了完整矩阵缺行、策略亏损和基线样本不足三种 fail-closed 路径。

## 5. 验证证据

```bash
cd htx-trend-python
.venv/bin/pytest -q
.venv/bin/python -m compileall -q src tests
.venv/bin/htx-trend independent-profile-study --help
```

- Python：264 passed；
- R4 真实门禁 evidence：`606c573f5201e17f35ed9590257afa35117946c1a604d6ff5054d89733832734`，52 objects VERIFIED；
- R5 真实门禁 evidence：`eef21df4fd5fc2ef1c23f2ec90fc524300ed1c4ad637895de2b1a68a32d3f1b9`，52 objects VERIFIED；
- compileall、CLI、无隔离 wheel、draw.io XML 和 diff check 通过。

## 6. 剩余工作

工程验收已经结束，但 R4/R5 研究没有结束。每日采集继续积累真实独立数据；15m 达到
60 天、4h 达到 180 天后，先用相同入口审计开发段基线完整交易数。达到至少 30 笔才
允许运行真实单条件生命周期实验。任何真实结果都不能用本验收的合成正例替代。
