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
