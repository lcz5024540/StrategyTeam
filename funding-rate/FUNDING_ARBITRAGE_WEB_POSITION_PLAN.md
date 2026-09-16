# 跨交易所资金费率仓位 Web 展示计划

## 1. 目标

为跨交易所资金费率套利提供只读仓位控制台，按一个组合仓位同时展示多头腿、空头腿、资金费、手续费、价差盈亏和合并收益。首期不在 Web 页面开放真实下单。

## 2. 当前基线与缺口

- `coin-strategy-web` 已有策略总览、仓位、订单、时间线等通用接口和静态 dashboard。
- 当前 FundingArb 页面仍读取 `single_exchange_arbitrage_*` 表，不能代表新的跨交易所五表数据。
- Job 已有跨所机会、仓位和 Paper PnL 接口，但 Web 端尚未形成组合仓位详情模型。

## 3. 成功标准

1. 页面可展示所有 `OPENED/CLOSING/MANUAL_INTERVENTION` 组合仓位，刷新延迟不超过 30 秒。
2. 每个组合仓位同时展示 long/short 两腿的交易所、方向、数量、成交均价、mark price、资金费和手续费。
3. 合并 PnL 明确拆分为 `basisPnl + fundingPnl - commission - slippage/otherCost`，并标识 `PAPER_PUBLIC_ESTIMATE` 或未来的 `EXCHANGE_LEDGER_ACTUAL` 来源。
4. 页面可查看订单状态迁移、资金费结算和风险快照，异常裸仓必须醒目标红。
5. API 与前端测试通过，JDK 固定为 17，前端 build 通过。

## 4. 范围与非范围

### 范围

- 组合仓位总览、列表和详情。
- 两腿订单、资金费结算、风险审计时间线。
- 四所健康状态、行情延迟和错误摘要。
- 当前/累计合并 PnL、名义收益率和保证金收益率。

### 非范围

- Web 真实开仓、平仓或划转。
- API Key 展示或编辑。
- 正负混合资金费率自动开仓。

## 5. 里程碑

| 里程碑 | 工作内容 | 验收 |
|---|---|---|
| W1-T1 后端读模型 | Web 改读 `cross_exchange_arb_*` 五表；新增组合仓位详情、订单、结算、风险和组合 PnL API | Controller/Service 单测；不得再把单所仓位冒充跨所数据 |
| W1-T2 前端总览 | 总览卡片、四所健康、活跃仓位表、状态和下一结算倒计时 | 空数据、正常、降级三种页面状态可用 |
| W1-T3 仓位详情 | Long/Short 双腿卡片、订单与结算时间线、PnL 分解 | 任一字段缺失显示不可用，不用 0 伪装 |
| W1-T4 联调验收 | 对接 Paper 环境、刷新/错误处理、响应式布局和构建验证 | 一笔 Paper 仓位从开仓到结算、平仓在页面形成完整闭环 |

## 6. API 计划

```text
GET /api/arbitrage/funding/portfolio/summary
GET /api/arbitrage/funding/positions
GET /api/arbitrage/funding/positions/{positionId}
GET /api/arbitrage/funding/positions/{positionId}/orders
GET /api/arbitrage/funding/positions/{positionId}/settlements
GET /api/arbitrage/funding/positions/{positionId}/risk-events
GET /api/arbitrage/funding/portfolio/pnl/timeline
GET /api/arbitrage/funding/exchanges/health
```

## 7. 合并 PnL 口径

```text
combinedTotalPnl
= longLegPricePnl
 + shortLegPricePnl
 + longFundingCashFlow
 + shortFundingCashFlow
 - longCommission
 - shortCommission
 - slippageAndOtherCost
```

资金费以交易所账单的实际正负现金流为准；Paper 阶段使用公共行情估值并明确标识来源。充值、提现和账户内部划转不计入交易收益。

## 8. 风险与缓解

- **旧页面数据源错误**：FundingArb 当前复用单所表。后端先完成专用读模型和契约测试，再接前端。
- **跨所时间不一致**：所有账单保存交易所时间与接收时间，展示统一转换为 UTC/Asia-Shanghai。
- **收益重复计入**：按 `positionId + exchange + settlementTime + ledgerId` 幂等归集。
- **行情缺失导致假 PnL**：字段缺失显示 `N/A` 并标记陈旧，不使用开仓价或 0 回填。

## 9. 验证方式

1. 后端使用确定性双腿订单、资金费和手续费样本验证合并 PnL。
2. 前端覆盖空仓、正常持仓、部分成交、补偿中、人工干预和已平仓场景。
3. Paper 环境完成开仓、至少一次模拟结算、平仓及页面对账。
4. `mvn` 显式使用 JDK 17；前端执行 lint、test、build。
