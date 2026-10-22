# P1 执行成本账

只读分析。从账本导出和成交计算每条订单腿的信号价、下单价、成交均价、滑点（bps）、手续费、部分成交次数、下单到首笔成交的延迟、撤单次数和改单次数。资金费按持仓期 `(首笔开仓成交, 末笔平仓成交]` 汇总，重复的 `event_key` 只计一次。

仅用于校准回测成本。不改变开单逻辑，不当作过闸证据，不回写已封存目标净效应。

正滑点表示成交比参考价更差：开多/平空是买入，成交高于参考价为正；开空/平多是卖出，成交低于参考价为正。往返成本 = 各腿下单价滑点 + 各腿手续费 bps。对比假设是 12 bps，与 `research/coinresearch/config.py` 的 `rt_cost` 默认值一致。资金费不进这 12 bps。

导出里没有信号价或下单价时，滑点留空，不用成交价回填。撤改次数缺字段时也留空，不写成 0。

## 运行

在 `research/` 目录：

```sh
python -m msa_p1_execution_cost --bundle msa_p1_execution_cost/fixtures/synthetic_bundle.json \
  --out msa_p1_execution_cost/samples/synthetic
python -m msa_p1_execution_cost \
  --ledger-pnl-dir ../htx-trend-python/tests/fixtures/ledger_pnl \
  --out msa_p1_execution_cost/samples/ledger_pnl
```

第二条只读 `ledger_pnl` 夹具（PAPER 成交导出），不修改该目录。该导出没有独立的信号价、下单价和下单时间，因此滑点与延迟为空，手续费和部分成交仍可计算。

## 本次新增文件

只新增 `research/msa_p1_execution_cost/`、`research/msa_p2_collector/` 以及 `research/tests/test_msa_p1_execution_cost.py`、`research/tests/test_msa_p2_collector.py`。没有修改 `htx-trend-python` 的判单、下单、止损、参数，也没有改部署、nginx、前端或 PRD。

## 进度记录 — 2026-10-22

### 本次目标

按口令完成 P1 执行成本账和 P2 只读采集器编码，不提交、不推送。

### 已完成

- P1 分析、夹具、真实 `ledger_pnl` 只读样例和 12 bps 对比表。
- P2 公开 REST 采集循环、限频、重连、缺口、幂等和 dry-run 样例。
- 默认币池用仓库内三个月研究宇宙（52 个合约，含 BTC、ETH）。口令中的 41 币名单不在仓库内，存储估算另按 43 个合约给出。

### 未完成 / 未做

- 不装机、不热更、不启动现网进程。CLI 不提供实盘轮询开关。
- 不把采集结果接入信号或 G0。
- 不回写历史 ledger，不改 PAPER 0.1.6 与 LIVE R0。

### 关键决策

- 缺价格保持空值，避免把 PAPER 成交价伪装成滑点。
- 默认币池跟已入库研究宇宙，不臆造 41 个名字；币池仍可配置，且必须含 BTC、ETH。

### 下一步

- 产品审阅本分支上的新增文件后再决定是否合入 `support_htx_python`。
- 若要换成外部 41 币清单，只改 `universe.yaml` 的 `contracts_file`。
