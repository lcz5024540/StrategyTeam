# HTX Python 双周期 V2 渐进改造实现记录

## 1. 本轮边界

基线为 `support_htx_python@86529039`。本轮只修改 Python、Python 专用迁移、测试和文档；未执行生产 SQL、未部署、未启用真实订单，也未修改 V1 入场公式或 V4 退出语义。

真实 Shadow 需要连续 2–3 个自然日，PAPER 每个 profile 至少需要连续 14 个自然日。因此当前工程结论是 `INSUFFICIENT_EVIDENCE`，不能用历史回放替代这两段证据。

## 2. 已接通的调用链

```text
strategy_runtime_instance
  -> LegacyLiveWaiver 或 LiveApproval（包/提交/配置/账户/profile/准入/V4）
  -> encrypted HTX credentials + writer lock
  -> profile runtime
       HTX_TREND_V1: INTRADAY_15M + SWING_4H（过渡 waiver）
       HTX_TREND_15M_V2: 仅 INTRADAY_15M
       HTX_TREND_4H_V2: 仅 SWING_4H
  -> 闭合 K 线窗口
       15m: trigger=15m, trend=60m, context=30m/60m/4h/1d
       4h:  trigger=4h,  trend=1d,  context=1d
  -> RegimeSnapshot（EMA 排列/价格与 EMA 斜率/ADX/ROC/ATR/量比/盘口）
  -> HtxStrategyDecisionEngine（profile 专属参数 + spec/feature/condition hash）
  -> 候选与条件证据（strategy_version/timeframe_profile/spec_hash）
  -> policy selection
  -> 账户与交易所 preflight
  -> 交易所确认权益下的 0.5%/1.5%/3 风险门禁
  -> 原子风险预占
  -> 订单意图/HTX 提交/回查/部分成交账本
  -> profile 所属持仓管理器
  -> 仅 V4 正式退出
```

任何 V2 必需高周期缺失、未闭合或证据落盘失败，均返回证据不可用并停止该 profile 新增风险。已有仓位仍由 V4 和订单对账管理。

## 3. 独立规格与研究能力

- `HTX_TREND_15M_V2` 与 `HTX_TREND_4H_V2` 各自生成内容寻址的 `ProfileStrategySpec`。
- 参数只从各自 `HTX_TREND_15M_*` / `HTX_TREND_4H_*` 命名空间读取，不跨 profile 回退。
- Donchian 42/84/126/168 仅作为 4h 研究变体登记；VWAP、RSI、MACD、价格斜率和 EMA 斜率仍为记录或逐项实验条件。
- 生命周期模拟明确标记 `STRATEGY_SIMULATION`，使用下一根 K 线、方向性滑点、费用、资金费、1% K 线成交量容量、限价真实穿越、部分成交、跳空止损和同根不利顺序。
- 研究门槛输出 `PASS`、`PASS_PROFITABILITY_FREQUENCY_GAP`、`REJECT` 或 `INSUFFICIENT_EVIDENCE`；先检查经济性，再检查双 profile 合计每日 5 笔完整闭环交易。
- 60/20/20、walk-forward、固定种子置换检验、时间块 bootstrap 和 test 数据集单次消费均由研究模块提供。

## 4. LIVE 约束

legacy waiver 最长 45 天且不可篡改，绑定 source commit、package SHA、配置指纹、账户、两个 profile、风险策略和 V4。到期或任一摘要不匹配时 worker 在获得交易执行权之前退出。

V2 `LiveApproval` 额外绑定独立策略版本、profile、spec hash 和 sealed admission hash。15m V2 不能持有 4h profile，4h V2 不能读取 15m 参数。运行配置的风险百分比或持仓数只允许等于或低于批准边界。

```bash
htx-trend validate-live-waiver --artifact /secure/legacy-live-waiver.json
htx-trend validate-admission --artifact /secure/15m-v2-admission.json
htx-trend validate-live-approval --artifact /secure/15m-v2-live-approval.json
```

凭据必须在数据库中以 `ENC(...)` 保存。LIVE 不接受明文 API key；首次切换仍只允许 PRIMARY，镜像账户不能进入本次 formal guard。

## 5. 数据库迁移

部署包前由运维显式执行，worker 不执行 DDL：

```text
sql/2026-10-17__strategy_risk_reservation_stop_risk.sql
sql/2026-10-17__candidate_strategy_identity.sql
```

第一份给风险预占增加止损风险金额；第二份给候选增加策略版本与 spec hash。两者用于账户级原子 1.5% 风险限制和双 profile 追溯。

## 6. 当前验收状态

工程测试覆盖 profile 参数隔离、waiver/approval 篡改和到期、闭合多周期证据、未来 K 线隔离、next-bar 成交、容量/部分成交、同根不利顺序、成本、统计结论，以及既有 V1 回归。

尚需在独立批次完成：

1. 生成并审查真实 waiver/approval/admission 文件；
2. 在测试数据库执行两份迁移并验证回滚；
3. 双 profile Shadow 连续运行 2–3 天；
4. 各 profile 独立 PAPER 至少 14 天；
5. 用真实结果生成十二阶段报告，再决定是否逐 profile 替换 legacy。
