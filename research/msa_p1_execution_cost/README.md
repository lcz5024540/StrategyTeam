# P1 执行成本账

只读分析。从账本导出计算每条订单腿的信号价、下单价、成交均价、滑点（bps）、手续费、部分成交、下单到首笔成交的延迟、撤单次数和改单次数。资金费按持仓期 `(首笔开仓成交, 末笔平仓成交]` 汇总，重复的 `event_key` 只计一次。

仅用于校准回测成本。不改变开单逻辑，不当作过闸证据，不回写已封存目标净效应。

正滑点表示更差，方向与原来的成交相对下单价一致：开多或平空是买入，后面的价格更高为正；开空或平多是卖出，后面的价格更低为正。价格用 `Decimal`，bps 乘 10000。

## 逐笔下单价（MS-A-P1-ORDERPX v1.1）

每条有成交的订单腿输出 `orderpx.csv` 和 `orderpx.jsonl`。

| 字段 | 含义 |
| --- | --- |
| `signal_px` | 信号价 |
| `order_px` / `order_px_source` | 下单价。`limit` 是委托价；市价单用下单时的 `mid` 或 `last` |
| `fill_vwap` | 成交均价 |
| `fill_qty` / `order_qty` / `partial_fill_ratio` | 成交量、委托量、成交量除以委托量 |
| `signal_ts` → `order_ts` → `first_fill_ts` → `full_fill_ts` | 四段时间。相邻差值是 `latency_signal_to_order_ms`、`latency_order_to_first_fill_ms`、`latency_first_to_full_fill_ms` |
| `cancel_count` / `amend_count` | 撤单次数、改单次数 |
| `fee_usdt` / `fee_bps` | 手续费 |
| `funding_paid_usdt` / `funding_received_usdt` | 该持仓窗口内的资金费实付、实收。同一持仓的每条腿都带同一笔数，加总时按 `position_id` 去重 |

`full_fill_ts` 只在委托量已知且成交量达到委托量时填写。部分成交留下 `missing_full_fill_ts`。旧字段 `partial_fill_count` 仍是“多出来的成交笔数”，和 `partial_fill_ratio` 不是同一个数。

滑点拆成三段，单位 bps，正值表示更差：

- S1：信号价 → 下单价
- S2：下单价 → 成交均价
- S3：成交均价相对下一根 bar 开盘价 `next_bar_open`

实际总成本 `total_cost_bps` = S3 + 该腿手续费 bps。汇总里的 `assumed_rt_bps` 是研究配置 `rt_cost` 的 12。差值用这个单腿成本去减 12。S1、S2 和资金费不进这列。`next_bar_open` 不进交易库，缺失就标 `missing_next_bar_open`。

字段缺失时写入 `missing_flags`（例如 `missing_order_px`）。对应数值留空。不会用信号价或成交价填下单价，也不会把缺失的撤改次数写成 0。导出里没有资金费事件时标 `missing_funding`，不把 0 当成实测。

`orderpx_summary.csv` 按账本（LIVE / PAPER）和币种分组，并带一行该账本的合计（`contract` 为 `*`）。每组都给出 S1、S2、S3 和总成本的分位数。均值和 95% 区间只在同时满足两点时出现：可计算总成本的成交腿不少于 200，且至少两个币种簇。区间用聚类稳健标准误 CR1，簇是 `contract`，临界值是正态近似 z=1.96：

```text
se = sqrt( (G/(G-1)) * Σ_g (Σ_i in g (y_i - ȳ))^2 / N^2 )
```

簇很少时这条正态近似偏窄，仍然只是成本校准，不是过闸证据。不足 200 笔只留分位数，`conclusion` 为「样本不足不下结论」。只有一个币种时标「单簇无法估计聚类标准误」，同样不给均值和区间。

若以后要落库，见 [docs/migration_draft_orderpx.md](docs/migration_draft_orderpx.md)。那份 SQL 草案状态是未执行。

## 运行

在 `research/` 目录：

```sh
python -m msa_p1_execution_cost --bundle msa_p1_execution_cost/fixtures/synthetic_bundle.json \
  --out msa_p1_execution_cost/samples/synthetic
python -m msa_p1_execution_cost \
  --ledger-pnl-dir ../htx-trend-python/tests/fixtures/ledger_pnl \
  --out msa_p1_execution_cost/samples/ledger_pnl
```

第二条只读仓库里已经提交的 PAPER 夹具 `htx-trend-python/tests/fixtures/ledger_pnl`。它不是这次任务新抓的实盘或模拟盘导出，目录里的文件不会被改写。该夹具没有信号价、下单价、下单时间、委托量、下一根 bar 开盘价和资金费事件，所以这些字段都是缺失标记。手续费、成交均价和部分成交次数仍可计算。有 7 条腿的合约不在 `trades_closed_window.csv` 里，汇总中币种留空。两边样本的成交腿都远少于 200，汇总标「样本不足不下结论」。

合成夹具在原有 A–D 之外加了两笔：E 是市价单，下单价来自 `mid`，只成交一半；F 只有信号价和成交价，用来展示缺失标记。A–D 的已完成往返仍是原来的三笔。

## 旧口径

往返对比表 `comparison.csv` 仍是各腿「下单价滑点 + 手续费 bps」的往返合计，对照 12 bps。资金费不进这张表。它和上面的 S3 总成本不是同一列。

## 进度记录 — 2026-10-22

### 本次目标

按口令 MS-A-P1-ORDERPX v1.1 扩展 P1 成本账的逐笔下单价，不提交、不推送、不执行迁移。

### 已完成

- 逐笔 CSV / JSONL、三段滑点、缺失标记、按 LIVE/PAPER 与币种的分位数，以及不少于 200 笔且至少两簇时的 CR1 区间。
- 合成夹具与 `ledger_pnl` 样例。`ledger_pnl` 使用仓库内已有 PAPER 夹具。
- 未执行的迁移草案：`docs/migration_draft_orderpx.md`。

### 未完成 / 未做

- 不改 `htx-trend-python` 的下单、风控和表结构。
- 不回写历史账，不在 PAPER / LIVE 执行 SQL。
- 样本不足 200 笔，不对真实夹具下成本结论。
