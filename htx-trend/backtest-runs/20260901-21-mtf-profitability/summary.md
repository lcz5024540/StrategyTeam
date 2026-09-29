# HTX 多周期趋势盈利回测

本报告比较策略与不交易（0U）基准。任一模型未达到扣费后正收益、PF、期望值、样本量和完整冻结输入门槛，即不可进入实盘。

数据集：`research:6f6c9502e0b9fe3f5a2edad467d17ac0`
输入完整性：`PARTIAL`

以下输入存在缺口，本次指标只能用于否定性诊断，不能作为模型淘汰或晋级的最终证据：
- `BTC-USDT:15min:insufficient-coverage`
- `ETH-USDT:15min:insufficient-coverage`
- `XAUT-USDT:15min:insufficient-coverage`

| 模型 | 笔数 | 净收益 | PF | 单笔期望 | 胜率 | 结论 |
|---|---:|---:|---:|---:|---:|---|
| DONCHIAN_BREAKOUT | 4 | -3.1778 | 0.02 | -0.7944 | 25.0% | INSUFFICIENT_EVIDENCE |
| FIB_PULLBACK_CONTINUATION | 2 | -1.8205 | 0.12 | -0.9103 | 50.0% | INSUFFICIENT_EVIDENCE |

整体：6 笔，净收益 -4.9983U，PF 0.06。

边界：使用冻结 K 线的下一根开盘价、双边 taker 费率和同 K 线止损优先规则；不伪造盘口、部分成交或资金费结果。
