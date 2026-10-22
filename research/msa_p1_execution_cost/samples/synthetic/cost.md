# 执行成本账

仅用于校准回测成本。不改变开单逻辑，不当作过闸证据，不回写已封存目标净效应。

回测往返成本假设 12 bps（与 research Config.rt_cost 默认值一致）。对比表只含开仓滑点、平仓滑点、开平手续费，不含资金费。资金费按持仓期单独汇总。
正滑点表示成交比参考价更差。缺信号价或下单价时滑点留空，不用成交价或信号价回填。
逐笔账把滑点拆成三段：S1 信号价到下单价，S2 下单价到成交均价，S3 成交均价相对下一根 bar 开盘价。
与 12 bps 假设对比的实际总成本只含 S3 和手续费，不含 S1、S2 和资金费。
汇总按 LIVE/PAPER 与币种分组。可计算成本的成交腿不少于 200 且至少两个币种簇时，才给出按币种聚类的均值和 95% 区间；否则只保留分位数，并标明样本不足不下结论。

## 与 12 bps 假设对比

| 范围 | 分组 | 笔数 | 中位往返 bps | p75 | p90 | 假设 | 中位减假设 | 排除持仓 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| ALL | * | 3 | 24 | 24 | 24 | 12 | 12 | 3 |
| SIDE | LONG | 2 | 24 | 24 | 24 | 12 | 12 | 3 |
| SIDE | SHORT | 1 | 8 | 8 | 8 | 12 | -4 | 0 |
| CONTRACT | BTC-USDT | 1 | 8 | 8 | 8 | 12 | -4 | 1 |
| CONTRACT | ETH-USDT | 2 | 24 | 24 | 24 | 12 | 12 | 2 |

## 成本分布

| 合约 | 方向 | 开平 | 指标 | n | 中位 | p75 | p90 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| BTC-USDT | LONG | OPEN | fee_bps | 1 | 0 | 0 | 0 |
| BTC-USDT | SHORT | CLOSE | fee_bps | 1 | 4 | 4 | 4 |
| BTC-USDT | SHORT | CLOSE | order_to_first_fill_ms | 1 | 2000 | 2000 | 2000 |
| BTC-USDT | SHORT | CLOSE | slippage_vs_order_bps | 1 | 0 | 0 | 0 |
| BTC-USDT | SHORT | OPEN | fee_bps | 1 | 4 | 4 | 4 |
| BTC-USDT | SHORT | OPEN | order_to_first_fill_ms | 1 | 2000 | 2000 | 2000 |
| BTC-USDT | SHORT | OPEN | slippage_vs_order_bps | 1 | 0 | 0 | 0 |
| ETH-USDT | LONG | CLOSE | fee_bps | 2 | 2 | 2 | 2 |
| ETH-USDT | LONG | CLOSE | order_to_first_fill_ms | 2 | 500 | 750 | 900 |
| ETH-USDT | LONG | CLOSE | slippage_vs_order_bps | 2 | 5 | 7.5 | 9 |
| ETH-USDT | LONG | OPEN | fee_bps | 4 | 2 | 2 | 2 |
| ETH-USDT | LONG | OPEN | order_to_first_fill_ms | 4 | 1000 | 1125 | 1350 |
| ETH-USDT | LONG | OPEN | slippage_vs_order_bps | 4 | 15 | 25 | 34 |
