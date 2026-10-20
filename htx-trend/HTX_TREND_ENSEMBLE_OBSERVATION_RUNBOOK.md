# HTX 趋势组合 shadow 观察与后续实盘接入计划

## 当前结论

截至 2026-09-15 19:45，趋势组合 shadow 引擎已经在服务器 `49.51.250.174` 上运行。它只记录候选信号，不下单。真实趋势策略仍然保持 live 配置，但现有 XAUT 单策略仍是 `FLAT`，没有趋势仓位和开放趋势订单。

当前不要急着把 ensemble 直接接入真实交易。原因是第一版刚完成去重修复，后续统计应基于“每根完成 K 线只记录一次”的干净样本。至少观察几个 30m/60m K 线周期后，再选择第一批小额 live sleeve。

## 已上线版本

| 项目 | 值 |
|---|---|
| 分支 | `support_htx` |
| 最新提交 | `a565816f docs(htx): add trend ensemble observation runbook` |
| 服务器 | `49.51.250.174` |
| 服务 | `coin-strategy-htx.service` |
| 线上 JAR | `/opt/coinstrategy/htx-trade/app.jar` |
| JAR SHA-256 | `86ab8f8b15ec5f99260db263ae96d5ba54412fe8c55228cfda6e6f9b617ed60a` |
| shadow 开关 | `HTX_TREND_ENSEMBLE_SHADOW_ENABLED=true` |
| 真实趋势开关 | `HTX_TREND_ENABLED=true`, `HTX_TREND_OBSERVE_ONLY=false` |
| 合约执行开关 | `HTX_CONTRACT_ENABLED=true`, `HTX_CONTRACT_DRY_RUN=false`, `HTX_CONTRACT_LIVE_ENABLED=true` |

## 当前观察范围

| 维度 | 当前值 |
|---|---|
| 标的 | `BTC-USDT`, `ETH-USDT`, `XAUT-USDT` |
| 周期 | `15min`, `30min`, `60min`, `4hour` |
| 模型版本 | `ensemble-shadow-v1` |
| 记录表 | `htx_trend_candidate_signal` |
| 去重规则 | 同一 `contract_code + period + model_version + bar_time` 只记录一次 |

## 当前样本的初步判断

第一批样本里，稳定性从高到低大致如下：

| sleeve | 当前表现 | 初步处理 |
|---|---|---|
| `XAUT-USDT 30min SHORT` | 连续 SHORT，score 多在 70–80 | 重点观察，可能是第一批候选 |
| `XAUT-USDT 60min SHORT` | 连续 SHORT，score 多在 70–80 | 重点观察，可作为方向过滤或低频 sleeve |
| `XAUT-USDT 4hour SHORT` | 连续 SHORT，但样本数少 | 只做 regime，不单独触发 |
| `ETH-USDT 30min SHORT` | 连续 SHORT，score 稳定 | 重点观察，可能是第一批候选 |
| `BTC-USDT 60min SHORT` | 后段开始转 SHORT | 继续观察，不急着 live |
| 15min 周期 | 翻转明显，容易从 SHORT 退回 FLAT | 暂不作为第一批 live 依据 |

这只是初步观察，不能直接等同实盘收益。后续需要看信号持续时间、翻转频率、信号后价格推进幅度，以及触发后是否足够覆盖手续费、滑点和资金费。

## 到家后的恢复步骤

在新机器上先拉代码：

```bash
git clone https://github.com/lcz5024540/CoinStrategy.git
cd CoinStrategy
git checkout support_htx
git pull origin support_htx
```

如果已经有仓库：

```bash
cd CoinStrategy
git fetch origin
git checkout support_htx
git pull --ff-only origin support_htx
```

确认最新提交：

```bash
git log --oneline -5
```

应能看到：

```text
a565816f docs(htx): add trend ensemble observation runbook
e8e806d0 fix(htx): dedupe trend ensemble candidate bars
a564b04c fix(htx): report trend ensemble persistence failures
7d78e370 feat(htx): add trend ensemble shadow signals
3b876166 docs(htx): plan trend ensemble roadmap
```

## 服务器检查命令

确认服务状态：

```bash
ssh -i /path/to/ChainStrategyPrime.pem ubuntu@49.51.250.174 \
  'systemctl is-active coin-strategy-htx.service && systemctl show coin-strategy-htx.service -p MainPID --no-pager && sha256sum /opt/coinstrategy/htx-trade/app.jar'
```

确认 shadow 正常运行且没有重复写入：

```bash
ssh -i /path/to/ChainStrategyPrime.pem ubuntu@49.51.250.174 \
  'journalctl -u coin-strategy-htx.service --since "30 minutes ago" --no-pager | grep -E "HTX-TREND-ENSEMBLE|HTX-TREND\\] result|ERROR|Exception" | tail -n 120'
```

正常现象：

```text
RunResult[stored=0, skippedDuplicateBars=12, failed=0, failures=[]]
```

这表示同一根 K 线没有重复入库。等新 K 线完成时，部分周期会出现 `stored>0`。

## 数据库检查 SQL

在服务器上执行：

```bash
cat > /tmp/trend_candidate_eval.sql <<'SQL'
SELECT COUNT(*) AS total_rows,
       COUNT(DISTINCT CONCAT(contract_code, '|', period, '|', model_version, '|', bar_time)) AS unique_bars,
       MIN(created_at) AS first_seen,
       MAX(created_at) AS last_seen
FROM htx_trend_candidate_signal;

SELECT contract_code,
       period,
       COUNT(DISTINCT bar_time) AS unique_bars,
       SUM(direction = 'LONG') AS long_bars,
       SUM(direction = 'SHORT') AS short_bars,
       SUM(direction = 'FLAT') AS flat_bars,
       ROUND(AVG(signal_score), 2) AS avg_score,
       ROUND(MAX(signal_score), 2) AS max_score,
       MIN(created_at) AS first_seen,
       MAX(created_at) AS last_seen
FROM htx_trend_candidate_signal
GROUP BY contract_code, period
ORDER BY contract_code, FIELD(period, '15min', '30min', '60min', '4hour'), period;

SELECT t.contract_code, t.period, t.direction, t.regime,
       ROUND(t.signal_score, 2) AS score,
       ROUND(t.score_gap, 2) AS gap,
       t.close_price, t.bar_time, t.created_at,
       LEFT(t.entry_reason, 160) AS reason
FROM htx_trend_candidate_signal t
JOIN (
    SELECT contract_code, period, MAX(id) AS max_id
    FROM htx_trend_candidate_signal
    GROUP BY contract_code, period
) latest ON latest.max_id = t.id
ORDER BY t.contract_code, FIELD(t.period, '15min', '30min', '60min', '4hour'), t.period;

-- LIVE-only operational observation; unfiltered SQL is not a daily report.
SELECT COUNT(*) AS active_positions
FROM htx_trend_position
WHERE account_label <> 'paper' AND status IN ('INTENT', 'ENTRY_SENT', 'ACTIVE', 'CLOSE_SENT');

SELECT COUNT(*) AS open_trend_orders
FROM htx_trend_order
WHERE account_label <> 'paper' AND status IN ('INTENT', 'ACCEPTED');

SELECT contract_code, trend_signal, reason, created_at
FROM htx_trend_signal
ORDER BY id DESC
LIMIT 5;
SQL

bash -lc 'set -euo pipefail; set -a; source /opt/coinstrategy/htx-trade/env/htx.env; set +a; export MYSQL_PWD="$HTX_DB_PASSWORD"; mysql -h"$HTX_DB_HOST" -P"$HTX_DB_PORT" -u"$HTX_DB_USERNAME" -D htx_trade_strategy < /tmp/trend_candidate_eval.sql'
```

## 进入小额 live 前的判定标准

第一批不要接 15min。优先从下面几个 sleeve 里选：

1. `XAUT-USDT 30min SHORT`
2. `XAUT-USDT 60min SHORT`
3. `ETH-USDT 30min SHORT`
4. `BTC-USDT 60min SHORT`

建议满足这些条件后再接 live：

- 同一 sleeve 至少有 4 根完成 K 线样本。
- 最近 3 根完成 K 线方向一致。
- 最近 3 根 score 都大于等于 70。
- 15min 没有强烈反向信号；如果 15min 反向，只降权，不直接否决 30m/60m。
- 当前真实趋势策略没有 active position，也没有 open trend order。
- 盘口点差、资金费、保证金检查仍由原 `HtxTrendFollowingService` gate 执行。

## 下一步代码任务

### P1：候选信号评估器

新增一个只读评估服务，基于 `htx_trend_candidate_signal` 输出每个 sleeve 的状态：

- `READY_FOR_PAPER_LIVE`
- `WATCHING`
- `REJECTED_CHOPPY`
- `REJECTED_LOW_SCORE`

它只读 DB，不下单。

### P2：组合决策层

新增 `TrendDecisionScore`，把多个周期的候选信号合成一个决策：

- 30m：主触发
- 60m：方向确认
- 4h：regime 权重
- 15m：短期冲突降权

### P3：小额 live 接入

只允许配置白名单 sleeve，例如：

```text
HTX_TREND_ENSEMBLE_LIVE_ENABLED=false
HTX_TREND_ENSEMBLE_LIVE_SLEEVES=XAUT-USDT:30min:SHORT,ETH-USDT:30min:SHORT
HTX_TREND_ENSEMBLE_LIVE_MAX_NOTIONAL=50
```

第一版 live 接入必须继续复用现有趋势订单账本和仓位账本，不能另开一套下单路径。

## 当前不要做的事

- 不要把所有 SHORT 候选信号直接接成真实开仓。
- 不要用 15min 翻转信号做第一批 live。
- 不要删除生产历史重复样本；后续统计用 `COUNT(DISTINCT bar_time)` 或部署去重后的新样本窗口。
- 不要绕过现有真实趋势风控 gate。
