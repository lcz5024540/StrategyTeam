# HTX Python R2 P1：真实资金费与合约事实

## 1. 结论

P1 已把研究回测中的两项自定义假设替换为 HTX 逐合约事实：

- 删除“每根持仓 K 线收固定资金费”的计算；
- 删除 BTC/ETH 统一使用 `0.001` 合约面值的计算。

回测现在必须显式绑定一个 `COMPLETE` 的 market-facts manifest。缺少合约、资金费
边界或估值 K 线时返回 `UNKNOWN` 并停止该次正式研究，不使用插值或默认费率。

## 2. 冻结事实

与六个月 K 线 manifest
`66c65fd4d1dd69aa70d0153e63807ea9cb767d84779b41eb9e3aa745c494afe0`
相同的 `2026-04-11T00:00:00Z` 至 `2026-10-11T00:00:00Z` 区间已冻结：

| 项目 | BTC-USDT | ETH-USDT |
|---|---:|---:|
| 历史结算事件 | 549 | 549 |
| 样本内观察到的相邻间隔 | 8 小时 | 8 小时 |
| 合约面值 | 0.001 | 0.01 |
| 价格 tick | 0.1 | 0.01 |
| 最小张数 | 1 | 1 |

事实 manifest 为
`98863f2304dfdce8981221cb9eb0a2df34299cdf71107d5dbb9bde33d3c5a8b1`，
55 个对象的引用闭包已验证。8 小时只是这两个合约在该样本内的统计结果，计算器
不使用固定结算周期，而是逐条消费 `funding_time`。

HTX 历史接口在本区间返回 `realized_rate=null`，因此记录明确标记
`rate_source=FUNDING_RATE`，使用交易所历史记录中的 `funding_rate`；未来若接口
提供 `realized_rate`，同一规则优先使用它。该公共记录是合约结算费率，不冒充
具体账户的真实资金流水；真实账户对账仍应使用私有财务流水。

## 3. 调用与计算

```text
collect-market-facts
  -> swap_contract_info 原始响应
  -> swap_query_elements 原始响应
  -> swap_historical_funding_rate 分页原始响应
  -> 标准化 contract_market_fact / historical_funding_page
  -> COMPLETE market_facts_manifest

profile-research
  -> 校验 K 线 manifest 闭包
  -> 校验 market facts manifest 闭包
  -> signal 时刻只读取当时及以前的最近资金费事件
  -> 使用逐合约 contract_size / price_tick 做 Gate、sizing、费用和盈亏
  -> 持仓跨过真实 funding_time 才计费
  -> LONG cost = 结算时名义额 × rate
     SHORT cost = 结算时名义额 × (-rate)
  -> 资金费事件数、金额与证据状态进入逐笔账本
```

结算时名义额使用该时刻之前最后一根已闭合 K 线的收盘价，避免使用未来价格。
若事件与退出落在同一不可排序的 K 线边界，按保守口径先计资金费。

四个成本场景现在只改变手续费和滑点。交易所资金费事实对所有场景相同，`ZERO`
不再意味着把真实资金费清零。

## 4. 验证

- Python 全量测试：217 项通过；
- 事实 manifest：`COMPLETE`，闭包 55 个对象 `VERIFIED`；
- 不消费 TEST 的训练/验证 smoke 回放：
  - 索引 `7c795e52c154348994ff49edf537d3082a2e96eccf57a09c7f858f3a78eb9091`；
  - 15m BASE：TRAIN 815 笔跨 309 个事件，VALIDATION 233 笔跨 101 个事件；
  - 4h BASE：TRAIN 36 笔跨 369 个事件，VALIDATION 34 笔跨 117 个事件；
  - 每笔账本 `funding_evidence_status=COMPLETE`。

本次 smoke 不消费既有 TEST，也不形成新的策略经济性结论。旧报告的金额与 PF
因资金费和 ETH 合约面值口径错误而降级为诊断数据。P2 将在修正后的事实口径上
生成候选全量标签与准确率归因。

## 5. 已知边界

- HTX 公共接口没有历史合约规格版本；manifest 保存本次观察时间和原始响应，
  当前规格用于历史研究时明确标记为 point-in-time observation；
- 冻结 OHLCV 没有结算瞬间 mark price，使用当时可知的最后闭合 K 线收盘价；
- 盘口和账户余额仍是策略模拟假设，结果继续标记 `STRATEGY_SIMULATION`；
- 本阶段没有修改策略条件、阈值、V4 退出、LIVE、数据库或部署。
