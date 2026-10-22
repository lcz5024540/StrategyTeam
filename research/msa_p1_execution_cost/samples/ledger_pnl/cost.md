# 执行成本账

仅用于校准回测成本。不改变开单逻辑，不当作过闸证据，不回写已封存目标净效应。

回测往返成本假设 12 bps（与 research Config.rt_cost 默认值一致）。对比表只含开仓滑点、平仓滑点、开平手续费，不含资金费。资金费按持仓期单独汇总。
正滑点表示成交比下单价更差。缺信号价或下单价时滑点留空，不用成交价回填。

## 与 12 bps 假设对比

| 范围 | 分组 | 笔数 | 中位往返 bps | p75 | p90 | 假设 | 中位减假设 | 排除持仓 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| ALL | * | 0 |  |  |  | 12 |  | 15 |
| SIDE | LONG | 0 |  |  |  | 12 |  | 7 |
| SIDE | SHORT | 0 |  |  |  | 12 |  | 8 |
| CONTRACT |  | 0 |  |  |  | 12 |  | 4 |
| CONTRACT | BTC-USDT | 0 |  |  |  | 12 |  | 5 |
| CONTRACT | ETH-USDT | 0 |  |  |  | 12 |  | 6 |

## 成本分布

| 合约 | 方向 | 开平 | 指标 | n | 中位 | p75 | p90 |
| --- | --- | --- | --- | --- | --- | --- | --- |
|  | LONG | CLOSE | fee_bps | 3 | 4 | 4 | 4 |
|  | LONG | OPEN | fee_bps | 4 | 4 | 4 | 4 |
| BTC-USDT | LONG | CLOSE | fee_bps | 2 | 4 | 4 | 4 |
| BTC-USDT | LONG | OPEN | fee_bps | 2 | 4 | 4 | 4 |
| BTC-USDT | SHORT | CLOSE | fee_bps | 3 | 4 | 4 | 4 |
| BTC-USDT | SHORT | OPEN | fee_bps | 3 | 4 | 4 | 4 |
| ETH-USDT | LONG | CLOSE | fee_bps | 1 | 4 | 4 | 4 |
| ETH-USDT | LONG | OPEN | fee_bps | 1 | 4 | 4 | 4 |
| ETH-USDT | SHORT | CLOSE | fee_bps | 5 | 4 | 4 | 4 |
| ETH-USDT | SHORT | OPEN | fee_bps | 5 | 4 | 4 | 4 |
