# HTX Python R3：独立数据与结构性假设预注册

## 1. 结论

R3 已启动并完成 R0–R1。R2 已以真实 `REJECT` 终态归档；R2 末尾 TEST 保持
`NOT_READ`。R3 冻结了两个不含参数搜索网格的结构性假设，并从 HTX 公共接口采集
2026-10-11～2026-10-18 的 BTC/ETH 五周期行情、合约规格和真实资金费事件。

首个独立数据区间完整，但只有 7 天。15m 要求至少 60 天，4h 要求至少 180 天，
所以当前终态是 `ACCRUING / INSUFFICIENT_EVIDENCE`。审计器没有读取候选、收益或
交易结果，也没有实现或运行新条件；这防止研究人员在积累期根据早期结果修改假设。

## 2. 冻结假设

| ID | Profile / 模型 | 公式 | 可调参数 |
|---|---|---|---|
| `R3_15M_DONCHIAN_RETEST_RECLAIM` | 15m / Donchian | 闭合突破后，仅当随后 1–3 根闭合 15m K 线触及冻结突破边界、未穿越冻结失效位并重新收于突破方向一侧时产生入场；仍在下一根开盘执行 | 无 |
| `R3_4H_1D_TREND_PERSISTENCE_3` | 4h / EMA/VWAP | 最近三根完全闭合的 1d K 线必须逐根满足方向一致的 EMA20/60/120、OLS 价格斜率、ROC12 和 ADX14≥16 | 无 |

第一个假设针对 R2 Donchian 在局部区间改善但假突破不稳定的问题，改变的是事件顺序，
不是评分阈值。第二个假设针对 4h EMA/VWAP 跨窗口不稳定，检查日线趋势是否持续，
不改入场阈值或 V4 退出。Fibonacci、RSI、MACD 和新的参数网格均未纳入本轮。

## 3. 首个独立数据证据

| 对象 | 内容 hash / 结果 |
|---|---|
| R3 预注册计划 | `2e728a7f73412ed71e6099fe6ef5a4b85f6f70bb95da2c9dc0b960eaa6b065ee` |
| K 线 manifest | `26bd5dbd50a6e1013d1e43d022cb87ed1fffb9375bafee48fcff13007261dc2e`，COMPLETE |
| 市场事实 manifest | `522d48bd2745863d7c4104217a44f8c593cbe1a09a77cd62eb9e1a2797323b62`，COMPLETE |
| 数据就绪审计 | `172e108364251f07aa0a28a3ed57136e9984b2c8dda99d8b9babb93dd8c74832`，50 对象 VERIFIED |

每个币种实际冻结：15m 672 根、30m 336 根、60m 168 根、4h 42 根、1d 6 根。
1d 根数按 HTX 的日线锚点计算，不使用简单的区间秒数除以 86,400。

| Profile | 已观察 | 最低时间门槛 | 完整交易样本 | 当前状态 | 最早时间门槛日期 |
|---|---:|---:|---|---|---|
| 15m | 7 天 | 60 天 | `NOT_EVALUATED_DATA_TIME_GATE` | `INSUFFICIENT_EVIDENCE` | 2026-12-10 00:00 UTC |
| 4h | 7 天 | 180 天 | `NOT_EVALUATED_DATA_TIME_GATE` | `INSUFFICIENT_EVIDENCE` | 2027-04-09 00:00 UTC |

时间门槛达到后，才允许运行基线完整交易样本审计；每个 profile 仍须至少 30 笔完整
交易。达到时间不代表自动通过研究门槛。

## 4. 调用与门禁

```text
R2 P7 REJECT + TEST NOT_READ
  → 冻结 R3 cutoff 与两个假设
  → 独立采集行情和真实市场事实
  → COMPLETE、周期锚点、连续性、symbols/periods/区间一致性审计
  → 时间覆盖是否达到 60d / 180d？
      否：ACCRUING / INSUFFICIENT_EVIDENCE；不读取策略结果
      是：再审计每个 profile 是否有 ≥30 笔基线完整交易
  → 单条件配对消融
  → 四成本、五窗口、置换、block bootstrap
  → 只有通过者才能成为新的 P7 nominee
```

流程图见
[`HTX_PYTHON_INDEPENDENT_RESEARCH_R3_20261018.drawio`](HTX_PYTHON_INDEPENDENT_RESEARCH_R3_20261018.drawio)。

```bash
cd htx-trend-python

.venv/bin/htx-trend collect-history \
  --evidence research-data/r3-independent-accrual \
  --symbols BTC-USDT ETH-USDT \
  --periods 15min 30min 60min 4hour 1day \
  --start 2026-10-11T00:00:00Z --end 2026-10-18T00:00:00Z

.venv/bin/htx-trend collect-market-facts \
  --evidence research-data/r3-independent-accrual \
  --symbols BTC-USDT ETH-USDT \
  --start 2026-10-11T00:00:00Z --end 2026-10-18T00:00:00Z

.venv/bin/htx-trend preregister-independent-round \
  --evidence research-data/r3-independent-accrual

.venv/bin/htx-trend audit-independent-data \
  --evidence research-data/r3-independent-accrual \
  --plan-hash 2e728a7f73412ed71e6099fe6ef5a4b85f6f70bb95da2c9dc0b960eaa6b065ee \
  --manifest-hash 26bd5dbd50a6e1013d1e43d022cb87ed1fffb9375bafee48fcff13007261dc2e \
  --market-facts-manifest-hash 522d48bd2745863d7c4104217a44f8c593cbe1a09a77cd62eb9e1a2797323b62
```

## 5. 当前边界

当前只完成预注册和首批数据积累，没有策略盈利结论。R3 不修改正式策略、V4、Java、
SQL、部署、PAPER、LIVE 或 admission。下一项是持续追加未来闭合 K 线和真实资金费；
在时间门槛到达前，不实现条件、不查看早期收益，也不进入 P7/P8。

## 6. 验证

- Python 全量测试：243 passed；
- `compileall`、CLI help 和 wheel 构建通过；
- readiness evidence 闭包 50 对象 `VERIFIED`；
- draw.io XML 与 `git diff --check` 通过；
- 真实审计发现并修复日线非零周期锚点的期望根数错误，回归测试覆盖 1day 锚点。
