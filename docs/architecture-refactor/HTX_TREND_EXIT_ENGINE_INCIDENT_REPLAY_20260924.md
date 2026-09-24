# HTX 趋势退出引擎 v2：9 月 24 日事故 K 线回放

> 日期：2026-09-24
> 范围：`FIB_PULLBACK_CONTINUATION` 的 DOGE、SUI、XRP、ZEC 五笔已平空单。
> 结论：**仍不能直接部署 v2 真实平仓。** 已补入未武装仓位的有界回退，但事故样本本身没有一笔同时满足“连续 3 根完整 post-entry `FLAT`、净亏且亏损超过预算”。它不能被用来伪造这五笔都有新退出结果。

## 1. 可重复测试

测试：[HtxTrendExitDecisionEngineIncidentReplayTest.java](../../coin-strategy-htx/src/test/java/com/metaverse/coin/strategy/htx/service/HtxTrendExitDecisionEngineIncidentReplayTest.java)

固定输入：[htx-trend-exit-incident-20260924.json](../../coin-strategy-htx/src/test/resources/htx/replay/htx-trend-exit-incident-20260924.json)

输入由以下只读事实组成：

- HTX USDT 永续公开 15 分钟 K 线；
- 生产库 `htx_trend_signal` 的 15 分钟信号、ATR、EMA 快慢线；
- 生产账本的实际开仓价、合约面值、张数、开仓手续费、初始止损、失效价和 Fibonacci 结构目标；
- 账户实际使用的单边 `0.0004` 费率。

回放从每笔入场后的**第一根完整 15 分钟 K 线**开始，排除入场所在 K 线，避免把入场前发生的 high/low 当成开仓后的有利价格。每根 K 线以收盘价作为可执行参考价，以 high/low 更新 MFE 和 ATR 保护价；未伪造盘口、滑点或市价成交优先级。

## 2. 结果

| 仓位 | 原实际净收益 | v2 在原窗口的结果 | 模拟扣费后净收益 | 相比原结果 | 判断 |
|---|---:|---|---:|---:|---|
| DOGE 153 | -0.4201 U | 09-24 04:30，`RUNNER/FULL_CLOSE`，0.092272 | +2.5995 U | +3.0196 U | 动态回吐后武装保护，随后穿越保护价，锁住收益 |
| SUI 154 | -0.3528 U | 09-24 00:15，`RUNNER/FULL_CLOSE`，0.9588 | +0.9672 U | +1.3200 U | 1R 里程碑武装后，保护价被穿越，正确退出 |
| XRP 156 | -0.6320 U | 截至旧平仓窗口最后 bar 仍为 `HOLD` | 不可与已平仓结果比较 | — | 回放中仅两根完整 post-entry `FLAT`，未达到生产 3 根确认 |
| ZEC 159 | -0.5009 U | 截至旧平仓窗口最后 bar 仍为 `HOLD` | 不可与已平仓结果比较 | — | K 线收盘价估算为 `+0.6119U`，不满足净亏回退条件；与实际成交损益的差异不得被忽略 |
| XRP 160 | -0.6732 U | 截至旧平仓窗口最后 bar 仍为 `HOLD` | 不可与已平仓结果比较 | — | 回放中仅两根完整 post-entry `FLAT`，未达到生产 3 根确认 |

DOGE 与 SUI 的模拟收益是按 K 线收盘价和实际费率的保守离散估算，不是承诺的真实成交结果。真实市价单还会受当时盘口和滑点影响。

## 3. 部署含义

当前 v2 的设计是 Fibonacci 仓位进入 v2 后由 v2 独占退出决定，以避免与旧 `FLAT`、旧 ATR trailing 同时下单。这个边界仍然正确；本轮新增的回退动作也仍由 v2 自己记录和执行，不会在 service 外部偷偷落回旧链路。

未武装的 Fibonacci 仓位现在会在以下所有条件同时成立时返回 `THESIS_EXPIRED/FULL_CLOSE`：未达到任何利润保护里程碑、当前信号及**连续** `FLAT` 信号达到 `HTX_TREND_FLAT_LOSS_EXIT_CONFIRMATION_BARS`、扣费后净收益为负、绝对亏损达到 `max(0.25U, 入场名义额 × 0.003)`。模型失效、初始止损和已武装保护价仍有更高优先级。

这一规则已由单元测试覆盖“满足连续 3 根且超预算则平仓”以及“仅两根连续 `FLAT` 不平仓”。但这五笔冻结事故 K 线不提供一个完整命中样本：两笔 XRP 的可观察窗口只有两根完整 K 线，ZEC 的 bar-close 估算并非净亏。下一步应补齐真实成交时点的盘口和逐笔成交数据，再对 XRP/ZEC 做执行价与滑点归因；在此之前不能以 DOGE、SUI 的改善为理由开启 `HTX_TREND_EXIT_ENGINE_V2_LIVE_ENABLED`。

## 4. 验证

- `mvn -q -pl coin-strategy-htx -am -Dtest=HtxTrendExitDecisionEngineIncidentReplayTest -Dsurefire.failIfNoSpecifiedTests=false test`
- `mvn -q -pl coin-strategy-htx -am test`

两项均通过。未部署、未执行生产 SQL、未启用任何 v2 环境变量。
