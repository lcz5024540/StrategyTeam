# Prod 部署 Checklist — W2-T6 Leader 行为漂移门 + W2-T1.4 价差 P70 门

> **状态**：2026-05-16 第十三批生成。Prod 暂未真正上线（test profile 仍是事实生产）。本文档作为 prod 真正启动时的 step-by-step 部署 checklist。
>
> **配套配置**：`coin-strategy-job/src/main/resources/application-prod.yml#smart-wallet.signal-risk`
>
> **风控双门**：W2-T6（行为漂移）+ W2-T1.4（价差 P70）阈值在 prod 比 test 更保守一档。

---

## 一、前置依赖（必须全部通过才能上 prod）

### 1.1 数据基础设施

| 表 | 校验 SQL | 通过条件 |
| --- | --- | --- |
| `smart_wallet_trade_event.trace_id` | `SELECT COUNT(*) FROM smart_wallet_trade_event WHERE trace_id IS NOT NULL AND created_at >= NOW() - INTERVAL 7 DAY;` | ≥ 1000（确认 W1-T3 链路稳定） |
| `pm_resolution_audit` | `SELECT COUNT(DISTINCT market_id) FROM pm_resolution_audit WHERE observed_at >= NOW() - INTERVAL 7 DAY;` | ≥ 50 |
| `pm_market_liquidity_percentile_daily` | `SELECT COUNT(DISTINCT category) FROM pm_market_liquidity_percentile_daily WHERE as_of_date >= CURRENT_DATE - INTERVAL 7 DAY;` | ≥ 5 个品类 × ≥ 7 天 |
| `smart_wallet_behavior_profile.behavior_vector_json` | `SELECT COUNT(*) FROM smart_wallet_behavior_profile WHERE behavior_vector_json IS NOT NULL AND behavior_vector_computed_at >= NOW() - INTERVAL 1 HOUR;` | ≥ 30 个 leader |
| `live_replay_diff_history` | `SELECT COUNT(*) FROM live_replay_diff_history WHERE as_of_date >= CURRENT_DATE - INTERVAL 7 DAY;` | ≥ 7 行 |
| `smart_wallet_signal_shadow.status=SETTLED` | `SELECT COUNT(*) FROM smart_wallet_signal_shadow WHERE status='SETTLED' AND shadow_settled_at >= NOW() - INTERVAL 14 DAY;` | ≥ 100（W1-T4 闭环验证） |

### 1.2 Job 健康

确认以下 Job 在 test profile 连续 7 天无 ERROR：

```bash
grep -E '\[(LiveReplayDiff|ShadowVsLive|SettlementSourceAudit|SlippagePostFact|LeaderDriftAggregation|MarketLiquidityPercentileSnapshot|ShadowSettlementBackfill|ResolutionTriDiff)' \
  /opt/coinstrategy/smart-wallet/logs/job-*.log \
  | grep -E 'ERROR' | tail -50
```

期望：**没有连续多日 ERROR**。

### 1.3 灰度数据验证

- `strategy_alert WHERE strategy_id='SMART_WALLET_AUDIT'` 最近 7 天的 `AUDIT_LEADER_DRIFT` 与 `AUDIT_SLIPPAGE_POSTFACT` **未连续触发**（同 alertType 未恢复告警 ≤ 1 条 / 周）
- `live_replay_diff_history` 最近 7 天的 `pnl_diff_exceeded=true` 占比 ≤ 30%
- 影子记账周报（log `[ShadowVsLiveWeeklyReport]`）显示"被拒信号若放行的累计 PnL" < 真实 PnL（证明门控有效）

---

## 二、Prod 启用配置变更（已就位）

`application-prod.yml#smart-wallet.signal-risk`（已在第十一/十二批写入）：

```yaml
smart-wallet:
  signal-risk:
    # W2-T6 Leader 行为漂移门（prod 阈值比 test 更保守一档）
    enable-leader-drift-gate: true
    leader-drift-min-samples: 20          # test=15
    leader-drift-cosine-threshold: 0.55   # test=0.50
    leader-drift-severe-cosine-threshold: 0.75  # test=0.70
    leader-drift-stake-multiplier: 0.25
    manual-allowlist-bypass-leader-drift-gate: true

    # W2-T1.4 价差 P70 门
    enable-spread-p70-gate: true
    spread-p70-min-samples: 8             # 默认 6
    spread-p70-downgrade-factor: 1.30     # 默认 1.20
    spread-p70-reject-factor: 2.00        # 默认 1.80
    spread-p70-downgrade-multiplier: 0.50

    # W2-T1 流动性 P30 滚动门
    enable-liquidity-rolling-percentile: true
    liquidity-rolling-min-samples: 10     # 默认 6
```

---

## 三、上线步骤（按顺序执行）

### 3.1 部署前（前一天）

- [ ] 确认 1.1 / 1.2 / 1.3 全部通过
- [ ] 备份当前 `coin_strategy` 数据库
- [ ] 准备 prod 服务器（test 在 49.51.250.174，prod 用别的机器或别的端口）
- [ ] 准备 prod 独立 DB user 或 schema（避免 test/prod 混用）

### 3.2 部署当天

1. **DB migration（如未执行过）**：
   ```bash
   mysql -h <PROD_DB> -u <user> -p <db> < sql/polymarket/smart_wallet_audit_observability_schema.sql
   mysql -h <PROD_DB> -u <user> -p <db> < sql/polymarket/pm_resolution_audit_migration.sql
   ```
2. **启动 prod profile job**：
   ```bash
   SPRING_PROFILES_ACTIVE=prod \
     DB_HOST=<PROD_DB> DB_USERNAME=<user> DB_PASSWORD=<pwd> \
     JOB_SERVER_ADDRESS=127.0.0.1 \
     ./run.sh
   ```
3. **观察 30 分钟**：
   ```bash
   tail -f /opt/coinstrategy/smart-wallet/logs/job-current.log \
     | grep -E '\[(LEADER_DRIFT|SPREAD_P70|LIQUIDITY_ROLLING)'
   ```
   期望：看到 ACTIVE 状态居多，DRIFTED/SEVERE 占比 ≤ 5%。
4. **2 小时内**：
   - 检查 `strategy_alert WHERE strategy_id='SMART_WALLET_AUDIT' AND created_at >= NOW() - INTERVAL 2 HOUR;`
   - 期望：≤ 2 条新告警
5. **24 小时内**：
   - `live_replay_diff_history` 当日是否有记录
   - `smart_wallet_signal_shadow` 当日新增 REJECTED 记录数与 test 同窗口对比 ≤ 1.5×

### 3.3 异常处理（即时回退路径）

任意指标超阈值 → **一行配置回退**：

```yaml
smart-wallet:
  signal-risk:
    enable-leader-drift-gate: false     # 关 W2-T6
    enable-spread-p70-gate: false       # 关 W2-T1.4
    enable-liquidity-rolling-percentile: false  # 关 W2-T1
```

回退后 **无需重启**（依赖 Spring `@RefreshScope` 或下次 hot reload；若依赖重启，5 分钟内服务恢复至改动前行为）。

---

## 四、上线后 7 天观察期

每天 09:00 跑一次本 checklist 4.1 数据指标：

### 4.1 关键指标

| 指标 | SQL / 来源 | 阈值 |
| --- | --- | --- |
| Live↔Replay pnl_diff_bps | `SELECT pnl_diff_bps FROM live_replay_diff_history WHERE as_of_date = CURRENT_DATE - INTERVAL 1 DAY;` | < 100 bps |
| 信号缺失率 | 同上 `signal_miss_pct` | < 5% |
| W2-T6 DRIFTED+SEVERE 触发率 | `SELECT COUNT(*) FROM smart_wallet_signal_shadow WHERE decision_status IN ('DOWNGRADED','REJECTED') AND reject_layer LIKE 'G2c%' AND created_at >= NOW() - INTERVAL 1 DAY;` | ≤ 10% × 当日 follow_task 总数 |
| W2-T1.4 SPREAD_P70 触发率 | 同上 `reject_layer LIKE 'G11_SPREAD_P70%'` | ≤ 15% × 当日 follow_task 总数 |
| 平均跟单延迟 | trace_id 端到端时间（W1-T3 trace 接口） | ≤ 5s（leader trade_event → CLOB order placed） |
| AUDIT_* 告警 | `strategy_alert WHERE strategy_id='SMART_WALLET_AUDIT' AND created_at >= NOW() - INTERVAL 1 DAY` | ≤ 3 条 / 日 |

### 4.2 与 test profile 对比基线

- prod 单笔平均 PnL ≥ test 单笔平均 PnL × 0.90（允许保守阈值带来轻微 PnL 下移）
- prod 跟单成交率 ≥ test 跟单成交率 × 0.80

未达基线 → 调整 prod 阈值（往 test 靠拢一档：minSamples 20→15、cosine 0.55→0.50）。

---

## 五、长期维护

### 5.1 月度复盘

每月 1 日跑 web 接口：
```bash
curl 'http://<prod-web>/strategy/api/smart-wallet/audit/live-replay-diff/history?limitDays=30'
curl 'http://<prod-web>/strategy/api/smart-wallet/audit/leader-drift-aggregation?lookbackHours=720'
curl 'http://<prod-web>/strategy/api/smart-wallet/audit/resolution-tridiff?lookbackHours=720'
```

输出存档到 `docs/polymarket/prod-monthly-report-YYYY-MM.md`。

### 5.2 阈值调整原则

- DRIFTED/SEVERE 触发率连续 2 周 < 1% → 阈值可放宽（cosine 0.55 → 0.50，min_samples 20 → 15）
- DRIFTED/SEVERE 触发率连续 2 周 > 15% → 阈值收紧 + 排查异常 leader（可能账号被盗）
- SPREAD_P70 触发率连续 2 周 > 30% → 重新审视 daily 表的 spread_bps_p70 是否合理（可能 sample_count 不足）

---

## 六、相关链接

- 当前主决议：`docs/polymarket/copy-trading-improvement-decision-2026-05-17.md`
- W2 风控门控精华：`docs/polymarket/audit-observability/w2-risk-gates.md`
- W2-T2 灰度 SOP：`docs/polymarket/audit-observability/w2-t2-grayscale-sop.md`
- 全部 SQL schema：`sql/polymarket/smart_wallet_audit_observability_schema.sql`
- Web 接口列表（21 个端点）：`docs/polymarket/audit-observability/web-bff-interfaces.md`

> **变更追踪**：本 checklist 每次 prod 阈值调整后需 update version；prod 真正部署当天添加 "首次部署日志" 章节。
