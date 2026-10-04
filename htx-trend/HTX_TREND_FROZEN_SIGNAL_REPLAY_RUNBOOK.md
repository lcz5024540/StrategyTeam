# 冻结 K 线生产信号回放运行说明

本命令只回放当前 Java 生产代码中的趋势信号与组合候选计算。输入只能是内容寻址且完整的公共 K 线冻结集；它不读取数据库、HTX、账户密钥或当前账户状态。

输出中的 `INSUFFICIENT_EVIDENCE` 是刻意保留的事实边界：历史盘口、账户费率、保证金、交易所仓位和当时的 ensemble 环境参数都没有冻结。每次回放必须显式传入配置快照，命令不再隐式使用代码默认值；代码默认快照仍不能复放最终 Gate、仓位、真实成交、净 PnL 或历史环境变量覆盖。本产物用于验证“同一根已完成 K 线在指定计算公式与参数下会生成什么信号和候选”，不是完整策略回测，更不能据此调整实盘参数。

先构建离线运行包：

```bash
mvn -q -pl coin-strategy-htx -am package -DskipTests
```

以下命令以北京时间回放 BTC 与 ETH 在 2026-09-18 的 15 分钟已完成 K 线，并流式写出 JSONL。必须显式指定 `--contracts`；回放严格使用生产服务的 `klineSize=180` 滚动窗口，仍先以小窗口验证产物和计算耗时，再安排全量 52 币运行。

```bash
java -Dloader.main=com.metaverse.coin.strategy.htx.replay.HtxTrendFrozenSignalReplayCommand \
  -cp coin-strategy-htx/target/coin-strategy-htx-0.0.1-SNAPSHOT.jar \
  org.springframework.boot.loader.PropertiesLauncher \
  --data-dir=htx-trend/research-data/20260329-20260929-v3 \
  --manifest=htx-trend/research-data/20260329-20260929-v3/dataset-manifest.json \
  --configuration=htx-trend/research-config/20260930-code-defaults.json \
  --contracts=BTC-USDT,ETH-USDT \
  --period=15min \
  --start=2026-09-18T00:00:00+08:00 \
  --end=2026-09-18T23:45:00+08:00 \
  --output=htx-trend/research-results/20260930-frozen-signal-replay/btc-eth-20260918-15m.jsonl
```

命令会同时生成 `*.summary.json`。JSONL 的第一行是输入、窗口与证据边界；其余每一行对应一根已完成 K 线，含生产 ensemble 信号、所有并行组合候选和未冻结外部 Gate 的明确标记。

全量 52 币运行前仍要完成性能基准和“优化后结果逐点等于当前生产计算器”的校验；未经该校验，不得为了加速改变指标公式或回放结果。

## BT-02A：组合 Gate 消融（K 线证据层）

`HtxTrendFrozenGateAblationCommand` 在同一冻结集上复用 Java 生产信号、组合候选计算器和当前的“反向大盘风险”判定。它对每个组合候选同时记录四条路径：

1. `COMBINATION_ONLY`：只看模型结构，用作候选覆盖基线；
2. `CURRENT_PRODUCTION_COMBINATION_GATE`：当前生产路径，仅在候选逆着广泛市场方向时拒绝；
3. `CURRENT_PLUS_MULTITIMEFRAME_DIRECTION`：研究实验，在当前路径外要求 30m、60m、4h 至少两周期同向且没有反向；
4. `COUNTER_MARKET_RISK_ONLY`：显式保留为对照。它当前应与第 2 条完全相同；若不相同，即代表生产 Gate 漂移。

该命令严格输出 `INSUFFICIENT_EVIDENCE`：公开 K 线不能重建历史盘口、深度、账户费率、保证金、订单、仓位、成交和滑点。因此结果只能回答“候选会被哪条可复现的 K 线 Gate 拦截”，不能输出交易数、PF、收益、回撤或修改实盘策略。

2026-09-18 的 52 币种验收运行产生 4,743 个原始组合候选：当前生产 Gate 拦截 39 个，多周期方向实验额外拦截 500 个。该差异只证明实验有足够影响值得进入完整同源成交回放，不证明实验更赚钱。

完整三个月运行使用单核公式基线约每交易日 26 秒，且会产生大量逐候选审计记录。先完成与基线逐点相等的窗口缓存/并行改造，再运行全量；不得为了速度改变指标算法或 K 线完成时刻。
