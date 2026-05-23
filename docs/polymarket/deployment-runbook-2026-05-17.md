---
title: "部署 Runbook — P0/P1 最小化实施修复批次"
type: runbook
status: active
tags: [polymarket, deploy, runbook]
created: 2026-05-17
updated: 2026-05-17
---

# 部署 Runbook — P0/P1 最小化实施修复批次

> **生成日期**：2026-05-17
> **关联计划**：[`minimal-implementations-fix-plan.md`](./minimal-implementations-fix-plan.md)（11 轮自动调度合入完毕）
> **关联代码**：分支 `polymarket-5-min-weather`，尚未 commit（git status 可见）
> **状态前提**：SQL DDL 已由用户执行到数据库（test / staging / prod 全环境）
>
> **本文件目的**：以"step-by-step checklist + 立即可执行命令"的方式，覆盖 P0/P1 全部已落地变更从「DDL 上库后」到「灰度可观察 / 全量上线 / 紧急回滚」的完整路径。

---

## 零、本批次的工作总览

| 类别 | 数量 | 状态 |
|------|------|------|
| P0（资金 / 数据正确性）| 6 项 | 代码全部 ✅，配置默认值已生效 |
| P1（灰度推进 / 策略质量）| 17 项 | 代码全部 ✅；运行时开关有 9 项默认 OFF 待按阶段灰度 |
| 新增 DDL | 2 个文件（共 3 张表 + 2 列）| ✅ 用户已上库 |
| 新增配置开关 | ≈ 60 项 | 大部分使用 Java 默认值，无需改 yml；prod 已新增 5 项（F-P1-1） |
| 新增 Java 服务 | 17 个 | 含 WS client / GTC tracker / MAKER repost / staged exit / 飞书 sink 等 |
| 待运维提供 | 3 项 | 飞书 webhook、WS endpoint 联调、user channel auth token |

---

## 一、SQL 已上库后的强制代码动作（**部署前 P0**）

> ⚠️ **必须先做这一步，否则 `pm_resolution_audit` 写入路径会因为 `@TableField(exist=false)` 注解忽略字段，UMA 争议数据永远落不进新列。**

### 1.1 删除 PmResolutionAuditEntity 上的两个注解

文件：`coin-strategy-common/common-model/src/main/java/com/metaverse/coin/strategy/common/model/arbitrage/entity/PmResolutionAuditEntity.java`

修改方式：删除第 51 行和第 57 行的 `@TableField(exist = false)`。

修改前（第 45–58 行）：

```java
    /**
     * F-P1-12: UMA Oracle 是否争议中 (disputed=true)。
     * <p>列默认 false，建表 DDL 见 sql/polymarket/2026-05-17__f-p1-12_uma_dispute_audit_columns_draft.sql。
     * DDL 上线前用 {@code @TableField(exist=false)} 自动忽略，避免 BadSqlGrammarException；
     * 上线后请删除该注解。
     */
    @TableField(exist = false)
    private Boolean umaDisputed;
    /**
     * F-P1-12: 第一次观测到 UMA 争议的时间戳。
     * <p>同上：DDL 上线前 {@code exist=false}，上线后删注解使其参与 SELECT/UPDATE。
     */
    @TableField(exist = false)
    private Date umaDisputeObservedAt;
```

修改后：保留字段，仅删 `@TableField(exist = false)` 那一行（注释可保留也可一并删掉，建议改注释为「已上库」）。

### 1.2 验证 SQL 上库结果（sanity check）

由用户在 DB 客户端执行：

```sql
-- 1.2.1 验证 F-P1-12 两列已存在（pm_resolution_audit）
SHOW COLUMNS FROM pm_resolution_audit LIKE 'uma_disputed%';
-- 期望：返回 uma_disputed (TINYINT) + uma_dispute_observed_at (DATETIME) 两行

-- 1.2.2 验证 F-P1-3 GTC tracker 表
SHOW TABLES LIKE 'pending_order_timeout_tracker';
DESCRIBE pending_order_timeout_tracker;
-- 期望：12 列，UNIQUE KEY uk_pending_order_timeout_order 存在

-- 1.2.3 验证 F-P1-4 MakerOnly tracker 表
SHOW TABLES LIKE 'pending_maker_only_tracker';
DESCRIBE pending_maker_only_tracker;
-- 期望：13 列，UNIQUE KEY uk_pending_maker_only_order 存在

-- 1.2.4 验证 idx_pm_resolution_audit_uma_disputed 索引
SHOW INDEX FROM pm_resolution_audit WHERE Key_name = 'idx_pm_resolution_audit_uma_disputed';
-- 期望：返回 (uma_disputed, observed_at) 复合索引
```

只要 1.2.1–1.2.4 都通过，DDL 部分就算正确上库。

---

## 二、本批次涉及的配置开关一览（按"是否需要立即改 yml"分类）

### 2.1 ✅ 已默认开启 / 不需要任何改动（Java 默认值已是修复后行为）

| 开关 | 默认值 | 关联任务 | 说明 |
|------|--------|---------|------|
| `execution-wallet.enable-live-partial-exit` | `true` | F-P0-1 | LIVE 部分退出 |
| `execution-wallet.enable-pending-tracker-persistence` | `true` | F-P1-3/4 | tracker 持久化（DDL 已上库故立即生效）|
| `execution-wallet.enable-maker-best-bid-pricing` | `true` | F-P1-15 限价 | MAKER 参考 best bid 收口 |
| `signal-risk.enable-pre-execute-price-drift-check` | `true` | F-P0-5 | 执行前价格漂移二次检查 |
| `signal-risk.enable-position-adjustment-concurrency` | `true` | F-P1-10 | 多仓位调整并发 |
| `signal-risk.enable-replay-live-window-baseline` | `true` | F-P1-2 | A/B baseline 评估 |
| `signal-risk.enable-exit-threshold-fee-adjustment` | `true` | F-P1-5 | 退出阈值扣双边 taker fee |
| `signal-risk.enable-concentration-fail-open` | `false` | F-P0-3 | 集中度 fail-closed（默认即生效）|
| `intent-aggregation.max-stake-multiplier` | `3.0` | F-P0-4 | 意图聚合金额 cap |

> 这一组无需运维改 yml，重新部署 Java 进程后自动生效。

### 2.2 🟡 已在 application-prod.yml 改过（git diff 可见）

第十一轮（2026-05-17）追加到 prod profile：

```yaml
# coin-strategy-job/src/main/resources/application-prod.yml
smart-wallet:
  signal-risk:
    # F-P1-1 W2-T2 Leader 滑动窗口胜率门 prod 启用
    enable-leader-rolling-gate: true
    leader-rolling-min-samples: 10
    leader-rolling-min-win-rate: 0.40
    leader-rolling-cooling-stake-multiplier: 0.05
    manual-allowlist-bypass-leader-rolling-gate: true
```

> 该配置文件的全部 prod 启用项（不止 F-P1-1，还包括第十一/十二/十三/十四批的 W2-T6 / 价差 P70 / 流动性 P30 / VWAP estimator / MAKER_ONLY / Gamma+UMA Live refresh）在仓库里已全部就位，直接 build 即可。

### 2.3 ⬜ 默认 OFF，部署后按阶段灰度（**核心待办**）

> 这一组开关在生产部署后需要 ops 按下面"四、灰度阶段编排"逐项打开。**部署 = 把代码上线，灰度 = 按阶段打开开关**，两件事分开做。

| 开关 | 当前默认 | 关联任务 | 建议灰度阶段 |
|------|---------|---------|------|
| `execution-wallet.enable-gtc-global-timeout` | `false` | F-P1-3 | 阶段 A |
| `execution-wallet.enable-maker-price-followup` | `false` | F-P1-15 重报 | 阶段 B |
| `execution-wallet.enable-staged-exit` | `false` | F-P1-6 | 阶段 C |
| `signal-risk.enable-auto-reverse-on-opposite-buy` | `false` | F-P1-11 | 阶段 D |
| `audit.feishu-sink-enabled` | `false` | F-P1-9 | 阶段 E（依赖 ops 提供 webhook）|
| `polymarket.enable-ws-client` | `false` | F-P1-7/8 | 阶段 F-1 |
| `polymarket.enable-ws-auto-subscribe` | `false` | F-P1-7/8 | 阶段 F-2 |
| `polymarket.enable-ws-hot-path-snapshot` | `false` | F-P1-7 | 阶段 F-3 |
| `polymarket.enable-ws-trade-event-push` | `false` | F-P1-8 | 阶段 F-3 |

---

## 三、部署步骤（**T+0 当天**）

### 3.1 部署前 checklist

- [ ] 1.1 节的 `@TableField(exist = false)` 注解已删除（PmResolutionAuditEntity 第 51、57 行）
- [ ] 1.2 节 4 条 SQL 校验全部通过
- [ ] 当前 test profile（事实生产）的 `strategy_alert` 最近 7 天无连续 `LEADER_ROLLING_*` / `MAKER_ONLY_*` 类异常告警
- [ ] git 当前分支 `polymarket-5-min-weather` 的 untracked 文件 / 修改已 commit + push
- [ ] CI 通过（重点关注新增 17 个 service 类的单测：`PolymarketWebSocketClientTest`、`PolymarketWatchlistSubscriptionServiceTest`、`ShadowFillSimulationServiceTest`、`SmartWalletTradeEventVectorizerTest` 等）
- [ ] 备份当前 `coin_strategy` 数据库（含 4 张关键表：`smart_wallet_order`、`smart_wallet_position`、`smart_wallet_follow_task`、`pm_resolution_audit`）

### 3.2 构建产物

```bash
cd /path/to/CoinStrategy
mvn -DskipTests clean package        # 跳测速构（CI 已跑过测）
# 产物：
# coin-strategy-job/target/coin-strategy-job-*.jar
# coin-strategy-web/target/coin-strategy-web-*.jar
```

### 3.3 部署 job（核心策略服务）

```bash
# 1. 在 prod 机器上备份旧 jar
mv /opt/coinstrategy/job/coin-strategy-job.jar /opt/coinstrategy/job/coin-strategy-job.jar.bak.$(date +%F)

# 2. 上传新 jar
scp coin-strategy-job-*.jar prod-host:/opt/coinstrategy/job/coin-strategy-job.jar

# 3. 滚动重启（systemd 示例）
ssh prod-host 'systemctl restart coin-strategy-job'

# 4. 跟踪启动日志 60s，确认无致命异常
ssh prod-host 'journalctl -u coin-strategy-job -n 200 -f' &
# 等待出现：
#   - "Started CoinStrategyJobApplication"
#   - "GtcOrderTimeoutTracker init"（关键 — 启动期 replay）
#   - "MakerOnlyOrderTimeoutTracker init"
#   - 无 "BadSqlGrammarException"
```

### 3.4 部署 web（业务查询服务）

```bash
mv /opt/coinstrategy/web/coin-strategy-web.jar /opt/coinstrategy/web/coin-strategy-web.jar.bak.$(date +%F)
scp coin-strategy-web-*.jar prod-host:/opt/coinstrategy/web/coin-strategy-web.jar
ssh prod-host 'systemctl restart coin-strategy-web'
```

### 3.5 部署后 smoke test（5 分钟内完成）

```bash
# 1. 健康检查
curl -fsSL http://prod-host:8080/actuator/health
# 期望：{"status":"UP", ...}

# 2. 验证 UMA 字段已可写入（F-P1-12）
ssh db-host "mysql coin_strategy -e \"
  SELECT market_id, observed_at, uma_disputed, uma_dispute_observed_at
    FROM pm_resolution_audit
   WHERE observed_at >= NOW() - INTERVAL 10 MINUTE
   ORDER BY observed_at DESC
   LIMIT 5;\""
# 期望：能查到行（即便 uma_disputed 全是 0 也算正常，证明字段已生效）

# 3. 验证 F-P0-3 fail-closed 生效（集中度数据不可用时拒绝信号）
# 在 log 里 grep：
ssh prod-host 'grep "G9_CONCENTRATION_DATA_UNAVAILABLE" /opt/coinstrategy/job/logs/job-*.log | tail -5'
# 期望：偶发该 reason（数据可用时不出现），表明 fail-closed 在工作

# 4. 验证 W2-T2 leader rolling gate prod 已生效
ssh prod-host 'grep "LEADER_ROLLING_GATE" /opt/coinstrategy/job/logs/job-*.log | tail -5'
# 期望：能看到 ACTIVE / COOLING 状态日志
```

**❗ 若 1–4 任一不通过，立即按"六、紧急回滚"回滚到上一版本。**

---

## 四、灰度阶段编排（**T+1 至 T+30**）

> 总原则：**每个阶段开 1 个开关 → 观察 2~7d → 无异常进入下一阶段。任一阶段触发回滚标准时，立即切回 false 一行配置零重启。**

### 阶段 A（T+1 ~ T+3）：F-P1-3 GTC 全局超时

**操作**：在 `application-prod.yml` 的 `smart-wallet.execution-wallet` 段下加：
```yaml
execution-wallet:
  enable-gtc-global-timeout: true
  gtc-default-timeout-seconds: 60
```
然后滚动重启 `coin-strategy-job`。

**观察指标**：
```sql
-- A.1 tracker 表有数据
SELECT COUNT(*), cancel_status FROM pending_order_timeout_tracker
 WHERE created_at >= NOW() - INTERVAL 1 HOUR GROUP BY cancel_status;

-- A.2 cancel 成功率（成功 / 总数）
SELECT
  SUM(cancel_status='CANCELLED') / COUNT(*) AS cancel_success_rate,
  AVG(cancel_attempts) AS avg_attempts
  FROM pending_order_timeout_tracker
 WHERE created_at >= NOW() - INTERVAL 1 DAY;
```

**通过条件**：cancel_success_rate ≥ 95%，avg_attempts < 1.5，无 CANCEL_FAILED 堆积。

**回滚动作**：`enable-gtc-global-timeout: false` → 重启 job。

---

### 阶段 B（T+4 ~ T+7）：F-P1-15 MAKER 重报循环

**操作**：
```yaml
execution-wallet:
  enable-maker-price-followup: true
```
*（前置：阶段 A 已上线，因为重报依赖 `pending_order_timeout_tracker` 表）*

**观察指标**：
```bash
# B.1 日志 grep
ssh prod-host 'grep "MakerPriceFollowupService" /opt/coinstrategy/job/logs/job-*.log | tail -20'
# 期望：能看到"drift bps=XX, cancel + repost"日志

# B.2 重报成功率
ssh db-host "mysql coin_strategy -e \"
  SELECT COUNT(*) FROM smart_wallet_order
   WHERE create_time >= NOW() - INTERVAL 1 DAY
     AND remark LIKE '%maker-price-followup-repost%';\""
```

**通过条件**：MAKER 单成交率（fill_status=FILLED 占比）相比阶段 A 提升 ≥ 5pp，无双重撤单冲突日志。

**回滚动作**：`enable-maker-price-followup: false` → 重启 job。

---

### 阶段 C（T+8 ~ T+14）：F-P1-6 分批出场

**操作**：
```yaml
execution-wallet:
  enable-staged-exit: true
  partial-exit-ratios: [0.30, 0.30, 0.40]
  staged-exit-min-residual-ratio: 0.05
  staged-exit-min-leg-notional-usdc: 5
```

**观察指标**：
```sql
-- C.1 分批出场触发率
SELECT COUNT(*) staged_count
  FROM smart_wallet_order
 WHERE remark LIKE '%STAGED_LEG=%'
   AND create_time >= NOW() - INTERVAL 1 DAY;

-- C.2 平均退出滑点（avg_pnl_after_exit_fee 对比基线）
SELECT
  AVG(realized_pnl_usdc) AS avg_realized_pnl,
  COUNT(*) AS exit_count
  FROM smart_wallet_position
 WHERE position_status = 'CLOSED'
   AND updated_time >= NOW() - INTERVAL 1 DAY;
```

**通过条件**：分批出场触发率 ≥ 10%（小仓位会自动回退全平故不会 100%），平均 realized_pnl 不降低（≥ 阶段 B 基线 -2%）。

**回滚动作**：`enable-staged-exit: false`。

---

### 阶段 D（T+15 ~ T+28）：F-P1-11 OPPOSITE_BUY 反手建仓

**操作**：
```yaml
signal-risk:
  enable-auto-reverse-on-opposite-buy: true
  reverse-stake-ratio: 1.0
  reverse-stake-cap-usdc: 50
```

**观察指标**：
```sql
-- D.1 反手任务创建数
SELECT COUNT(*) FROM smart_wallet_follow_task
 WHERE intent_key LIKE 'REVERSE_FROM_%'
   AND created_at >= NOW() - INTERVAL 1 DAY;

-- D.2 反手任务通过率（status=EXECUTED / 总数）
SELECT
  SUM(status='EXECUTED') / COUNT(*) AS pass_rate
  FROM smart_wallet_follow_task
 WHERE intent_key LIKE 'REVERSE_FROM_%'
   AND created_at >= NOW() - INTERVAL 14 DAY;
```

**通过条件**：反手任务通过率 ≥ 30%（剩余 70% 应该被 13 Gate 中的 G2c / G11 等正确拒绝，证明 risk gate 没被绕过）；反手任务 PnL ≥ 0。

**回滚动作**：`enable-auto-reverse-on-opposite-buy: false`。已 PENDING 的反手 task 由既有调度链路按常规风控自然处理（不丢单也不漏判）。

---

### 阶段 E（任意时间，依赖 ops）：F-P1-9 飞书 audit sink

**前置**：运维提供
1. 飞书机器人 webhook URL（含 token）
2. 飞书机器人 secret（若启用了签名校验）

**操作**：
```yaml
audit:
  feishu-sink-enabled: true
  feishu-webhook-url: "https://open.feishu.cn/open-apis/bot/v2/hook/XXXX"
  feishu-webhook-secret: "YOUR_HMAC_SECRET"   # 无签名校验时留空
  feishu-webhook-timeout-ms: 3000
  feishu-dedupe-interval-ms: 300000
  feishu-allowed-severities: "WARN,CRITICAL"
```

**观察指标**：
1. 故意触发一条 WARN 级告警（例如临时把 `enable-concentration-fail-open=false` + 让 G9 数据不可用），看飞书群是否收到卡片
2. 落库的 `strategy_alert` 表条数不变（飞书是装饰，不替代落库）

**通过条件**：告警卡片正常推送到群、含完整 alertType / level / message / metric 字段、签名校验通过（若启用）。

**回滚动作**：`feishu-sink-enabled: false`（webhook 故障时也安全 —— sink 内部 swallow 失败不影响主流程，可不立即回滚）。

---

### 阶段 F：WebSocket 链路（依赖运维联调 Polymarket WS endpoint）

> ⚠️ 这是本批次**风险最高且依赖外部最强**的部分。运维联调成功前请勿打开任何 WS 开关。

#### F-0 联调前置（运维侧）

```bash
# 1. 验证 Polymarket WS endpoint 可达
wscat -c wss://ws-subscriptions-clob.polymarket.com/ws/market
wscat -c wss://ws-subscriptions-clob.polymarket.com/ws/user

# 2. 确认 user channel 是否需要 auth token；若需要，向 Polymarket 获取并配置：
#    polymarket.ws-client-user-channel-auth-token: "<token>"

# 3. 确认 subscribe payload schema 与代码默认假设一致：
#    默认假设 market: {"type":"market","markets":["tokenId1",...]}
#    默认假设 user:   {"type":"user","auth":{"apiKey":"..."}, "markets":[...]}
#    若 Polymarket 实际 schema 不同，需先修改
#    PolymarketWebSocketClient.buildMarketSubscribePayload / buildUserSubscribePayload
```

#### F-1（T+30 起，仅 client 连接）：仅打开 WS client，consumer 继续走 polling

```yaml
polymarket:
  enable-ws-client: true
  # 其他三个 WS 开关保持 false
```

**观察 2d**：
```bash
# F-1.1 通过 /audit endpoint 看 PolymarketWebSocketClient.getStats()
curl -fsSL http://prod-host:8080/audit/ws/stats
# 期望：marketConnected=true, userConnected=true, reconnectAttempts < 5, sendErrors = 0
```

**通过条件**：marketConnected && userConnected 持续 > 99%，sendErrors ≈ 0。

#### F-2（T+32）：打开自动订阅

```yaml
polymarket:
  enable-ws-auto-subscribe: true
```

**观察 2d**：
```bash
curl -fsSL http://prod-host:8080/audit/ws/auto-subscribe-stats
# 期望：subscribedTokenCount > 0（接近 HOT 钱包数 × 2，因为每 conditionId 通常 2 个 outcome）
#       subscribedWalletCount = HOT 钱包数
#       tokenLookupFailures < 5% × subscribedTokenCount
```

#### F-3（T+34）：切换 consumer 端到 WS 推流

```yaml
polymarket:
  enable-ws-hot-path-snapshot: true
  enable-ws-trade-event-push: true
```

**观察 7d**：
```bash
# F-3.1 hot-path 命中率
curl -fsSL http://prod-host:8080/audit/ws/hot-path-stats
# 期望：hotPathWsHits / (hotPathWsHits + hotPathDbFallbacks) > 80%

# F-3.2 trade event 推流命中率
curl -fsSL http://prod-host:8080/audit/ws/trade-event-stats
# 期望：forwarded / received > 80%, deduped 占比 < 30%, parseFailures < 1%
```

**通过条件**：F-3.1 / F-3.2 全部达标。

**回滚动作**：任一阶段失败 → 把对应开关切回 false → 重启 job → 自动回落到 45s polling 链路（零数据丢失）。

---

## 五、监控与告警建议

部署后 14 天内强烈建议加以下监控（手动 SQL 巡检或 Prometheus + Grafana）：

| 指标 | 阈值 | 关联开关 |
|------|------|---------|
| `pending_order_timeout_tracker` 中 `cancel_status=CANCEL_FAILED` 行数 | > 10 / 小时告警 | F-P1-3 |
| `pending_maker_only_tracker` 中 `status=FAILED` 行数 | > 10 / 小时告警 | F-P1-4 |
| `strategy_alert` 中 `G9_CONCENTRATION_DATA_UNAVAILABLE` 频率 | > 100 / 天告警（说明集中度上游不稳定）| F-P0-3 |
| `smart_wallet_follow_task.intent_key LIKE 'REVERSE_FROM_%'` 通过率 | < 10% 告警（说明反手 risk gate 拒绝过多）| F-P1-11 |
| WS client `sendErrors` / `reconnectAttempts` | sendErrors > 10 / h 或 reconnect > 5 / h | F-P1-7/8 |
| `hotPathWsHits / (hits + fallbacks)` | < 50% 告警 | F-P1-7 |
| `pm_resolution_audit.uma_disputed=1` 总数 | 7d 后仍为 0 告警（怀疑 UMA 上游字段名不对）| F-P1-12 |

---

## 六、紧急回滚

### 6.1 单开关回滚（首选）

按"四"节中每个阶段标注的 "回滚动作" 把对应开关切回 false → 滚动重启 job。**所有开关都是软回滚**，已落库的数据不会清理但不会再增长，新数据走旧路径，无需 DDL 回滚。

### 6.2 全量代码回滚（最坏情况）

```bash
# 1. 还原 jar
ssh prod-host '
  mv /opt/coinstrategy/job/coin-strategy-job.jar.bak.YYYY-MM-DD /opt/coinstrategy/job/coin-strategy-job.jar
  systemctl restart coin-strategy-job
'

# 2. DDL 不需要回滚（pending_order_timeout_tracker / pending_maker_only_tracker
#    和 uma_disputed / uma_dispute_observed_at 两列对旧代码完全无害，旧代码不读不写）
```

### 6.3 Java 代码层兜底安全保证（设计上）

所有新增的"开关 ON"路径在异常时都有 fallback：
- WS client 失败 → 自动回落 45s polling
- MAKER repost 失败 → GtcOrderTimeoutTracker 兜底 60s 撤单
- 分批出场 leg 失败 → 下次扫描 planner 仍能继续推进
- 飞书 webhook 失败 → 仅落 strategy_alert 表，不阻塞 audit job
- 实体 primary wallet 查询失败 → swallow + log warn，下游 primaryWalletAddress 永不 null

故"系统挂掉"的风险面非常窄，主要风险在"开关 ON 后真实 PnL 退化"，这是观察指标的核心。

---

## 七、本批次未完成的事项（**不在本部署范围**）

以下属于 P2 长期治理或外部依赖，本次不部署：

| 项 | 阻塞原因 |
|----|---------|
| F-P1-1 第二阶段 prod 灰度观察 | 需要 14 天 cooling 比例数据 + W1-T4 shadow 周报，纯时间窗 |
| F-P2-1 ~ F-P2-23 | 长期治理项，建议下个 sprint 启动（详见 `minimal-implementations-fix-plan.md` §四 路线图）|
| 飞书 webhook 联调 | 等 ops 提供 URL |
| Polymarket WS 真实联调 | 等 ops 联调 endpoint + auth token |

---

## 八、问题排查 FAQ

### Q1：部署后 Job 启动报 `BadSqlGrammarException: Unknown column 'uma_disputed'`

→ 第一节 1.1 注解没删干净。回到注解状态 + 重启 job，确认 SQL 已上库后再次删注解。

### Q2：`GtcOrderTimeoutTracker.replayPendingFromDb()` 启动期日志出现 NPE

→ DDL 未上库，但 `enable-pending-tracker-persistence=true`。临时把这行设 false 退化到 in-memory，等 DDL 上库后再切回。

### Q3：阶段 A 上线后，`pending_order_timeout_tracker` 行数飙升但 cancel 成功率 < 50%

→ 检查 CLOB API 速率限制，把 `gtc-cancel-scan-interval-ms` 从 3000 调到 5000；或临时回滚 F-P1-3。

### Q4：阶段 D（反手）上线后，反手任务通过率 0%

→ 多半是反向 outcome 的 market_info 不存在，看 log 中是否有 `REVERSE_LEG SKIPPED: REVERSE_MARKET_INFO_MISSING`。如果是，说明 `smart_wallet_market_info` 表里只缓存了 leader 这一侧的 outcome；需要先 backfill 反向 outcome 数据，或暂时关闭 F-P1-11。

### Q5：F-3 阶段 hot-path WS 命中率长期 < 30%

→ 说明 WS 推流不稳定。先关 `enable-ws-hot-path-snapshot`（回到 polling），保留 `enable-ws-client=true` 继续观察连接稳定率；确认 WS 链路完全稳定后再次切换 consumer。

---

## 九、上线签收

| 项 | 责任人 | 完成日期 | 备注 |
|---|--------|---------|------|
| 删 `@TableField(exist=false)` 两处 | dev | __________ | |
| SQL sanity check 1.2.1–1.2.4 | dba | __________ | |
| 备份数据库 | dba | __________ | |
| 部署 job / web | ops | __________ | |
| Smoke test 3.5 | dev + ops | __________ | |
| 阶段 A | dev | __________ | 通过条件 cancel_success_rate ≥ 95% |
| 阶段 B | dev | __________ | MAKER 成交率 +5pp |
| 阶段 C | dev | __________ | realized_pnl 不降低 |
| 阶段 D | dev | __________ | 反手 PnL ≥ 0 |
| 阶段 E | ops + dev | __________ | 飞书卡片可推送 |
| 阶段 F-1 / F-2 / F-3 | ops + dev | __________ | hot-path WS 命中率 > 80% |

---

> 维护者：跟单策略 owner
> 关联文档：
> - [`minimal-implementations-fix-plan.md`](./minimal-implementations-fix-plan.md)（实施清单与修复计划）
> - [`prod-deployment-checklist-w2-t6.md`](./prod-deployment-checklist-w2-t6.md)（W2-T6 / 价差 P70 prod 上线 checklist，与本文件可并行参考）

---

<!-- kb-backlink -->
> 本文归属：[Polymarket 知识地图](../MOC-Polymarket.md) ｜ [知识库首页](../知识库首页.md)
