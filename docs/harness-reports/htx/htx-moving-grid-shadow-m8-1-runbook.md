# HTX moving-grid M8.1 影子盘第 1-3 天 runbook

更新时间：2026-09-01

关联提交：`9674408e feat(htx): add hedge shadow observability`

关联图：`htx-moving-grid-shadow-m8-1-gates.drawio`

## 结论

M8.1 的目标不是让策略自动交易，而是在真实行情和真实账本旁路下验证“双向移动网格 + 聚合 Delta 空头 + 配对库存回收”的计算结果是否稳定。第 1-3 天只能开启 hedge supervisor 的影子观察能力，必须保持 `shadow-only=true`、`auto-send=false`、`dry-run=true`、`live-enabled=false`；任何真实订单、生产配置变更、服务重启或放量都需要单独人工授权。

## 本阶段要证明什么

1. 价格上破或下破网格边界时，策略仍保持运行，窗口按整数网格步长迁移，不出现 `STOPPED` 语义。
2. 下跌过程中，`targetShort` 只作为“应对冲目标”被计算和记录；只有 funding、basis、margin、liquidation、仓位所有权等门全部安全时，后续阶段才允许考虑真实开空。
3. 每个 symbol 仍只有一笔逻辑聚合 Delta 空头，不做“一格一空”；所有平空和库存回收 close-short 意图必须是 `buy/short/reduceOnly=true`。
4. `HEDGE_DEGRADED` / `UNKNOWN` 是人工介入信号，不是自动补单信号；存在阻断行时，pending intent 不得被自动发送。

## M8.1 前置门

| 门 | 必须满足 | 验收口径 |
|---|---|---|
| 代码门 | 目标部署包来自 `support_htx` 且包含 `9674408e` | 部署前记录 commit hash；若不是该提交或其后继提交，停止 |
| 回归门 | `mvn -pl coin-strategy-htx -am test` 通过 | 交付记录保留测试输出；若失败，先回到开发修复 |
| 配置门 | 只允许打开 supervisor，不打开自动发送 | 见下方“允许配置” |
| 生产门 | 已获得明确 G0 授权 | 未授权时只准备材料，不 push、不部署、不改配置、不重启 |
| 责任门 | 当日观察人、风险确认人、回滚联系人明确 | 任一缺失则不进入观察窗口 |

## 允许配置

M8.1 只允许以下组合：

| 配置项 | 目标值 | 原因 |
|---|---:|---|
| `HTX_CONTRACT_HEDGE_SUPERVISOR_ENABLED` | `true` | 加载影子监督器，周期性输出 `ShadowReport` |
| `HTX_CONTRACT_HEDGE_SUPERVISOR_SHADOW_ONLY` | `true` | 只对账 `SENT` 行，不发送 pending intent |
| `HTX_CONTRACT_HEDGE_AUTO_SEND_ENABLED` | `false` | 禁止自动提交合约 hedge/recycle intent |
| `HTX_CONTRACT_DRY_RUN` | `true` | 即使误触执行路径，也只应落 dry-run 结果 |
| `HTX_CONTRACT_LIVE_ENABLED` | `false` | 关闭真实下单 live gate |
| `HTX_CONTRACT_HEDGE_SUPERVISOR_BATCH_SIZE` | `20` 或更小 | 降低一次对账批量，便于人工复核 |

禁止组合：

- `shadow-only=false` 与 `auto-send=true` 同时出现。
- `dry-run=false` 或 `live-enabled=true` 出现在 M8.1。
- 在 `HEDGE_DEGRADED` / `UNKNOWN` backlog 未清零或未备注前进入下一阶段。

## 第 1 天启动检查

第一步：确认部署对象。

- 记录目标环境、服务名、当前 JAR/镜像版本、当前 commit、目标 commit。
- 验证目标 commit 包含 `HtxDeltaHedgeSupervisor.ShadowReport` 和 `[HTX-HEDGE-SUPERVISOR-ALERT]` 日志。
- 验收：日报“部署对象”字段可追溯到具体 commit 和部署批次。

第二步：确认只读快照。

- 记录 HTX 现货余额、合约 short 仓位、可用保证金、强平距离、资金费率、spot/swap mid 价差。
- 记录本地 `htx_spot_fill_ledger`、`htx_inventory_lot`、`htx_delta_hedge_ledger`、`htx_inventory_recycle_saga` 的关键行数。
- 验收：交易所真相与本地账本口径同时存在，不只看 DB 或只看日志。

第三步：只开影子监督。

- 在获得授权后，仅把 `HTX_CONTRACT_HEDGE_SUPERVISOR_ENABLED=true` 加入目标环境。
- 保持 `HTX_CONTRACT_HEDGE_SUPERVISOR_SHADOW_ONLY=true`、`HTX_CONTRACT_HEDGE_AUTO_SEND_ENABLED=false`、`HTX_CONTRACT_DRY_RUN=true`、`HTX_CONTRACT_LIVE_ENABLED=false`。
- 验收：首个 `[HTX-HEDGE-SUPERVISOR] result=` 的 `ShadowReport` 中，`supervisorEnabled=true`、`shadowOnly=true`、`autoSendEnabled=false`、`dryRun=true`、`liveEnabled=false`。

## 每日日报模板

> 每天至少固定两个观察点：启动后 30 分钟、当日收盘前。若遇到价格越界、V 反转、资金费跳变或告警，则追加临时观察点。

| 字段 | Day 1 | Day 2 | Day 3 |
|---|---|---|---|
| 日期 / 观察人 | 待填 | 待填 | 待填 |
| 环境 / 服务 / commit | 待填 | 待填 | 待填 |
| supervisor 运行次数 | 待填 | 待填 | 待填 |
| `RunCode` 分布 | 待填 | 待填 | 待填 |
| `pendingHedgeRows` | 待填 | 待填 | 待填 |
| `pendingRecycleRows` | 待填 | 待填 | 待填 |
| `pendingSubmittedRows` | 必须为 0 | 必须为 0 | 必须为 0 |
| `pendingSkippedRows` | 待填 | 待填 | 待填 |
| `pendingBlockedRows` | 待填 | 待填 | 待填 |
| `newBlockingResults` | 待填 | 待填 | 待填 |
| recovery backlog degraded/unknown | 待填 | 待填 | 待填 |
| `MANUAL_INTERVENTION_REQUIRED` 次数 | 待填 | 待填 | 待填 |
| 当前 spot inventory | 待填 | 待填 | 待填 |
| 当前 `targetShort` | 待填 | 待填 | 待填 |
| 当前真实 short | 待填 | 待填 | 待填 |
| 网格窗口迁移次数 / 方向 | 待填 | 待填 | 待填 |
| 下破/上破边界是否仍运行 | 待填 | 待填 | 待填 |
| 组合 PnL 仅计算结果 | 待填 | 待填 | 待填 |
| 差异说明与处理人 | 待填 | 待填 | 待填 |
| 当日结论 | pass / watch / stop | pass / watch / stop | pass / watch / stop |

## 观察口径

- 日志口径：从 `[HTX-HEDGE-SUPERVISOR] result=` 提取 `ShadowReport` 字段；从 `[HTX-HEDGE-SUPERVISOR-ALERT]` 提取人工介入事件。
- 账本口径：按状态统计 `htx_delta_hedge_ledger` 与 `htx_inventory_recycle_saga`；重点关注 `INTENT`、`CLOSE_SHORT_PENDING`、`SENT`、`FILLED`、`UNKNOWN`、`HEDGE_DEGRADED`、`SKIPPED_DRY_RUN`、`SKIPPED_LIVE_GATE`。
- 交易所口径：对照 HTX 真实订单、现货余额、合约 short 仓位和保证金安全线；交易所真相优先于本地状态。
- 策略口径：记录网格窗口上移/下移事件、`targetShort` 变化、实际 short 偏差、库存回收建议；M8.1 只计算，不执行。

## 立即停止条件

任一条件命中，M8.1 当日结论必须写 `stop`，且不得进入 M8.2：

1. 影子模式下 `pendingSubmittedRows > 0`。
2. `autoSendEnabled=true`、`shadowOnly=false`、`dryRun=false` 或 `liveEnabled=true` 出现在目标环境。
3. 任一 `UNKNOWN` / `HEDGE_DEGRADED` backlog 未被解释，却继续新增 pending hedge/recycle intent。
4. 价格跌破或突破网格边界后出现 `STOPPED`，或窗口未按整数步长迁移。
5. 真实 short 与 `targetShort` 偏差超过一个合约张数步长且无风险门解释。
6. 出现非 `reduceOnly=true` 的平空或回收 close-short 意图。
7. 发现 shared position / 非本策略持仓所有权冲突。
8. funding、basis、margin、liquidation 任一数据缺失，但 planner 仍增加 `targetShort` 或新增开空意图。

## M8.1 验收

三天全部满足以下条件后，M8.1 才能从 `prep-ready` 改为 `done`：

- Day 1-3 日报齐全，且每日报告都有观察人、commit、配置、日志与账本证据。
- `pendingSubmittedRows` 始终为 0。
- 没有未解释的 `HEDGE_DEGRADED` / `UNKNOWN`。
- 价格越界场景不触发 STOP；窗口迁移方向与整数步长符合预期。
- `targetShort`、真实 short、现货库存、账本库存之间的差异都有解释，且不超过对应最小步长。
- 风险负责人确认可以进入 M8.2；否则继续观察或回到 M7 修复。

## 下一阶段进入条件

只有在 M8.1 三天 pass 后，才能讨论 M8.2。M8.2 仍然是影子盘观察，不意味着允许真实合约下单；真实下单最早也必须等 M9 dry-run 连续 3 天、G4 风险签署和用户单独生产授权之后。
