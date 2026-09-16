# Hyperliquid + CEX 跨所资金费率套利（Paper）设计

## 结论

Hyperliquid 已作为第五个只读公共行情场所接入跨所 Paper 扫描器。它可与 Binance、OKX、HTX、Bitget 的同一底层币种比较资金费率和盘口，但不会产生钱包操作、签名、订单或资金调拨。

## 数据与计算

1. 通过 `POST /info` 的 `metaAndAssetCtxs` 拉取合约目录、`funding`、`markPx` 和 `oraclePx`，目录结果在进程内缓存 10 秒。
2. 将 Hyperliquid 的 `BTC` 等币种映射成现有价格归一化键 `BTCUSDT`；它代表 USD 计价比较，不表示抵押品相同。Paper 默认假设 USDC/USDT 近似 1:1。
3. 通过 `POST /info` 的 `l2Book` 拉取 20 档 L2；每档名义值按 `price × size` 计算，和 CEX 合约乘数归一化后的盘口走同一容量/VWAP 逻辑。
4. Hyperliquid 的资金费率是 1 小时周期。仅当两腿下一次结算时间偏差不超过既有阈值时才会比较；双方先转成小时费率，再以较长的结算周期投影收益。例如 8 小时 CEX 与 Hyperliquid 配对时，以 8 小时作为 Paper 评估窗口，Hyperliquid 的当前小时费率投影 8 次。这个投影是当前费率不变的 Paper 假设，不是对未来费率的承诺。
5. 预估净收益扣除两腿 round-trip taker 成本、滑点、基差缓冲和可选的每腿 `capital-transfer-cost-rate`。该字段只用于 Paper 摊销假设，默认 `0`。

## 安全与回滚

- `HyperliquidFundingRateGateway` 继承只读网关；私有订单和账户方法保持抛出 `UnsupportedOperationException`。
- 不新增凭据、钱包地址、私钥、签名库或链上交易依赖。
- 若公共接口连续失败、USDC/USDT 明显偏离或运营方不再认可该成本假设，从 `arbitrage.multi-exchange.exchanges` 移除 `HYPERLIQUID` 并重启 Funding-only Job；其余四家场所和既有数据不受影响。

## 已验证

- JDK 17：公共行情解析 7 项、核心决策与只读边界 15 项、Spring Paper 配置 1 项均通过。
- 实际无鉴权接口：`metaAndAssetCtxs` 返回合约及资金费率上下文；`l2Book` 返回 BTC 买卖盘。
- 仍待：在隔离环境启动五所公共数据的 7 天 Paper 验收；不得部署或开启真实交易。
