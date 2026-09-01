# HTX moving-grid `HEDGE_DEGRADED` 处理 runbook

更新时间：2026-09-01

## 结论

`HEDGE_DEGRADED` 不是自动补单信号，而是“真实仓位和策略意图已经不能由本地状态机安全推断”的人工介入信号。处理原则是先查交易所真相，再让 planner 基于真实仓位重算；在人工确认前，策略只能对账、减风险，不允许新增空头或扩大现货库存。

## 触发条件

- 合约 hedge intent 已提交到交易所，但返回部分成交、撤单、拒绝或终态未满成。
- 库存回收 close-short 已提交到交易所，但返回部分成交、撤单、拒绝或终态未满成。
- SENT 行缺少 exchange order id 和 client order id，无法唯一定位交易所订单。
- 对账查询持续异常，行被置为 `UNKNOWN`，与 `HEDGE_DEGRADED` 一样阻断自动发送。

## 处置步骤

第一步：冻结新增风险。

- 保持 `HTX_CONTRACT_HEDGE_SUPERVISOR_SHADOW_ONLY=true`，不得打开 `HTX_CONTRACT_HEDGE_AUTO_SEND_ENABLED`。
- 如已进入实盘灰度，关闭扩大风险的业务开关，仅保留撤单、reduce-only 平空和对账能力。
- 验收：影子监督器日志出现 `MANUAL_INTERVENTION_REQUIRED` 时，没有新的 hedge/recycle pending intent 被发送。

第二步：以交易所为准确认真实状态。

- 优先用 `exchange_order_id` 查询 HTX `/v5/trade/order`。
- 缺少 `exchange_order_id` 时，用本地 `client_order_id` 查询。
- 同时只读核对合约当前 short 仓位、现货库存账本和最新余额；不要仅凭 DB status 判断仓位。
- 验收：能得到订单终态、成交张数、均价、手续费、收益，以及当前真实 short contracts。

第三步：选择最小风险动作。

- 如果真实 short 小于 planner 目标：先确认 funding、basis、margin、liquidation 四门均通过；未通过则不补空。
- 如果真实 short 大于 planner 目标：只生成 `buy/short/reduceOnly=true` 平空。
- 如果库存回收 close-short 部分成交：按真实剩余 short 与真实现货库存重算，不沿用旧订单残量直接补单。
- 验收：任何补偿动作都来自新的 planner 输入快照，而不是来自 degraded 行的旧预期。

第四步：恢复策略。

- 人工确认账本和交易所一致后，再把策略从风险暂停态恢复。
- 影子盘至少连续观察 14 天，dry-run 连续 3 天后，才允许进入小额实盘阶段。
- 验收：`HEDGE_DEGRADED` / `UNKNOWN` 行数归零或都有人工处置备注；自动发送开关仍按阶段保持关闭。

## 开关矩阵

| 场景 | `hedge-supervisor-enabled` | `hedge-supervisor-shadow-only` | `hedge-auto-send-enabled` | `dry-run` | `live-enabled` | 结果 |
|---|---:|---:|---:|---:|---:|---|
| 默认 | false | true | false | true | false | 不加载监督器 |
| 影子盘 | true | true | false | true | false | 只对账 SENT，报告 degraded/unknown，不发送 pending |
| dry-run 调度演练 | true | false | false | true | false | 只对账，pending 保持不动 |
| 实盘发送预备但未授权 | true | false | true | true | false | live gate closed，pending 保持不动 |
| 实盘自动发送 | true | false | true | false | true | 仅在无 degraded/unknown 时发送 pending；需单独生产授权 |

## 红线

- 不做“一格一空”，只维护每 symbol 一笔逻辑聚合空头。
- 不在 `UNKNOWN` 或 `HEDGE_DEGRADED` 存在时自动发新合约单。
- 不用本地预期成交量推导真实仓位。
- 所有平空必须 `reduceOnly=true`。
- 不因为价格跌破网格下界而停止策略；移动网格可以下移，但下移过程的 Delta 风险必须受上述门禁约束。
