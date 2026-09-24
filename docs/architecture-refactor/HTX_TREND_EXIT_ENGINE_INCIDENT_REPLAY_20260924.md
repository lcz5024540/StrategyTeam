# HTX 趋势退出引擎 v2：9 月 24 日事故 K 线回放

> 日期：2026-09-24
> 范围：`FIB_PULLBACK_CONTINUATION` 的 DOGE、SUI、XRP、ZEC 五笔已平空单。
> 结论：**不能直接部署 v2 真实平仓。** 它能保护 DOGE、SUI 两笔盈利回吐，但 XRP 两笔与 ZEC 在原平仓窗口内不产生 v2 动作；若直接启用，Fibonacci 仓位不会再落回旧 `FLAT` 平仓，三笔会继续暴露。

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
| XRP 156 | -0.6320 U | 截至旧平仓窗口最后 bar 仍为 `HOLD` | 不可与已平仓结果比较 | — | 没有达到 v2 里程碑，也未出现可执行的反转减仓条件 |
| ZEC 159 | -0.5009 U | 截至旧平仓窗口最后 bar 仍为 `HOLD` | 不可与已平仓结果比较 | — | 同上 |
| XRP 160 | -0.6732 U | 截至旧平仓窗口最后 bar 仍为 `HOLD` | 不可与已平仓结果比较 | — | 同上 |

DOGE 与 SUI 的模拟收益是按 K 线收盘价和实际费率的保守离散估算，不是承诺的真实成交结果。真实市价单还会受当时盘口和滑点影响。

## 3. 部署含义

当前 v2 的设计是 Fibonacci 仓位进入 v2 后由 v2 独占退出决定，以避免与旧 `FLAT`、旧 ATR trailing 同时下单。这个边界是正确的，但本回放说明 v2 对“未达到利润里程碑、短时间反转”的样本没有收敛出口。

因此不能以 DOGE、SUI 的改善为理由直接开启 `HTX_TREND_EXIT_ENGINE_V2_LIVE_ENABLED`。下一步应先补齐 v2 的**有界回退机制**：当未武装且 Fibonacci 延续证据消失、持仓超过最短观察窗口并出现明确反向/失效风险时，给出可审计的 `FULL_CLOSE` 或将控制权明确交回兼容退出路径。补完后，重放同一组五笔，要求所有仓位都有明确终态，才比较扣费后总净收益、PF 和最大回撤。

## 4. 验证

- `mvn -q -pl coin-strategy-htx -am -Dtest=HtxTrendExitDecisionEngineIncidentReplayTest -Dsurefire.failIfNoSpecifiedTests=false test`
- `mvn -q -pl coin-strategy-htx -am test`

两项均通过。未部署、未执行生产 SQL、未启用任何 v2 环境变量。
