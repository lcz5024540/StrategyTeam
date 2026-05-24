# Polymarket 跟单策略运行分析报告

**分析区间**：2026-05-13（上次更新）→ 2026-05-23 21:00（CST）
**生成时间**：2026-05-23
**数据来源**：服务器 `49.51.250.174`（SSH 进程与日志）+ `coin_strategy` 数据库（`smart_wallet_*` / `strategy_*` 表）

---

## 一、执行摘要

跟单策略服务**当前在线运行正常**（进程 PID 128210，启动于 05-22 00:46，已连续运行约 44 小时，无 ERROR 日志）。但在 5.13 更新后的这 10 天里，出现了三个层面的问题，按严重程度排序如下：

1. **【严重】一次约 40 小时的停机**：05-20 08:40 跟单主链路停止摄取，05-20 19:16 进程整体宕机，全天 05-21 无任何数据，直到 05-22 00:46 重新部署并手动拉起。服务**没有任何进程守护 / 自动重启机制**，导致无人值守状态下长时间空转。
2. **【严重】策略结构性"不开单"**：5.13 至今共产生 12,475 个跟单任务，真正进入跟单执行的仅 **20 笔（占 0.16%）**，其余 72% 被 SKIPPED、27% 仅落为影子单（PAPER_SHADOW）。服务内置的 `StrategyHealthMonitorJob` 在 5/22、5/23 多次告警 "24 小时 / 4 小时零开仓"。
3. **【中等】数据完整性缺陷**：5/13–5/14 有 669 条交易事件永久未处理；存在 9 条"未来时间结算"的异常记录；`daily_report` 多日缺失且与明细对不上；Live↔Replay 校验链路失效。

**重要前提**：截至目前，所有执行仍为 **纸面模拟（PAPER）模式**，没有任何真实链上下单。下文盈亏均为模拟盘口径。

---

## 二、问题拆解

为系统性回答"5.13 更新后运行情况如何"，将其拆解为四个子问题，并逐一核查：

| 模块 | 子问题 | 核查对象 |
|---|---|---|
| 模块一 | 服务进程是否稳定存活？有无重启 / 崩溃？ | SSH 进程、`job-*.log`、各表写入时间线 |
| 模块二 | 跟单成交多少？盈亏与胜率如何？ | `smart_wallet_order` / `trade_order` / `position` / `daily_report` |
| 模块三 | 数据写入是否连续完整？有无异常记录？ | 全量 `smart_wallet_*` 表的时间断档与字段异常 |
| 模块四 | 异常根因是什么？如何修复与优化？ | 日志报错、跟单拒绝原因、风控网关 |

> 补充背景：根据代码仓库 Git 记录，5.13 前后的更新本质是一次**架构拆分**——"job 和 web 拆分，web 保留业务接口，job 做策略服务"（提交集中在 5/12–5/14）。5/14 仍有 "web 服务启动修复 / job 启动修复" 的补丁提交。本报告分析的"跟单策略服务"即拆分后的 job 服务（服务器上的 `backend/app.jar`，应用名 `CoinStrategyJobApplication`）。

---

## 三、模块一：服务运行状态与日志

### 3.1 当前运行状态：正常

服务器 `VM-0-11-ubuntu` 已开机 39 天，未重启。跟单策略以**单进程**方式运行：

```
java -Xms512m -Xmx1024m -XX:+UseG1GC -Dspring.profiles.active=test
     -Dserver.port=8081 -jar /opt/coinstrategy/smart-wallet/backend/app.jar
```

- 进程 PID 128210，启动于 **2026-05-22 00:46:11**，截至 05-23 21:05 持续运行，常驻内存约 777 MB（堆上限 1 GB）。
- `job-current.log` 当前 **0 条 ERROR**、1,298 条 WARN；数据库最新写入时间 05-23 20:57，与日志时间一致——**服务在线且正在正常处理数据**。
- 服务器资源充足：磁盘 41% 占用、内存 3.6 GB（用 1.7 GB，另有 1.6 GB swap 余量）。

### 3.2 关键事件：05-20 至 05-22 的约 40 小时停机

这是 5.13 后最严重的运行事故。各数据表的写入时间线清晰还原了过程：

| 阶段 | 时间点（CST） | 现象 |
|---|---|---|
| 主链路停摆 | 05-20 08:39:56 | `follow_task`、`trade_event` 最后一条写入，之后**事件摄取彻底停止** |
| 进程带病运行 | 08:40 – 19:16 | 后台扫描类任务（`order_analysis`）仍在跑，但跟单摄取已死约 10.5 小时 |
| 进程整体宕机 | 05-20 19:15:38 | `order_analysis` 最后写入；`job-2026-05-20.log` 在 19:16:18 戛然而止，无任何关闭日志 |
| 完全空窗 | 05-21 全天 | `job-2026-05-21.log` 为 0 字节，全天零数据 |
| 重新部署 | 05-21 22:26 | 替换新 `app.jar`（66.5 MB），打包 `smart-wallet-test-job.tar.gz` |
| 恢复 | 05-22 00:46:11 | 进程重启成功（10.7 秒启动完成），00:47–00:48 各表恢复写入 |

**结论**：跟单主链路中断约 **40 小时 8 分钟**（05-20 08:40 → 05-22 00:48）；进程完全宕机约 **29.5 小时**（05-20 19:16 → 05-22 00:46）。

### 3.3 停机根因线索

`job-2026-05-20.log` 末尾在 19:13:18 出现数据库通信中断异常：

```
CommunicationsException: Communications link failure
The last packet successfully received from the server was 21,819,293 ms ago.
（MyBatis Mapper: SmartWalletOrderAnalysisMapper.updateById）
```

21,819,293 ms ≈ **6 小时**——意味着该数据库连接早在当天 13:00 左右就已失效，连接池一直持有死连接未被剔除。`dmesg` 中**未发现 OOM Killer 记录**，排除内存溢出被杀。综合判断：进程因数据库连接长期失效而处于"假死/挂起"状态，最终于 19:16 后停止；由于缺乏守护进程，无人发现，直至次日晚间手动重新部署。

### 3.4 致命缺口：无进程守护

- 服务器上**没有任何 systemd 服务单元**管理本应用（`systemctl` 查无 coin/smart 相关单元）。
- ubuntu 用户**无 crontab**，无健康巡检脚本。
- 进程通过 `bin/start-smart-wallet-backend.sh` 以 `nohup java -jar` 裸启动，**崩溃后不会自动拉起**。
- 值得注意：`bin/` 目录下**已存在** `install-smart-wallet-linux-service.sh`（systemd 安装脚本，7 KB），但从未被执行启用。

这正是"40 小时事故"得以发生的直接原因——若有 `Restart=always` 的 systemd 守护，停机时间可压缩到分钟级。

### 3.5 健康监控的自我告警

服务内置的 `StrategyHealthMonitorJob` 在恢复后持续报警：

- 05-22 02:55、18:54：`CRITICAL: Zero positions opened in the last 24 hours! Strategy may be structurally blocked.`
- 05-23 14:57、17:38、19:52：`WARNING: Zero positions opened in the last 4 hours.`

监控本身工作正常，但**告警只写入日志、没有外发到任何通知渠道**，因此问题虽被"检测到"却未被"通知到"。

---

## 四、模块二：跟单交易盈亏与成交

### 4.1 重要口径说明：全程为纸面模拟（PAPER）

5.13 至今，**没有任何真实链上下单**：

- `follow_task.execution_mode`：PAPER 9,051 / PAPER_SHADOW 3,424，**LIVE 为 0**。
- `smart_wallet_order` 全部 143 笔的 `execution_mode` 均为 `PAPER`。
- 资金账本 `cash_ledger` 的执行账户为 `paper-book`（纸面账本）。

因此以下盈亏均为**模拟盘表现**，用于策略验证，不代表真实资金损益。

### 4.2 跟单转化漏斗（5/13 起）

| 阶段 | 数量 | 占比 |
|---|---|---|
| 跟单任务总数 `follow_task` | 12,475 | 100% |
| SKIPPED（直接跳过） | 9,031 | 72.4% |
| PAPER_SHADOW（仅记录影子单） | 3,424 | 27.4% |
| FOLLOWED + SETTLED（真正进入跟单执行） | **20** | **0.16%** |

每 600 多个跟单任务才实际成交 1 笔——这是本期最突出的结构性问题（详见模块四）。

### 4.3 模拟盘成交与盈亏

5/13 以来共 **20 笔**模拟跟单成交（对应 `smart_wallet_trade_order` id 587–606）：

| 指标 | 数值 |
|---|---|
| 成交笔数 | 20 笔（18 笔已了结，2 笔持仓中） |
| 投入成本 | 70.85 USDC |
| 已实现盈亏 | +16.84 USDC |
| 未实现盈亏 | +3.88 USDC |
| **区间合计盈亏** | **+20.72 USDC** |
| 已了结胜 / 负 | 7 胜 / 11 负 |
| 已了结胜率 | **38.9%** |

- **区间表现为正**（+20.72 USDC），但样本极小（仅 20 笔、总成本约 70 美元），统计意义有限。盈利主要来自少数几笔高 ROI 单（如 id 597 +10.09、id 598 +16.69）。
- **生命周期累计**：全部 82 笔 `trade_order` 合计盈亏 **−2.67 USDC**，即 5/13 之前累计约 −23 USDC，本期把整体拉回接近盈亏平衡。
- 可用模拟资金约 190–194 USDC，仓位上限 500 USDC，当前在用约 142–157 USDC，资金利用率不高。

### 4.4 数据口径不一致提示

`smart_wallet_daily_report` 中 5/13 后 10 个报告日的净盈亏合计为 **−1.16 USDC**，与 `trade_order` 口径的 +20.72 USDC 明显不符。原因是 `daily_report` 按结算日聚合且多日缺失（详见模块三），**不建议以该表作为盈亏依据**，应以 `trade_order` 为准。

---

## 五、模块三：数据完整性与异常

### 5.1 约 40 小时数据断档

如模块一所述，全部 `smart_wallet_*` 表在 **05-20 08:40 → 05-22 00:48** 之间无任何写入，整个 05-21 完全空白。这是本期最大的一处数据缺口。

### 5.2 669 条交易事件永久未处理（5/13–5/14）

`smart_wallet_trade_event.processed` 字段统计：

| 日期 | 事件总数 | 已处理 | **未处理** |
|---|---|---|---|
| 05-13 | 419 | 297 | **122** |
| 05-14 | 678 | 131 | **547** |
| 05-15 起 | 正常 | 全部处理 | 0 |

5/13–5/14 共 **669 条交易事件被摄取入库但从未被跟单逻辑消费**，且至今未补处理。该时间窗恰好与 5.13 的 job/web 架构拆分重合——拆分过程中跟单消费链路出现了一段处理空窗。这些事件大多来自钱包 `0x2005d16a84…`（高频网球/电竞市场交易者）。

### 5.3 "未来时间结算"异常

- `smart_wallet_trade_order` 中有 **9 条记录的 `settled_at` 晚于当前时间**（如 id 590 显示 2026-06-30 结算）。
- `daily_report` 因此生成了 4 个**未来日期的报告行**（report_date = 5/25、5/27、5/29、6/30）。

根因是结算逻辑把市场的"名义到期时间"写成了实际结算时间。这会污染按日统计与盈亏归集。

### 5.4 daily_report 稀疏且不可靠

`daily_report` 缺失大量日期（5/14、5/16、5/17、5/21 等均无记录），既有断档又混入未来日期，与明细表对不上。建议重构其生成逻辑或停用。

### 5.5 空表 / 未使用字段

- `strategy_run_log`、`strategy_health_snapshot`、`smart_wallet_market_snapshot` 三表**行数为 0**——要么功能未启用，要么写入逻辑未接通。
- `cash_ledger.balance_after` 字段全部为 NULL，账户余额无法从账本直接追溯。

### 5.6 Live↔Replay 校验链路失效

`live_replay_diff_history` 全部 5 行的 `replay_accepted_events` 均为 **0**——回放/复盘校验从未接受任何事件，导致 Live 与 Replay 的对比恒为"无穷大偏差"。这正是 05-23 03:22 触发 `AUDIT_LIVE_REPLAY_DIFF` 告警（pnl_diff_bps = 10000）的原因。该监控目前形同虚设。

### 5.7 strategy_alert 记录

仅 2 条，均为 WARN 级：05-17 `AUDIT_SETTLEMENT_SOURCE`（delayed=819）、05-23 `AUDIT_LIVE_REPLAY_DIFF`。

---

## 六、模块四：错误诊断与改进建议

### 6.1 根因诊断

**根因 A — 停机事故（模块一）**
数据库连接长期失效 + 连接池未剔除死连接 → 调度线程挂起；叠加**无进程守护**，故障无人感知、无法自愈。

**根因 B — 结构性不开单（模块二的 0.16% 转化率）**
日志与 `signal_shadow` 显示，跟单信号被层层拦在风控网关外：

| 拦截原因 | 规模 | 说明 |
|---|---|---|
| `MARKET_RESOLVED` 市场已结算 | 8,515 条（占 SKIPPED 的 94%） | 处理跟单任务时市场已经结算，根本无法下单 |
| `G10c_EXPOSURE_LIMIT` / `EXPOSURE_CLIP_BELOW_MIN` | signal_shadow 中合计约 700 条 | 敞口/TVL 上限把下单金额裁剪到低于最小单（$0.5/$1），无法成交 |
| `KELLY_NEGATIVE_EV` | 437 条 WARN | Kelly 模型算出负期望，不下注 |
| `G3_PRICE_OUT_OF_RANGE` / `TRIGGER_SIZE_TOO_SMALL` | 约 220 条 | 价格越界 / 触发金额过小 |

其中 `MARKET_RESOLVED` 是核心矛盾。稳态下"领单事件发生 → 跟单任务评估"的延迟约 **13–16 分钟**（5/22–5/23 因负载升高恶化到约 40 分钟）。对于电竞单图、网球单盘这类**分钟级结算的快市场**，10–40 分钟的延迟意味着等系统反应过来、市场早已结算。此外，启动日志显示 `enable-ws-client=false`——**Polymarket WebSocket 实时推送被关闭**，系统只能轮询，进一步放大了延迟。

同时，05-22 当天 7,419 条 SKIPPED 中绝大多数为 `MARKET_RESOLVED`：这是停机 40 小时积压的事件在恢复后被集中处理，而此时对应市场早已结算——**停机事故直接放大了"市场已结算"跳过量**。

**根因 C — Polymarket API 不稳定**
`job-current.log` 中有约 48 次 API 请求失败 + 39 次 curl 兜底失败 + 7 次搜索失败，虽未致命，但加剧了元数据获取延迟。

### 6.2 改进建议（按优先级）

**P0 — 消除单点停机风险**

1. **启用进程守护**：执行已存在但未使用的 `install-smart-wallet-linux-service.sh`，将服务托管为 systemd 单元并设置 `Restart=always`、`RestartSec=10`；再叠加一个基于 `/strategy/api` 健康端点的外部巡检看门狗。
2. **数据库连接韧性**：为 HikariCP 配置 `keepaliveTime`、`maxLifetime`（小于 MySQL `wait_timeout`）、`validationTimeout` 与 JDBC `socketTimeout`，让死连接被主动剔除而不是把调度线程拖死。

**P1 — 让策略真正能开单**

3. **降低跟单延迟**：开启 Polymarket WebSocket 客户端（`enable-ws-client=true`）或显著缩短轮询周期；对分钟级结算的快市场类型在链路早期就快速短路，避免无效占用。
4. **隔离调度线程**：当前所有定时任务挤在单一 `scheduling-1` 线程，一个任务卡死会冻结整条摄取链路。应为事件摄取单独分配线程池。
5. **重调敞口风控**：`EXPOSURE_CLIP_BELOW_MIN` 频繁把下单额裁到最小单以下，等于既不开单又不断刷拒绝噪声。需重新标定敞口上限与最小单的关系（提高资金、下调最小单、或在裁剪前直接干净跳过）。

**P2 — 数据治理**

6. 修复结算时间戳（使用真实结算时刻而非名义到期时间），清理 9 条未来时间结算记录。
7. 补处理或显式标记 5/13–5/14 的 669 条未处理交易事件。
8. 修复 Live↔Replay 校验（`replay_accepted_events` 恒为 0），否则该监控无意义。
9. 重构或停用 `daily_report`；接通空表 `strategy_run_log` / `strategy_health_snapshot` 的写入。

**P3 — 告警可达性**

10. `StrategyHealthMonitorJob` 已能检测"零开仓"等异常，但目前只写日志。应将 CRITICAL/WARN 告警外发到即时通知渠道（如企业微信/钉钉/飞书），确保下次停机能第一时间被发现。

---

## 七、附录：数据来源与方法

- **服务器**：SSH 登录 `ubuntu@49.51.250.174`，核查进程（`ps`）、磁盘内存、`/opt/coinstrategy/smart-wallet/` 目录结构、`logs/job-*.log` 日志、`dmesg`、systemd/crontab。
- **数据库**：`coin_strategy` 库，重点表 `smart_wallet_follow_task`、`smart_wallet_order`、`smart_wallet_trade_order`、`smart_wallet_position`、`smart_wallet_trade_event`、`smart_wallet_signal_shadow`、`smart_wallet_daily_report`、`live_replay_diff_history`、`strategy_alert`。
- **方法**：以各表 `created_at` 写入时间线还原服务存活状态，与日志、进程启动时间交叉验证；以 `trade_order` 为盈亏权威口径；以 `follow_task` 状态分布与日志拒绝原因定位结构性问题。
- **口径**：所有时间为 CST（Asia/Shanghai）；所有盈亏为 PAPER 纸面模拟，非真实资金。
