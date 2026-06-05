# 问题记录: 策略运行状况定期检查（开单/胜率/卡点）

> ⚠️ **已被取数版替代**：本 README 是 2026-05-30 早间一次**网络受限**运行的产物（当时沙箱无法连 `49.51.250.174`，仅给出方法与占位 SQL）。同日 10:28–10:30 的重跑已**直连数据库取得实测数据**，正式结论以 [`../问题记录事项-待解决-2026-05-30.md`](../问题记录事项-待解决-2026-05-30.md) 为准（核心结论：可用资金耗尽 → G4 门全拒 → ~24h 零开仓；结算胜率 13.3%）。本文保留作方法/脚本参考。

> 本文档遵循项目 `skills/doc-writing`（问题记录模板）与 `skills/troubleshooting`（7 步排查法）规范编写，未自定义结构。

## 基本信息

- **日期**: 2026-05-30
- **报告人**: AI Assistant（定时任务 `1-job` 自动运行）
- **严重程度**: P2（本次为例行巡检；含一项 P1 阻塞：自动化环境无法直连服务器，详见“当前阻塞”）
- **状态**: 待解决
- **检查对象**: 当前版本策略（Polymarket 跟单策略为主，兼顾资金费率/通用交易链路）
- **目标环境**: test 环境，服务器 `49.51.250.174`，库 `coin_strategy`

## 检查目标

按定时任务要求，定期核验：

1. **开单是否正常** —— 近 24 小时是否持续开单、开单频率有无异常下跌、是否存在卡住未提交/未成交的订单。
2. **胜率是否正常** —— 已平仓交易的胜率、盈亏比是否在历史正常区间，按策略/周期分组对比。
3. **卡点在哪里** —— 定位“信号→订单→成交→持仓→平仓”链路中断点。

## 当前阻塞（本次自动运行未取得线上数据）

本次为无人值守的定时运行。自动化沙箱**仅允许访问白名单网络**，对服务器 `49.51.250.174` 的以下端口均连接超时，因此**本轮无法直连线上库取数**：

| 目标 | 端口 | 结果 |
| --- | --- | --- |
| MySQL | 3306 | 连接超时（timeout 2003） |
| SSH | 22 | 连接超时 |

- 复现：在沙箱内 `pymysql.connect(host=49.51.250.174, port=3306, ...)` 抛 `OperationalError(2003, "Can't connect to MySQL server ... (timeout)")`；`socket.connect((host,22))` 同样超时。
- 结论：**这不是数据库/策略故障，而是巡检执行环境的网络出口限制。**

### 待解决项（需人工或调整运行环境）

1. **打通取数通道（最高优先级）**：将定时任务放到可访问 `49.51.250.174` 的执行环境（例如服务器本机 crontab、或加入出口白名单/跳板机），或为巡检开放只读数据库账号 + 可达网络。打通后下方诊断脚本可直接落地为每日自动巡检。
2. 在打通前，可由人工按本文“手动执行步骤”跑一次，并把结果回填到本记录的“排查过程/根因分析”。

## 手动执行步骤（在可访问服务器的机器上）

```bash
# 方式一：SSH 登录后本机连库
cd /Users/jenniferni && ssh -i ./jenifer/ray/ChainStrategyPrime.pem ubuntu@49.51.250.174
# 服务器密码备用：Chainprime1122!.

# 方式二：本地有 mysql 客户端 + 网络可达时直连
mysql -h 49.51.250.174 -P 3306 -uroot -pLroot1234 coin_strategy --default-character-set=utf8mb4
```

> 落地时优先使用同目录下的 `diagnose.py`（见文末），一次性输出全部巡检指标。

## 排查过程（按 troubleshooting 7 步法）

### 第 1 步 · 明确现象

例行巡检，无明确报障。重点验证开单连续性与胜率水位，并排查链路卡点。

### 第 2 步 · 确认环境与范围

- 涉及核心表（来自 `sql/init.sql` 与 `sql/V20260514__create_polymarket_follow_tables.sql`）：
  - 通用交易链路：`t_strategy`、`t_trade_signal`、`t_order`、`t_trade`、`t_position`、`t_account`、`t_funding_rate`
  - Polymarket 跟单链路：`polymarket_follow_signal`、`polymarket_follow_order`、`polymarket_follow_position`（及 `polymarket_market` / `polymarket_trade`）
- Job 代码：`coin-strategy-job`（含 `coin-strategy-job-polymarket` 子模块）。
- 链路模型：**信号(signal) → 订单(order) → 成交(trade) → 持仓(position) → 平仓**。任一环节断点即为“卡点”。

> 注：本轮因网络阻塞未能 `DESCRIBE` 各表，下列 SQL 按项目通用列名（`create_time`/`update_time`/`status`/`strategy_id`/`profit`|`pnl`/`side`|`direction`）编写。**首次执行前请先用第 3 步的 DESCRIBE 校准真实列名**，再按需替换。

### 第 3 步 · 校准表结构（先跑一次）

```sql
DESCRIBE t_order;
DESCRIBE t_position;
DESCRIBE polymarket_follow_order;
DESCRIBE polymarket_follow_position;
DESCRIBE polymarket_follow_signal;
-- 重点确认：时间列(create_time)、状态列(status)、盈亏列(profit/pnl/realized_pnl)、策略列(strategy_id/strategy_name)的真实命名
```

### 第 4 步 · 检查“开单是否正常”

```sql
-- 4.1 最近一次开单时间（越接近当前时间越健康；若 >1~2h 无新单需告警）
SELECT 'follow' AS src, MAX(create_time) AS last_order FROM polymarket_follow_order
UNION ALL
SELECT 't_order', MAX(create_time) FROM t_order;

-- 4.2 近 24 小时开单量 + 近 7 天日均，做横向对比
SELECT COUNT(*) AS last_24h FROM polymarket_follow_order
 WHERE create_time >= NOW() - INTERVAL 1 DAY;
SELECT DATE(create_time) AS d, COUNT(*) AS cnt
  FROM polymarket_follow_order
 WHERE create_time >= NOW() - INTERVAL 7 DAY
 GROUP BY DATE(create_time) ORDER BY d;

-- 4.3 开单按小时分布（识别是否某时段突然停摆）
SELECT DATE_FORMAT(create_time,'%Y-%m-%d %H:00') AS h, COUNT(*) cnt
  FROM polymarket_follow_order
 WHERE create_time >= NOW() - INTERVAL 2 DAY
 GROUP BY h ORDER BY h;

-- 4.4 卡住的订单：长时间停留在“待提交/提交中/部分成交”
SELECT status, COUNT(*) cnt, MIN(create_time) oldest
  FROM polymarket_follow_order
 WHERE status NOT IN ('FILLED','CANCELED','CLOSED','DONE')   -- 按真实状态枚举调整
 GROUP BY status;
```

判定基准：近 24h 开单量较 7 日均值骤降 >50%、或最近开单时间距今 >2 小时、或出现大量长龄未成交订单 ⇒ **开单异常**。

### 第 5 步 · 检查“胜率是否正常”

```sql
-- 5.1 总体胜率与盈亏比（基于已平仓持仓）
SELECT
  COUNT(*)                                              AS closed_cnt,
  SUM(CASE WHEN profit > 0 THEN 1 ELSE 0 END)           AS win_cnt,
  ROUND(SUM(CASE WHEN profit>0 THEN 1 ELSE 0 END)/COUNT(*)*100,2) AS win_rate_pct,
  ROUND(AVG(profit),4)                                  AS avg_pnl,
  ROUND(AVG(CASE WHEN profit>0 THEN profit END),4)      AS avg_win,
  ROUND(AVG(CASE WHEN profit<0 THEN profit END),4)      AS avg_loss
FROM polymarket_follow_position
WHERE status IN ('CLOSED','DONE');                       -- 按真实平仓状态调整

-- 5.2 近 7 / 30 天胜率（观察趋势是否走低）
SELECT '7d'  AS win,
  ROUND(SUM(CASE WHEN profit>0 THEN 1 ELSE 0 END)/COUNT(*)*100,2) AS win_rate_pct
FROM polymarket_follow_position
WHERE status IN ('CLOSED','DONE') AND update_time >= NOW() - INTERVAL 7 DAY
UNION ALL
SELECT '30d',
  ROUND(SUM(CASE WHEN profit>0 THEN 1 ELSE 0 END)/COUNT(*)*100,2)
FROM polymarket_follow_position
WHERE status IN ('CLOSED','DONE') AND update_time >= NOW() - INTERVAL 30 DAY;

-- 5.3 分策略胜率（定位是单个策略拖累还是整体）
SELECT strategy_id,
  COUNT(*) closed_cnt,
  ROUND(SUM(CASE WHEN profit>0 THEN 1 ELSE 0 END)/COUNT(*)*100,2) win_rate_pct,
  ROUND(SUM(profit),4) total_pnl
FROM polymarket_follow_position
WHERE status IN ('CLOSED','DONE')
GROUP BY strategy_id ORDER BY total_pnl;
```

判定基准：与历史/回测基线对比，近 7 天胜率显著低于 30 天均值，或盈亏比 <1 且胜率 <50% ⇒ **胜率异常，需复核策略参数/行情适配**。

### 第 6 步 · 定位“卡点”

```sql
-- 6.1 信号→订单转化（有信号但未生成订单 = 执行环节卡点）
SELECT
  (SELECT COUNT(*) FROM polymarket_follow_signal WHERE create_time >= NOW()-INTERVAL 1 DAY) AS signals_24h,
  (SELECT COUNT(*) FROM polymarket_follow_order  WHERE create_time >= NOW()-INTERVAL 1 DAY) AS orders_24h;

-- 6.2 最近未被消费的信号（堆积说明 job 未拉取/执行失败）
SELECT status, COUNT(*) cnt, MIN(create_time) oldest
FROM polymarket_follow_signal
WHERE create_time >= NOW() - INTERVAL 2 DAY
GROUP BY status;

-- 6.3 订单创建但长时间未成交（撮合/下单接口卡点）
SELECT id, status, create_time, TIMESTAMPDIFF(MINUTE, create_time, NOW()) AS age_min
FROM polymarket_follow_order
WHERE status NOT IN ('FILLED','CANCELED','CLOSED','DONE')
ORDER BY create_time ASC LIMIT 50;

-- 6.4 持仓长期不平仓（平仓逻辑/止盈止损卡点）
SELECT id, strategy_id, status, create_time,
       TIMESTAMPDIFF(HOUR, create_time, NOW()) AS hold_hours
FROM polymarket_follow_position
WHERE status NOT IN ('CLOSED','DONE')
ORDER BY create_time ASC LIMIT 50;

-- 6.5 错误聚合（若订单/信号表有 error_msg/remark 列）
SELECT status, COUNT(*) cnt
FROM polymarket_follow_order
WHERE create_time >= NOW() - INTERVAL 2 DAY
GROUP BY status ORDER BY cnt DESC;
```

同时检查服务器侧 Job 日志（确认调度是否在跑、是否抛异常）：

```bash
# 登录服务器后（路径按实际部署调整）
ls -lt ~/coin-strategy*/logs/ 2>/dev/null
tail -n 300 ~/coin-strategy-job*/logs/*.log | grep -iE "error|exception|fail|timeout"
# 确认 job 进程存活
jps -l | grep -i job || ps -ef | grep -i job | grep -v grep
```

卡点判定矩阵：

| 现象 | 卡点位置 | 优先排查 |
| --- | --- | --- |
| 有信号、无订单 | 信号→下单执行 | Job 是否运行/拉取信号、下单接口异常、风控拦截 |
| 有订单、长期未成交 | 下单→撮合 | 交易所/Polymarket 接口、价格档位、余额不足 |
| 持仓长期不平 | 平仓触发 | 止盈止损条件、平仓任务调度 |
| 全链路无新数据 | 数据源/调度 | 行情拉取任务、定时器、DB 写入 |

### 第 7 步 · 结论与跟进

- 本轮因**执行环境网络受限**未取到线上数据，无法给出开单/胜率的实测结论。
- 上述 SQL/脚本已就绪，**一旦取数通道打通即可直接出结论**，并回填到本记录后将状态改为“解决中/已解决”，必要时归档至 `docs/problem/archive/`。

## 影响范围

- 巡检本身：当前无法自动产出线上健康度结论，存在“策略异常但未被及时发现”的监控盲区。
- 业务策略：本轮未发现可证实的线上故障（因无法取数，亦不能排除）。

## 根因分析

- 直接原因：定时巡检运行在受限沙箱中，无法访问 `49.51.250.174` 的 3306/22 端口。
- 深层原因：巡检任务的运行位置与数据源网络隔离，缺少专用只读取数通道。

## 解决方案（建议）

1. 将巡检改到可达环境运行（服务器本机 crontab 调用 `diagnose.py`，结果落盘/推送）。
2. 或为巡检账号开通到 `coin_strategy` 的只读、网络可达通道。
3. 通道打通后，将本巡检固化为每日定时任务，异常（开单停摆/胜率跌破阈值/卡点堆积）自动告警。

## 验证结果

- [ ] 取数通道打通，`diagnose.py` 在目标环境成功连库
- [ ] 开单指标已取得并判定
- [ ] 胜率指标已取得并判定
- [ ] 卡点已定位
- [ ] 结论回填本记录并更新状态

## 经验总结

- 监控/巡检任务必须部署在与数据源网络连通的环境，否则形同虚设。
- 巡检脚本应对表结构做防御性校准（先 DESCRIBE 再统计），避免列名假设导致误报。
- 健康度三要素固化：开单连续性、胜率水位、链路卡点，统一阈值化告警。

---

*本记录由定时任务 `1-job` 自动生成，遵循项目 `skills/doc-writing` 与 `skills/troubleshooting` 规范。*
