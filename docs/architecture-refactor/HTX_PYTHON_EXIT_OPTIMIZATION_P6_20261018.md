# HTX Python R2 P6：固定入场的退出条件配对实验

## 1. 结论

P6 已完成，15m 与 4h 总结论均为 `REJECT`，没有退出条件获得 P7 组合资格。

- 15m 三模型样本充足，但 TRAIN 选出的退出条件均未在 VALIDATION 同时满足正收益、
  PF、保守成本、配对统计和 4/5 时间窗口稳定性。
- 4h Donchian 与 Fibonacci 的验证样本只有 8/7 笔，结论为
  `INSUFFICIENT_EVIDENCE`；EMA/VWAP 的 `no_progress_timeout` 将净收益从
  `+19.9861U` 提高到 `+23.6260U`，但配对置换 `p=0.7306`、bootstrap 增益下界
  `-0.3279U`，且只有 2/5 窗口为正增益，因此仍为 `REJECT`。
- P6 只读取 TRAIN 与 VALIDATION，末尾 TEST 保持 `NOT_READ`。正式 V4、PAPER、LIVE、
  admission、运行配置和订单路径均未修改。

## 2. 固定入场与证据闭包

P6 不重新扫描空仓后可出现的新机会。它直接冻结 P4/P5 BASE 父账本的每笔
`signal_time / entry_time / model / side / quantity`，然后对同一笔持仓分别应用旧退出和
六个单条件退出。新条件只能提前保护或提前平仓；同一时刻命中父账本硬止损、结构目标
或正式退出时，父退出优先并记录 `SKIPPED`。因此所有变体的交易数和入场集合完全相同，
结果衡量的是退出增益，而不是“提前退出后又多开了几笔”的混合效应。

| Profile | K 线 / 市场事实 | 父生命周期 | P6 最终结果 | 闭包 |
|---|---|---|---|---|
| 15m | `66c65fd4...afe0` / `98863f23...a8b1` | `4a13b40a...580d` | `d1b9b7afed2ced48c6cf0503c6226f7179a62006cdbe0c8e2ab36ba77038947c` | 461 对象 VERIFIED |
| 4h | `81c4e09d...1ee2` / `61ea6116...8794` | `5a7fe11a...1e3c` | `3108ce6d55f4821e758e2bed3bfd9ad78245b653834423dc1174850a6bdd44af` | 201 对象 VERIFIED |

三模型的 `BASELINE_EXIT / BASE / VALIDATION` 净收益均与父 P4/P5 账本逐项相等，
这是固定入场和旧退出未漂移的验收断言。四成本场景只改变固定路径上的手续费和入场
方向性滑点；资金费继续按冻结 HTX `funding_time` 与实际 rate 逐事件计算。

## 3. 条件合同与调用顺序

| 条件 | v1 公式 | 动作 |
|---|---|---|
| `exit.no_progress_timeout` | 15m 16 根 / 4h 6 根后，MFE<0.25R 且净值未正 | 全平 |
| `exit.cost_covered_protection` | MFE 净值≥2倍平仓费且 MFE≥0.25R | 建立成本保护价 |
| `exit.mfe_giveback` | 已覆盖2倍平仓费、仍盈利且回吐≥50% | 全平 |
| `exit.trend_decay` | ADX、ROC、价格斜率、EMA斜率至少3/4衰减，连续2根 | 全平 |
| `exit.opposite_signal` | 同源信号方向与持仓相反 | 全平 |
| `exit.structure_invalidated` | Donchian 回箱、Fib 丢边界或 EMA 跌破/升破慢线 | 全平 |

每次评估保存 `condition_id/version/status/actual/threshold/action/reason`，状态只允许
`PASS / FAIL / UNKNOWN / SKIPPED`。15m VALIDATION 的 BASE 条件轨迹汇总为
`FAIL=24570、PASS=900、SKIPPED=3228、UNKNOWN=0`；4h 为
`FAIL=2245、PASS=173、SKIPPED=348、UNKNOWN=0`。本批冻结数据指标完整，因此没有
实际 UNKNOWN；单元测试覆盖预热或指标缺失时的 UNKNOWN 合同。

完整调用图见
[`HTX_PYTHON_EXIT_OPTIMIZATION_P6_20261018.drawio`](HTX_PYTHON_EXIT_OPTIMIZATION_P6_20261018.drawio)。

## 4. 15m 结果

| 模型 | TRAIN 选择 | 笔数 | 旧退出净收益/PF | 新退出净收益/PF | 净增益 | 胜率变化 | 结论 |
|---|---|---:|---:|---:|---:|---:|---|
| Donchian | structure invalidated | 90 | `+10.3927 / 1.2359` | `+1.7719 / 1.0640` | `-8.6207` | 46.67%→28.89% | REJECT |
| Fibonacci | MFE giveback | 98 | `-12.1235 / 0.6288` | `-13.6757 / 0.4936` | `-1.5521` | 32.65%→64.29% | REJECT |
| EMA/VWAP | MFE giveback | 429 | `-24.7418 / 0.6861` | `-17.9043 / 0.6966` | `+6.8375` | 58.51%→66.20% | REJECT |

EMA/VWAP 的胜率与净收益亏损幅度改善，但策略仍亏损，CONSERVATIVE 净收益为
`-60.2210U`，配对置换 `p=0.2369`，bootstrap 增益下界 `-0.0067U`。这说明提高胜率
并不等于形成可准入的正期望退出规则。Donchian 与 Fibonacci 在验证段反而恶化。

## 5. 4h 结果

| 模型 | TRAIN 选择 | 笔数 | 旧退出净收益/PF | 新退出净收益/PF | 净增益 | 胜率变化 | 结论 |
|---|---|---:|---:|---:|---:|---:|---|
| Donchian | cost covered protection | 8 | `+19.1423 / 4.8789` | `+20.1473 / 8.7553` | `+1.0050` | 75.00%→87.50% | INSUFFICIENT_EVIDENCE |
| Fibonacci | no progress timeout | 7 | `+16.1696 / 9.3175` | `+14.8952 / 5.6280` | `-1.2744` | 不变 | INSUFFICIENT_EVIDENCE |
| EMA/VWAP | no progress timeout | 51 | `+19.9861 / 1.7794` | `+23.6260 / 2.3628` | `+3.6399` | 84.31%→76.47% | REJECT |

4h EMA/VWAP 的超时退出在 TRAIN 也由 `+44.0510U/PF1.4038` 改善到
`+51.0504U/PF1.5602`，CONSERVATIVE VALIDATION 为 `+19.1209U`。但增益集中在少量
事件：验证段只触发 8 笔，配对统计和时间稳定性均失败。它保留为 P6 失败实验，不进入
P7；后续若获得全新样本，应以相同固定公式重新预注册验证，不能回头读取 TEST 调参。

## 6. 调用方式与边界

```bash
cd htx-trend-python
.venv/bin/htx-trend exit-optimize \
  --evidence research-data/six-month-btc-eth \
  --manifest-hash 66c65fd4d1dd69aa70d0153e63807ea9cb767d84779b41eb9e3aa745c494afe0 \
  --market-facts-manifest-hash 98863f2304dfdce8981221cb9eb0a2df34299cdf71107d5dbb9bde33d3c5a8b1 \
  --lifecycle-result-hash 4a13b40a880fbaac91ca8da5b87e86be05bcaf6bffc050aba0eda75aa68f580d \
  --profile INTRADAY_15M

.venv/bin/htx-trend exit-optimize \
  --evidence research-data/p5-swing-expanded \
  --manifest-hash 81c4e09d30e36cb2f17212669670c8fe395c2999ad9341675cf88f2527fc1ee2 \
  --market-facts-manifest-hash 61ea6116b9a2a1f85fed31e81c6d0d203152485d1284607abf1b28aeee248794 \
  --lifecycle-result-hash 5a7fe11aa4e459ecbeefd891299a63f1967ee40d90576b2cc56e0632226b1e3c \
  --profile SWING_4H
```

P6 是配对退出归因，不是新 OOS，也不等价于完整账户生命周期回测。由于没有条件获得
P7 资格，下一步不能执行 P7 组合搜索；应冻结当前失败结论，保留末尾 TEST 未读，并在
新增独立数据或新预注册假设出现后再开始下一轮研究。
