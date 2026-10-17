# HTX Python 双周期 V2 冻结数据研究报告（2026-10-17）

## 结论

本轮研究使用冻结数据 manifest
`66c65fd4d1dd69aa70d0153e63807ea9cb767d84779b41eb9e3aa745c494afe0`，
按时间顺序切分 60%/20%/20%，测试集已消费。双 profile 基线结论为
`REJECT`，不得据此申请 PAPER 或 LIVE 准入。

| Profile | OOS 完整交易 | BASE 净收益 | PF | 最大回撤 | 日均闭环 | 结论 |
|---|---:|---:|---:|---:|---:|---|
| 15m | 271 | -48.9259 | 0.5513 | 51.8406 | 7.3243 | REJECT |
| 4h | 17 | -21.6375 | 0.1601 | 22.6805 | 0.4595 | INSUFFICIENT_EVIDENCE |
| 合计 | 288 | -70.5634 | [不跨 profile 合并 PF] | [不跨 profile 合并回撤] | 7.7838 | REJECT |

15m 达到频率目标，但扣费后净收益、期望、PF、保守成本和 bootstrap
期望下界均未通过。4h 基线 OOS 只有 17 笔，未达到 30 笔最低样本量；其
EMA/VWAP 独立模型样本较多，但经济性不通过，结论为 `REJECT`。

## 同源调用链

```text
COMPLETE manifest
  -> profile 专属 V2 spec + BACKTEST-only provisional admission
  -> HtxStrategyDecisionEngine（闭合 K 线，160 根窗口）
  -> model candidate / 记录型确认条件
  -> evaluate_gate（评分、资金费、时效、价差、追价、容量、风险 sizing）
  -> 下一根 K 线开盘 + 方向性滑点
  -> 单 symbol/profile 持仓状态
  -> 硬止损优先
  -> exits.decide
       Fibonacci -> V4
       Donchian / EMA-VWAP -> 既有兼容退出核
  -> 部分退出 / 最终平仓 / 费用 / 资金费 / MFE / MAE
  -> 完整交易账本与绩效
```

每次部分退出仍属于原 position，只有最终平仓计一笔完整交易。不同时间切分
之间不携带持仓，避免训练、验证和测试相互污染。追加未来 K 线不会改变已经
形成的历史决策，固定随机种子的置换和时间块 bootstrap 可重复。

## 实验范围

每个 profile 均运行四个成本场景：ZERO、OPTIMISTIC、BASE、CONSERVATIVE。
研究变体包括：完整基线、Donchian/Fibonacci/EMA-VWAP 三个主模型、逐项叠加
RSI、MACD、价格斜率、EMA 方向和 regime，以及候选评分 65/70 邻域。

15m 的全部变体均为 `REJECT`。4h 除 EMA/VWAP 变体为 `REJECT` 外，其余变体
均因 OOS 交易不足 30 笔而为 `INSUFFICIENT_EVIDENCE`。五个 walk-forward
验证窗中，15m 只有一个窗净收益为正，4h 也只有一个窗为正，稳健性不足。

## 执行假设与限制

冻结数据只有 OHLCV，没有历史 L2 盘口、账户状态和逐期真实资金费。因此本产物
标记为 `STRATEGY_SIMULATION`：盘口围绕下一根开盘价合成，资金费采用成本场景
常数，合约面值采用 `0.001` 的归一化研究单位，单笔最多消费该 K 线成交量 1%，
同根同时触发止损和目标时先执行止损。这些结果不能称为真实成交复现。

完整账本与全部实验结果存放在不可变 evidence CAS，不进入 Git：

- 双 profile 索引：`a8bc3c1e82ad848a709d21693989809e14892918d4f1c0ee5c811331a95e8340`
- 15m：`2141dba61aad435931cd2214594d1358530260c87814a1f64c6d48c8c19cc8fb`
- 4h：`08e79512a5d8180cfc3d4fb555d79dcf927dc9966e61ae9f3491073f489c0396`

可复算命令：

```bash
PYTHONPATH=src .venv/bin/python -m htx_trend.cli profile-research \
  --evidence research-data/six-month-btc-eth \
  --manifest-hash 66c65fd4d1dd69aa70d0153e63807ea9cb767d84779b41eb9e3aa745c494afe0
```

## 准入决定

当前不生成 sealed PAPER/LIVE admission。Shadow 可以继续用于核对候选、profile
隔离、证据闭包和重启恢复；PAPER 只有在形成新的、预注册且通过经济性门槛的
候选版本后才有晋级意义。真实 Shadow 2–3 天与 PAPER 14 天不能由本报告替代。
