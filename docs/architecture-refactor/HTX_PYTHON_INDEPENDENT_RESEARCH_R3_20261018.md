# HTX Python R3：独立数据与结构性假设预注册

## 1. 结论

R3 已启动并完成 R0–R1。R2 已以真实 `REJECT` 终态归档；R2 末尾 TEST 保持
`NOT_READ`。R3 冻结了两个不含参数搜索网格的结构性假设，并从 HTX 公共接口采集
2026-10-11 00:00～2026-10-18 07:00 UTC 的 BTC/ETH 五周期行情、合约规格和真实
资金费事件。

首个独立数据区间完整，但只有 7 天。15m 要求至少 60 天，4h 要求至少 180 天，
所以当前终态是 `ACCRUING / INSUFFICIENT_EVIDENCE`。两个冻结条件及研究入口已经实现，
但 R4/R5 在时间门禁处停止，没有读取候选、收益或交易结果；这防止研究人员在积累期
根据早期结果修改假设。

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
| K 线 manifest | `4e192231f24420cd8ce7f3c2d47c241670363c2cba9ba47e2453d22fba908d96`，COMPLETE |
| 市场事实 manifest | `346a3d4ee844cea534a2a9fdea68f55b82b9f95bce0782f2aa8be77d3cfc747c`，COMPLETE |
| 数据就绪审计 | `8d78eb59dc71b2e9c55b02f964347b89f76e6b407f5cc5667a381dc87505890c`，50 对象 VERIFIED |

每个币种实际冻结：15m 700 根、30m 350 根、60m 175 根、4h 43 根、1d 6 根。
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
  --start 2026-10-11T00:00:00Z --end 2026-10-18T07:00:00Z

.venv/bin/htx-trend collect-market-facts \
  --evidence research-data/r3-independent-accrual \
  --symbols BTC-USDT ETH-USDT \
  --start 2026-10-11T00:00:00Z --end 2026-10-18T07:00:00Z

.venv/bin/htx-trend preregister-independent-round \
  --evidence research-data/r3-independent-accrual

.venv/bin/htx-trend audit-independent-data \
  --evidence research-data/r3-independent-accrual \
  --plan-hash 2e728a7f73412ed71e6099fe6ef5a4b85f6f70bb95da2c9dc0b960eaa6b065ee \
  --manifest-hash 4e192231f24420cd8ce7f3c2d47c241670363c2cba9ba47e2453d22fba908d96 \
  --market-facts-manifest-hash 346a3d4ee844cea534a2a9fdea68f55b82b9f95bce0782f2aa8be77d3cfc747c

.venv/bin/htx-trend independent-profile-study \
  --evidence research-data/r3-independent-accrual \
  --readiness-hash 8d78eb59dc71b2e9c55b02f964347b89f76e6b407f5cc5667a381dc87505890c \
  --profile INTRADAY_15M

.venv/bin/htx-trend independent-profile-study \
  --evidence research-data/r3-independent-accrual \
  --readiness-hash 8d78eb59dc71b2e9c55b02f964347b89f76e6b407f5cc5667a381dc87505890c \
  --profile SWING_4H
```

## 5. 当前边界

当前完成预注册、首批数据积累、两个条件实现和 R4/R5 fail-closed 入口，没有策略盈利
结论。R3 不修改正式策略、V4、Java、SQL、部署、PAPER、LIVE 或 admission。下一项仍是
持续追加未来闭合 K 线和真实资金费；时间门槛到达前不查看早期收益，也不进入 P7/P8。

## 6. 验证

- Python 全量测试：252 passed；
- `compileall`、CLI help 和 wheel 构建通过；
- readiness evidence 闭包 50 对象 `VERIFIED`；
- draw.io XML 与 `git diff --check` 通过；
- 真实审计发现并修复日线非零周期锚点的期望根数错误，回归测试覆盖 1day 锚点。

## 7. 持续采集

已创建线程 heartbeat `htx-r3`，每天本地时间 00:20 执行同一采集和审计调用链。
任务固定使用 2026-10-11 作为起点，并把结束时间推进到运行时最近完整结束的 UTC
小时。状态保持 `ACCRUING` 且无异常时不发送重复消息；以下情况才通知：

- 采集失败、manifest 不完整或证据闭包校验失败；
- 15m 达到 60 天或 4h 达到 180 天；
- 任一 profile 进入 `READY_FOR_BASELINE_SAMPLE_AUDIT`；
- 需要用户处理外部状态。

自动任务只写 Git 忽略的本地 evidence，不提交、不 push，也不运行策略收益或条件实验。

## 8. R4/R5 工程准备与首次门禁结果

15m 条件使用显式 pending-breakout 状态机。突破 K 线只冻结方向、Donchian 边界、失效位、
目标和结构标识；只检查随后第 1～3 根闭合 K 线。K 线先触及失效位则状态为
`INVALIDATED`，超过三根为 `EXPIRED`；触及边界且重新收在突破方向时才在该闭合 K 线上
产生候选，实际成交仍交给生命周期引擎在下一根开盘处理。

4h 条件只读取信号收盘时已经完整闭合的日线。最近三根日线分别用各自当时的历史前缀
计算 EMA20/60/120、OLS close slope20、ROC12 和 ADX14，三根都同向且 ADX14≥16 才
`PASS`。至少需要 123 根闭合日线；缺少预热返回 `UNKNOWN`，不能解释成通过。

`independent-profile-study` 依次检查：readiness 类型和证据闭包、TEST 未读、策略结果未读、
profile 时间覆盖、开发段基线完整交易≥30，之后才运行单条件四成本生命周期比较。正式
条件比较仍只使用 TRAIN/VALIDATION，TEST 保持 `NOT_READ`。满足经济性、置换、bootstrap
和五窗口标准时才输出 P7 nominee。

| 阶段 | 结果 hash | 闭包 | 当前结果 |
|---|---|---:|---|
| R4 / 15m | `606c573f5201e17f35ed9590257afa35117946c1a604d6ff5054d89733832734` | 52 VERIFIED | 7/60 天，`INSUFFICIENT_EVIDENCE` |
| R5 / 4h | `eef21df4fd5fc2ef1c23f2ec90fc524300ed1c4ad637895de2b1a68a32d3f1b9` | 52 VERIFIED | 7/180 天，`INSUFFICIENT_EVIDENCE` |

两个结果都明确记录 `strategy_outcomes_read=false`、TEST=`NOT_READ`、
`reason=DATA_TIME_GATE_NOT_READY` 和 `p7_nominee=null`。这表示 R4/R5 已启动并完成工程接线，
实际生命周期实验尚未达到允许运行的外部时间条件。
