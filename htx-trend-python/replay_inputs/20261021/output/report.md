# 完整 runtime.scan 离线重放

包版本 `0.1.0`；配置 `HTX_TREND_15M_V2`；现网版本（输入描述）`0.1.6-paper-db-c79afbaa`。

完整对齐 **9/12**；方向/模型入场对齐 **12/12**；多发 **0**；漏发 **0**。

完整对齐=position入场与每腿数量、15m退出桶、原因均相同；逐腿参考缺失不能记为完整通过。入场按12个position统计。

## 输入哈希

- `klines.csv`: `c9317a85fcca1b9fce1a88409f2356dd668cc22688a3d173797f1712a8aef923`
- `trades_closed_window.csv`: `845643abcd4e8d9e153d823ae956f23f7c213684b906902dbd62641662337359`

## 逐笔对齐

|现网开仓 CST|币种|方向/模型|现网出场|重放出场|结果/归因|
|---|---|---|---|---|---|
|2026-10-20 03:15:40|ETH-USDT|SHORT/EMA_MOMENTUM_CONTINUATION|trend-weakened-protection-breach|trend-weakened-protection-breach|一致|
|2026-10-20 08:15:39|ETH-USDT|SHORT/EMA_MOMENTUM_CONTINUATION|armed-protection-breach-atr-impulse|armed-protection-breach-atr-impulse|一致|
|2026-10-20 09:15:12|BTC-USDT|SHORT/DONCHIAN_BREAKOUT|model-or-initial-stop-invalidated|model-or-initial-stop-invalidated|一致|
|2026-10-20 09:15:12|ETH-USDT|SHORT/DONCHIAN_BREAKOUT|model-or-initial-stop-invalidated|model-or-initial-stop-invalidated|一致|
|2026-10-20 13:30:33|ETH-USDT|LONG/EMA_MOMENTUM_CONTINUATION|model-or-initial-stop-invalidated|model-or-initial-stop-invalidated|一致|
|2026-10-20 15:45:25|ETH-USDT|SHORT/DONCHIAN_BREAKOUT|armed-protection-breach-atr-impulse|armed-protection-breach-atr-impulse|一致|
|2026-10-20 14:15:13|BTC-USDT|SHORT/EMA_MOMENTUM_CONTINUATION|armed-protection-breach-atr-impulse|armed-protection-breach-atr-impulse|一致|
|2026-10-20 18:15:26|ETH-USDT|SHORT/EMA_MOMENTUM_CONTINUATION|trend-weakened-protection-breach|trend-weakened-protection-breach|EXIT_LEGS_MISMATCH: see exit_leg_alignment for quantity/time/reason differences|
|2026-10-20 21:15:34|BTC-USDT|SHORT/EMA_MOMENTUM_CONTINUATION|model-or-initial-stop-invalidated|model-or-initial-stop-invalidated|一致|
|2026-10-21 02:15:17|BTC-USDT|LONG/EMA_MOMENTUM_CONTINUATION|trend-weakened-protection-breach|profit-at-risk-with-deteriorating-continuation|EXIT_LEGS_MISMATCH: see exit_leg_alignment for quantity/time/reason differences|
|2026-10-21 09:30:46|BTC-USDT|LONG/FIB_PULLBACK_CONTINUATION|model-or-initial-stop-invalidated|model-or-initial-stop-invalidated|一致|
|2026-10-20 23:00:32|ETH-USDT|LONG/DONCHIAN_BREAKOUT|model-or-initial-stop-invalidated|None|EXIT_OUTSIDE_KLINE_COVERAGE: reference close exceeds replay/K-line coverage; no extrapolation or forced close|

## 逐腿出场对齐（时间按15m桶，数量按张）

|position|腿|现网时间 / 数量 / 原因|重放时间 / 数量 / 原因|一致|
|---|---|---|---|---|
|paper-20d87b03-2979-476f-9c97-734690348e96|1|2026-10-20 04:15:17 / 7 / trend-weakened-protection-breach|2026-10-20T04:15:30+08:00 / 7 / trend-weakened-protection-breach|True|
|paper-a4e8bbbe-b8bd-4152-be36-79a3836fcde6|1|2026-10-20 09:00:32 / 7 / armed-protection-breach-atr-impulse|2026-10-20T09:00:30+08:00 / 7 / armed-protection-breach-atr-impulse|True|
|paper-67798c74-aef6-4a34-a260-716ebe2bbc4e|1|2026-10-20 12:00:44 / 1 / model-or-initial-stop-invalidated|2026-10-20T12:00:30+08:00 / 1 / model-or-initial-stop-invalidated|True|
|paper-8dd5e5fb-e6f4-4ad1-978e-426ad8655f0d|1|2026-10-20 12:00:44 / 3 / model-or-initial-stop-invalidated|2026-10-20T12:00:30+08:00 / 3 / model-or-initial-stop-invalidated|True|
|paper-1d723b39-cfd4-45c6-9f84-1361ca528644|1|2026-10-20 14:00:34 / 7 / model-or-initial-stop-invalidated|2026-10-20T14:00:30+08:00 / 7 / model-or-initial-stop-invalidated|True|
|paper-d3f16232-710b-4f25-91e9-98ea234179a7|1|2026-10-20 16:45:32 / 5 / armed-protection-breach-atr-impulse|2026-10-20T16:45:30+08:00 / 5 / armed-protection-breach-atr-impulse|True|
|paper-91cb6914-e5e5-44ea-897d-4413180763de|1|2026-10-20 18:15:26 / 2 / armed-protection-breach-atr-impulse|2026-10-20T18:15:30+08:00 / 2 / armed-protection-breach-atr-impulse|True|
|paper-f56d3d17-02f9-4a9b-afeb-099487522f30|1|2026-10-20 19:30:46 / 5 / trend-weakened-protection-breach-partial|2026-10-20T19:30:30+08:00 / 3 / profit-at-risk-with-deteriorating-continuation|False|
|paper-f56d3d17-02f9-4a9b-afeb-099487522f30|2|2026-10-20 19:45:32 / 2 / trend-weakened-protection-breach|2026-10-20T19:45:30+08:00 / 4 / trend-weakened-protection-breach|False|
|paper-5ddbbabb-3671-4e54-ba8a-e9f27fadcedd|1|2026-10-20 22:00:40 / 2 / model-or-initial-stop-invalidated|2026-10-20T22:00:30+08:00 / 2 / model-or-initial-stop-invalidated|True|
|paper-36733c1f-0d27-4186-82aa-789b463dc424|1|2026-10-21 09:00:21 / 1 / profit-at-risk-with-deteriorating-continuation|2026-10-21T05:00:30+08:00 / 1 / profit-at-risk-with-deteriorating-continuation|False|
|paper-36733c1f-0d27-4186-82aa-789b463dc424|2|2026-10-21 09:15:54 / 1 / trend-weakened-protection-breach|2026-10-21T09:00:30+08:00 / 1 / profit-at-risk-with-deteriorating-continuation|False|
|paper-eb68a4f6-0ada-41d0-ab34-f83a11bea8ca|1|2026-10-21 10:30:19 / 2 / model-or-initial-stop-invalidated|2026-10-21T10:30:30+08:00 / 2 / model-or-initial-stop-invalidated|True|
|paper-93cef710-d3d0-4c85-bb79-4c76080a5ad8|1|2026-10-21 13:45:33 / 1 / model-or-initial-stop-invalidated|缺失|False|

## 窗外（不计完整通过）

- paper-93cef710-d3d0-4c85-bb79-4c76080a5ad8：参考平仓 2026-10-21 13:45:33；重放/行情有效覆盖截至 2026-10-21T10:30:59+08:00。缺13:45时段15m/60m行情；不外推、不强平。

## 未闭合差异与缺字段

窗内出场未对齐 2 仓；窗外 1 仓。完整对齐 9/12，未达 ≥11/12。
逐腿时间/数量/原因见上表；固定输入下仅补齐参考不能恢复真实盘口。以下为该参考集的证据边界：

- 默认假设重跑观察：BTC 36733c1f（02:15）：缺真实入场及管理时刻 bid/ask/mid、tick路径与实际评估时间。forming-open入场约82610，现网82633.5，MFE/武装时序级联偏移（重放04:45 ARM，05:00已非newly_armed）。不可凭OHLC唯一恢复；保留A，不回滚open-mid、不注入参考退出。
- 默认假设重跑观察：ETH f56d3d17：缺真实管理盘口mid/tick路径及实际评估时间。参考19:30减5张trend-weakened-partial、19:45减2张trend-weakened；重放19:30减3张profit-at-risk、19:45减4张trend-weakened。原因与数量路径不可由固定K线唯一确定。
- ETH 93cef710（23:00）：缺覆盖13:45退出的15m/60m行情；详见窗外。盘口、funding、contract_spec假设继续保留。

未改runtime/paper_worker/execution/exits；未回滚A；未出包、未装机。

## 多发（参考集中未观察到）

无。

## 漏发

无。

## 假设和证据边界

- 盘口取证不可得 → 假设：scan_delay 时刻用形成中 15m bar 的 open 作 mid（优于上一根 close）；无形成中 bar 时回退上一根 close；spread=0（默认，仍支持配置 spread）；没有真实盘口深度、滑点或tick路径。
- 取证不可得 → 假设：funding / contract_spec：funding默认0；contract_size BTC=.001/ETH=.01，tick=.01，合约可交易。
- 假设/缺数据：初始空仓、无历史结构键/订单/手续费；300U、10倍、保证金上限10%、5仓，后续余额/风险按原worker动态计算。
- 假设/缺数据：固定收盘后30秒，每15m调用一次；10秒收盘宽限；窗口160根，60m仅完整性检查，scan不使用其趋势。
- 假设/缺数据：使用当前源码与默认配置；未证明等同现网构建及环境覆盖。源码哈希用于复跑。
- 假设/缺数据：book深度与turnover缺失；require_execution_facts=False，深度、独立可用保证金、FIB质量、逆市/混合市场等受该开关控制的门未启用；min_average_turnover=0、fee_protection_enabled=False。硬保证金上限与止损风险门仍执行。
- 原worker语义：prior_trade=None（cooldown无历史输入），市场context中性，退出opposition=0、flat_streak/breach_bars默认0。
- 原worker语义：同币tick开始有仓则仅管理，即使本tick平仓也不再开；所有历史结构键持续去重。
- 参考集：窗口内开仓且截至导出已平仓12个position；全部14腿来自预生成state close orders + EXIT_DECISION，BTC归因已交叉验证。ETH 23:00平仓晚于固定K线覆盖，不外推行情。
- CSV signal_bar_cst按成交桶解释；真实候选bar开盘为该值减15m；同时输出两种时间。
- 退出使用原runtime.manage/exits.decide，包括部分平仓；数据末尾未平仓不强平。
- 门输出为原函数实际短路路径；失败之后未到达的门不能记为通过；没有候选或已有仓时不执行候选门。
- 只读固定CSV、MANIFEST和本包源码；内存ledger，无HTTP/数据库/密钥读取，不初始化生产worker。

```json
{
  "btc_contract_size": "0.001",
  "end_cst": "2026-10-21 10:30:59",
  "equity": "300",
  "eth_contract_size": "0.01",
  "funding_rate": "0",
  "leverage": "10",
  "margin_rate": "0.10",
  "max_active_positions": 5,
  "price_tick": "0.01",
  "scan_delay_seconds": 30,
  "spread_rate": "0",
  "start_cst": "2026-10-19 21:35:46"
}
```

完整配置、全部源码 SHA256、逐bar候选/路由/门阈值与实际值、逐持仓退出决策见同目录 report.json。
