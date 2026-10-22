# 账本与数据库盈亏唯一权威口径

## 定义与修复

唯一权威指标是以成交（fills）为事实源的已实现、费后盈亏，包含已平数量分摊的开仓费与实际平仓费，不包含浮动盈亏。已确认资金费按有符号成本扣除（收入为负成本），必须确认导出覆盖完整。没有数据不能当成零。

当前一次开仓后分批平仓模型：开仓均价为成交量加权均价；平仓毛利为每笔 `(平仓成交价 − 开仓均价) × 平仓数量 × 合约面值 × 方向`，多头方向为 1、空头为 -1。分摊开仓费为 `开仓成交总费用 × 本次平仓数量 / 总开仓数量`。净额为毛利减分摊开仓费、实际平仓费和资金费。成交价已含成交滑点，不重复扣滑点。全程使用 Decimal。

未平数量的开仓费不提前计入已实现。DONE 全量平仓净额应与 final_net_profit 一致；ACTIVE 部分平仓可以有已实现净额，但 DB final_net 可能为空。

PAPER state 是成交事实来源，但旧 positions.realized_pnl 与旧 ledger CLOSE_FILL 净额只扣平仓费，不能直接作为上述权威指标。原证据 PNL.md 中“ledger CLOSE 净为权威”的描述不再适用。本次只修正 paper_db_persist 的记账投影，不改变 state 交易执行逻辑。

新写 CLOSE_FILL 净额扣除本次平仓费及按数量分摊的开仓费；fee_usdt 仍只记录该笔成交费用，OPEN_FILL 费用不会重复计入费用汇总。事件键和幂等重放规则不变。LIVE order_accounting 累计快照语义此次未修改。

## PAPER 归因（2026-10-21 09:29 CST，13 个 DONE）

| 项目 | USDT | 解释 |
|---|---:|---|
| 成交毛利 | -3.53820000 | ledger 与 DB 一致 |
| 平仓费 | 0.69573500 | 旧 CLOSE_FILL 已扣 |
| 旧 ledger 净 | -4.23393500 | 毛利减平仓费 |
| 全部 OPEN_FILL 费用 | 0.70467988 | 包含 ACTIVE |
| ACTIVE 9877 开仓费 | 0.00987896 | 不计入已实现 |
| 已平仓开仓费 | 0.69480092 | 旧 ledger 漏扣差额 |
| 双边费用 | 1.39053592 | 开仓费加平仓费 |
| fills 权威净 / DB final_net | -4.92873592 | 完全一致 |
| 资金费 / 四舍五入差额 | 0 / 0 | 本证据无贡献 |

### 窗口与快照修正

窗口起点为 2026-10-19 21:35:46 CST。必须使用 state 实际成交/平仓时间，不能使用 DB 回填创建时间。

| 旧窗仓位 | 实际平仓 CST | 权威净 USDT |
|---|---|---:|
| 325 | 2026-10-19 17:00:48 | -0.23881580 |
| 327 | 2026-10-19 18:00:11 | -0.20151556 |
| 329 | 2026-10-19 18:00:11 | -0.27202384 |
| 合计 | 3 笔 | -0.71235520 |

09:29 汇总是 13 笔，其中窗口内 10 笔。较新的 state、DB 明细和 CSV 增加仓位 20186（10 月 21 日 10:30:19 CST 平仓，净额 -0.56749416），所以最新已平 14 笔，窗口内 11 笔。不是“13 减旧窗 2 笔”。窗口 11 笔 fills 净与 DB final_net 逐笔差为零；CSV Decimal 尾差小于 0.00000001。

旧窗属于范围差异，不能再次加到漏扣开仓费的差额中。

## LIVE 汇总归因

差额方向为 ledger 减 DB，使用证据中 8 位汇总。

| 项目 | ledger | DB DONE | 差额 |
|---|---:|---:|---:|
| 毛利 | -76.77916400 | -56.07089700 | -20.70826700 |
| 费用成本 | 18.25261413 | 35.74181429 | -17.48920016 |
| 净额 | -95.03177813 | -91.81271129 | -3.21906684 |

净差 = 毛差 − 费差。LIVE ledger 由 REALIZED_PNL 和独立 FEE 事件组成，不能只求 CLOSE_FILL 净。现有证据只有汇总，无法断言毛差属于漏单、旧窗、重复事件或仓位集合差异，也不能把费差拆成开仓和平仓费。这是恒等式验证，不是逐仓归因；缺少 fills 时无权威重算结论。

## 离线工具

Python 3.11 以上，在 htx-trend-python 下运行。JSON 输出 stdout，Markdown 输出 stderr；工具不连接数据库、不写输入文件。输出请重定向到新文件。

```sh
PYTHONPATH=src python -m htx_trend.tools.ledger_db_recon \
  --positions ../.codex-evidence/t1pack/db_positions.txt \
  --ledger ../.codex-evidence/t1pack/db_ledger.txt \
  --state ../.codex-evidence/t1pack/state.json \
  --window-start 2026-10-19T21:35:46+08:00 \
  --as-of 2026-10-21T09:29:00+08:00 --funding-complete \
  > /tmp/ledger-recon.json 2> /tmp/ledger-recon.md
```

去掉 --as-of 可验证较新完整明细。该参数按仓位最后平仓时间筛选，用于此次已完成仓位快照复现，不是通用历史时点重建；不得将后续仍有追加成交或资金费的仓位当作早期完整快照。

单独使用 `--aggregate ../.codex-evidence/t1pack/03-mysql-pnl-agg.txt` 可读取 MySQL 多段汇总，输出汇总恒等式和缺数据声明。

逐仓输入支持 CSV、JSON 行数组、MySQL 制表符或边框单表。输入必须限定同一账户、策略、截止时间；不同账户分别运行。

| 输入 | 必需字段 |
|---|---|
| positions | id、position_key、status、final_net_profit |
| ledger | position_id、event_type、net_pnl_after_fee；资金费用 funding_fee_usdt 表示有符号成本 |
| fills（替代 state） | position_id、fill_id、leg、quantity、price、fee、filled_at、side、contract_size |

leg 使用 ENTRY / CLOSE / PARTIAL_CLOSE；时间含时区；fee 是 USDT 成本，返佣为负。不要把累计订单快照直接当作独立成交。重复成交、缺少成交、超量平仓、开平仓交错会标注证据不足。当前成本模型不支持加仓与平仓交错，需先补充逐时成本分配。

--funding-complete 是调用方对资金费导出完整性的明确声明，完整导出无资金费行才可视为零；未声明时不输出已确认权威净。独立 FEE 与 REALIZED_PNL 的账本净额求和，成交侧费用单独重算，不重复扣费。汇总仅包含成功对账仓位，必须同时查看证据不足数量。费用分类按差额匹配，不能匹配或匹配多种原因时标注未解释，不替代原始事件审计。

## 历史数据处理

历史 ledger 不 UPDATE、不批量重写、不 ALTER。跨修复版本原始净额可能混合新旧口径；历史报表必须从 fills 重算，不能无条件在原净额上再减一次开仓费。此次不含部署或历史回填，离线测试通过不代表生产已部署。

## LIVE 需补充的只读导出

- 完整仓位集合（DONE、RECONCILED_CLOSED、ACTIVE）：主键、position_key、账户、策略、方向、面值、成交数量、entry/close_fee、final_gross/total_fee/net、盈亏来源和实际开平时间。
- 逐订单与交易所确认的逐成交：仓位关联、订单标识、成交唯一标识、开平类型、价格、数量、手续费币种及 USDT 金额、实际时间；累计快照需去重转换为增量。
- 所有类型逐账本事件：event_key、event_type、账户、策略、position_id/order_id、gross_pnl_usdt、fee_usdt、funding_fee_usdt、net_pnl_after_fee、occurred_at；保留独立 FEE、REALIZED_PNL、修正与资金费事件。
- 完整资金流水、仓位分摊依据和覆盖起止时间；非 USDT 手续费需可审计的成交时换算依据。
- 导出过滤条件、时区、快照时间和行数；无法关联的账本事件单列，不强行归仓；不导出凭据。

## LIVE 平仓拆事件与残缺告警（LIVE-CLOSE）

LIVE 重叠仓以拆事件记账：CLOSE_FILL（周转）+ FEE（平仓费）+ REALIZED_PNL（毛）。Python `order_accounting` 对新平仓按此写全；若 CLOSE 已 FILLED 仍缺三件套，日志告警码 `LIVE_CLOSE_LEDGER_INCOMPLETE`。离线补写仅 INSERT 缺事件、不改历史行；合入≠装机≠现网补写。禁止为对齐汇总差把 PAPER CLOSE_FILL 开仓费公式热更 LIVE。
