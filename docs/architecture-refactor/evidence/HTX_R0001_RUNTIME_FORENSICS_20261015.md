# R0001 运行与账本只读取证

取证时间：2026-10-15 15:35 CST  
服务器：`49.51.250.174`  
范围：B07 重启/扫描失败、B08 历史失败探针、B10 CLOSE/INTENT 状态；未执行任何修复。

## 1. 服务状态

| 服务 | 事实 |
|---|---|
| `htx-trend-worker@htx-trend-live-primary.service` | `active/running`，主进程启动于 2026-10-14 22:16:45 CST |
| `htx-trend-shadow-r1.service` | `active/running`，主进程启动于 2026-10-15 14:43:33 CST |
| 主 `current` | `/opt/coinstrategy/htx-trend-py/releases/0.1.1-r0-d9b1ceca` |
| 最近 24h 主服务 journal | 只有 systemd 停止/启动记录，未检出 `FAILED/ERROR/EXCEPTION/TRACEBACK` |
| 最近 24h shadow journal | 只有启动记录，未检出 `FAILED/ERROR/EXCEPTION/TRACEBACK` |

说明：`run/status.json` 当前记录的是独立 collector，状态为 `healthy`、`positions=[]`；它不是主趋势 worker 的交易心跳，不能用来证明主 LIVE 没有仓位或主策略已经完成扫描。

## 2. 运行指纹不一致

数据库 `strategy_runtime_instance` 当前登记：

| instance | mode | enabled | deployment_unit | config_fingerprint |
|---|---|---:|---|---|
| `htx-trend-live-primary` | LIVE | 1 | `htx-trend-worker@htx-trend-live-primary` | `0.1.1-live-982e5867` |
| `htx-trend-shadow-r1` | OBSERVE | 1 | `manual-shadow-r1` | `6806ded6` |

主服务实际 `current` 是 `0.1.1-r0-d9b1ceca`，与数据库登记的 `0.1.1-live-982e5867` 不同。这里目前只能确认“登记与文件系统不一致”，不能仅凭此判断正在运行的策略逻辑错误；需要后续以部署证据和发布记录核对。修正登记属于变更操作，本次不执行。

## 3. 历史探针与失败证据

状态目录仍保留 2026-10-12 的 probe 证据目录，包括 `probe-hotfix-*`、`probe-emergency-*`、`probe-s1-*`，其中有 `verification-failure-flag.txt`、`live0-status.stderr` 和 `SUMMARY.md`。这说明历史失败证据没有被删除，满足“可追溯”要求；B08 的“失败原因分类和是否需要人工收口”尚未完成。

本次没有重跑探针、没有删除目录、没有重置订单，也没有把探针结果计入正式 T1。

## 4. 账本状态

全库只读统计得到：

| 对象 | 状态 | 账户 | 数量 |
|---|---|---|---:|
| position | `DONE` | primary | 302 |
| position | `RECONCILED_CLOSED` | primary | 2 |
| position | `RECONCILED_CLOSED` | TradeX | 14 |
| order | `CLOSE/ACCEPTED` | TradeX | 15 |
| order | `CLOSE/EXPIRED` | TradeX | 1 |
| order | `PARTIAL_CLOSE/ACCEPTED` | TradeX | 20 |
| order | `ENTRY/ACCEPTED` | TradeX | 19 |
| order | `CLOSE/INTENT` | primary | 1 |

没有发现 position 表中仍处于 `CLOSE_SENT`、`CLOSE_PENDING` 或 `EXIT_SENT` 的记录；但 order 表中仍存在上述未终态记录，因此不能把“仓位已标记关闭”直接等同于“每条镜像订单已完成交易所对账”。

主账户的 `CLOSE/INTENT` 示例为 `position_id=149`、`ETH-USDT`，创建于 2026-09-24 14:02:33 CST，原因包含 `FUNDING_COST` 与退出快照。它已超出正常短期对账窗口，应列入 B10 人工复核队列。

TradeX 的 `CLOSE/EXPIRED` 示例为 `PEPE-USDT`，交易所状态为 `not_found`；这正是历史“旧镜像订单状态卡住”的类型，当前只记录为待清理/对账候选，未执行删除。

## 5. 判定与后续动作

- **B07：部分完成。** 最近 24h 两个策略服务没有 journal 级失败，但需要补应用级扫描/心跳查询；collector 的 healthy 不能替代策略 worker 证据。
- **B08：未完成。** 失败探针证据保留，但尚未形成失败原因分类、复核结论和“是否允许归档”的决定。
- **B10：未完成且有账本风险。** position 没有卡在 `CLOSE_SENT`，但主账户 1 条旧 `CLOSE/INTENT` 和 TradeX 多条 `ACCEPTED`/`EXPIRED` 未终态订单仍需对账；不能直接删除。
- **运行指纹：需要单独建立修复任务。** 先核对部署包、数据库登记和发布记录，再决定是否更新 `config_fingerprint`；不在本次只读任务中改库。
