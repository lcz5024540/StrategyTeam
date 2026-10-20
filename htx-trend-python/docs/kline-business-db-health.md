# Business K-line health（文档与纯函数夹具）

固定 universe：BTC-USDT、ETH-USDT × 15min、60min、4hour、1day，共 8 组。
`runtime_kline_business.business_kline_health(rows, now=aware_datetime)` 无 I/O、无告警或重启。
每组分别求 max(bar_time) 与 max(captured_at)，两者可能来自不同记录。
bar_time 为 UTC epoch 秒的开盘时间；capture 为版本首次被观察时间，重复 INSERT IGNORE 不刷新。
缺任一组返回 MISSING；将来时间返回 INVALID_FUTURE；任一 lag 超限为 STALE。

建议阈值（仅口径，不接自动运维）：

| period | bar lag 上限 | capture lag 上限 |
| --- | --- | --- |
| 15min | 1920 秒 | 1020 秒 |
| 60min | 7320 秒 | 3720 秒 |
| 4hour | 28920 秒 | 14520 秒 |
| 1day | 172920 秒 | 86520 秒 |

公式：bar lag <= 2×period+120；capture lag <= period+120，两者都满足才 HEALTHY。
bar lag 包含一根已闭合 bar 的周期长度；低频周期重复读取同一版本不会刷新 capture。
健康不等于全窗口完整、无缺口，也不代表 worker 用库判单；历史回填不保证当前健康。

DATETIME 不携带时区。本实现明确按 Asia/Shanghai 保存/解释 captured_at；输入 aware 时间
先转换再剥去 tzinfo。参考 Java LocalDateTime.now 和 application.yml 的 JDBC
serverTimezone=Asia/Shanghai；这只是代码配置依据，未查询现网 JVM/DB 时区。
生产启用前须另外核对旧数据时区，否则跨旧/新数据的 lag 不可比较。

审计查询口径（本任务未执行）：

```sql
SELECT contract_code, period, MAX(bar_time) AS max_bar_time,
       MAX(captured_at) AS max_captured_at
FROM htx_trend_runtime_kline_candle
WHERE contract_code IN ('BTC-USDT', 'ETH-USDT')
  AND period IN ('15min', '60min', '4hour', '1day')
GROUP BY contract_code, period;
```

纯函数输入为逐行 candle 数据；若使用上述聚合结果，先把 max_bar_time/max_captured_at
映射为 bar_time/captured_at。未返回的组也必须报告 MISSING。
测试 `tests/test_kline_business_db.py::test_health_frozen_clock_independent_maxima`
使用冻结时钟覆盖 HEALTHY、STALE、MISSING、INVALID_FUTURE；不依赖生产 DB。
