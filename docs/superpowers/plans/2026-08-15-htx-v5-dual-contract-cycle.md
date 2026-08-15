# HTX V5 双向合约周期实现计划

**Goal:** 将 HTX USDT-M 私有接口迁移到 V5，并为 XAUT-USDT 实现同一价格快照下的开多/开空批量下单、同价双向减仓、深度复验、费用审计和 UID 限频保护。

**Safety boundary:** 默认 `dry-run=true`、全部 live 开关关闭；本计划只改代码和测试，不部署、不调用真实私有接口、不提交真实订单。所有同账户订单显式使用 `self_match_prevent=cancel_both`，不允许策略自成交。

**Architecture:** 公共行情接口保持 v1；8 个私有接口迁移到 V5，另接入 V5 批量下单。`HtxUsdtSwapService` 用独立 V5 wire 模型转换为现有 domain 模型。合约服务以一个批量请求发送 LONG_OPEN/SHORT_OPEN，再以一个批量请求发送 LONG_CLOSE/SHORT_CLOSE；每条腿独立持久化。查询和交易分别使用保守固定窗口限频器。

## Task 1: V5 私有接口与限频

- [x] 增加 V5 响应模型和 `code/message` envelope 解析。
- [x] 迁移 account/position/open-orders/leverage/order/cancel/query 8 个接口。
- [x] 增加 `/v5/trade/batch_orders` 和 `self_match_prevent=cancel_both`。
- [x] 增加每 3 秒 68 次交易、68 次查询的客户端限频保护。
- [x] 用 MockWebServer 覆盖路径、方法、请求字段、V5 状态和错误映射。

## Task 2: 双向四单状态机

- [x] 同一 `openPrice` 批量提交开多和开空。
- [x] 等待并收口两条开仓腿；部分成交只按实际持仓减仓。
- [x] 平仓前重新读取深度，再以同一 `closePrice` 批量提交平多和平空。
- [x] 平仓单强制 `reduce_only=true`；任一腿未知或未平完时保持 fail-closed 暴露状态。
- [x] 保留旧 `/long-cycle` 兼容入口，新增 `/dual-cycle` 并让自动调度走双向周期。

## Task 3: 审计、配置和测试

- [x] 新增 `htx_contract_leg`，记录四条腿的订单 ID、成交量、均价、手续费和已实现盈亏。
- [x] 默认自动间隔调为 500ms，但由 UID 限频器控制真实请求上限。
- [x] 增加双向 happy path、单腿拒绝、部分成交、平仓深度失败、手续费汇总和限频测试。
- [x] 运行 HTX 定向 Maven 测试、模块测试、打包和 `git diff --check`。

## 验证结论

- HTX/MarketMaker 定向覆盖集：217 tests，0 failure，0 error。
- `coin-strategy-htx` 及依赖模块打包通过；Draw.io XML 与 `git diff --check` 通过。
- 全 reactor 仍会命中既有 Binance 在线 debug 测试的外部 `400/401`，与本实现无关。
- 未调用真实私有接口。相同价格的自有买卖单在 `cancel_both` 下不会互相成交，实际周期速度取决于外部成交和 HTX UID 限频，500ms 不是成交保证。
