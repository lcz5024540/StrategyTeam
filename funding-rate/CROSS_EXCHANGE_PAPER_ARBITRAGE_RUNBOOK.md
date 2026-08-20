# 跨交易所资金费率套利 V1（Paper）验收手册

## 1. 安全边界

- 仅支持 `DRY_RUN`，代码中不存在 `LIVE` 模式切换。
- 四个交易所 Connector 只调用公共行情 API，私有下单方法固定抛出异常。
- 自动扫描、合约同步、健康检查、持仓恢复、持仓监控和模拟结算默认全部关闭。

## 2. 初始化

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 17)
export PATH="$JAVA_HOME/bin:$PATH"
mvn -pl coin-strategy-job -am package -DskipTests
```

数据库迁移必须先确认目标验收库，再由环境负责人执行：

```bash
mysql --host=<验收库地址> --user=<账号> --password < sql/2026-08-20__cross_exchange_funding_arbitrage_v1.sql
```

在本地配置中开启：

```yaml
arbitrage:
  multi-exchange:
    scan-enabled: true
    contract-sync-enabled: true
    health-check-enabled: true
    recovery-enabled: true
    monitor-enabled: true
    settlement-enabled: true
```

启动 `coin-strategy-job` 后，接口前缀为 `/strategy/api`。

## 3. 验收步骤

1. `POST /strategy/api/arbitrage/funding/scan?symbol=BTCUSDT`，首次可能为空；同方向机会稳定 3 分钟后返回 `APPROVED`。
2. `GET /strategy/api/arbitrage/funding/exchanges/health`，确认每家交易所的成功/失败次数与最近错误可见。
3. `GET /strategy/api/arbitrage/funding/opportunities`，确认机会已落库且包含多空交易所、费率差、净收益和名义容量。
4. 从扫描结果取得服务端生成的 `opportunityId`，调用 `POST /strategy/api/arbitrage/funding/paper/open`，请求体为 `{ "opportunityId": "<id>", "notional": 100 }`。接口不接受客户端拼装机会，过期、未批准的 ID 或非正数 `notional` 会被拒绝。
5. `GET /strategy/api/arbitrage/funding/positions`，确认状态为 `OPENED`，双腿数量相同。
6. `POST /strategy/api/arbitrage/funding/paper/close?positionId=<id>`，确认状态转为 `CLOSED`。
7. `POST /strategy/api/arbitrage/funding/pause?paused=true`，确认新 Paper 开仓被拒绝；传 `false` 恢复。
8. 访问 `/strategy/api/actuator/prometheus`，确认以下指标可见：
   - `funding_arb_scans_total`、`funding_arb_opportunities_total`
   - `funding_arb_active_positions`、`funding_arb_unhedged_notional`
   - `funding_arb_rollbacks_total`、`funding_arb_manual_interventions_total`
   - `funding_arb_exchange_healthy{exchange="..."}`
   - `funding_arb_exchange_request_success_ratio{exchange="..."}`
   - `funding_arb_exchange_last_latency_ms{exchange="..."}`
   - `funding_arb_exchange_rate_limits_total{exchange="..."}`

## 4. 数据核验

```sql
SELECT * FROM cross_exchange_arb_opportunity ORDER BY id DESC LIMIT 20;
SELECT * FROM cross_exchange_arb_position ORDER BY id DESC LIMIT 20;
SELECT * FROM cross_exchange_arb_order ORDER BY id DESC LIMIT 40;
SELECT * FROM cross_exchange_funding_settlement ORDER BY id DESC LIMIT 20;
SELECT * FROM cross_exchange_risk_snapshot ORDER BY id DESC LIMIT 20;
```

`cross_exchange_risk_snapshot.check_stage = 'STATE_TRANSITION'` 的记录是 Paper 双腿状态迁移审计，
`reason` 依次记录 `CREATED`、`OPENING_LONG/OPENING_SHORT`、补偿状态以及最终状态。

验收不通过条件：出现真实交易所订单、关键行情字段用常量代替、单腿成交未补偿、重复 client order ID、未记录的活动裸仓，或 Java/Maven 未使用 JDK 17。

## 5. 已知环境差异

当前开发环境可直连 Binance、Bitget；OKX 请求出现超时，HTX 域名连接失败。扫描器按交易所隔离失败并记录健康状态，部署环境需确认四所域名均可访问，才能验证四所共同币种集合。

数据库迁移必须由验收环境负责人执行。本地未配置 `DB_PASSWORD`，且默认地址可能指向共享环境，开发验证不会自动执行 SQL。
