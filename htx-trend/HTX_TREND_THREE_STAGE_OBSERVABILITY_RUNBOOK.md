# HTX 趋势三阶段评估与回放操作说明

本工具只解释冻结证据，不能改变开仓、仓位、退出或任何实盘开关。

## 运行时账本

`htx_trend_assessment` 只追加三类记录：

- `ENTRY_ASSESSMENT`：组合候选在实时 Gate 前的趋势证据。
- `ENTRY_OUTCOME`：该候选最终在哪个 Gate 被拦截，或是否成功提交订单。
- `HOLDING_ASSESSMENT` / `TREND_TRANSITION`：每根已完成 15 分钟 K 线上的持仓趋势状态，以及实际状态转换。

同一 `position_id + bar_time` 使用稳定键去重。重启、重试不产生新的评估或虚假的转换。

## 冻结回放输入

离线命令读取一个由数据库账本和对应冻结 K 线共同导出的 JSON。根字段必须是：

```json
{
  "dataIntegrity": "COMPLETE",
  "observations": [{
    "primaryModel": "FIB_PULLBACK_CONTINUATION",
    "direction": "LONG",
    "marketRegime": "BROAD_UP",
    "timeframeProfile": "INTRADAY_15M",
    "maxFavorableR": 1.2,
    "mae": -0.4,
    "givebackRate": 0.25,
    "holdingBars": 8,
    "structureTargetReached": true,
    "invalidationReached": false,
    "transitions": ["TRENDING_TO_WEAKENING"],
    "netPnl": 1.0,
    "terminalState": "DONE",
    "dataIntegrity": "COMPLETE"
  }]
}
```

`dataIntegrity` 不是 `COMPLETE`，或任一观察窗口为 `PARTIAL` 时，报告固定为 `INSUFFICIENT_EVIDENCE`，不得用于调参。

```bash
java -cp <应用依赖> com.metaverse.coin.strategy.htx.replay.HtxTrendAssessmentReplayCommand \
  --input=<冻结投影.json> --output=<报告.json>
```

报告按“模型 × 方向 × 市场状态 × 周期画像”输出样本数、先到 `+0.5R/+1R`、结构目标和失效价的比例、MFE、MAE、回吐、持仓时长、扣费后净收益、PF、平均盈亏比、最大回撤，以及状态转换矩阵和转换后的结果。`observations` 必须按仓位终态时间升序冻结；缺少结构目标或失效价字段时固定输出 `INSUFFICIENT_EVIDENCE`。

## v4 退出验证

v4 仍是唯一退出引擎，版本号仍为 `fib-exit-engine-v4`。每次 v4 评估会读取当前持仓评估状态及上一个持仓状态，并把引用键写回退出决策证据；它不创建 v5，也不改变现有真实退出开关。

上线前必须单独执行追加式迁移 `sql/htx/2026-10-06__htx_trend_three_stage_assessment.sql`。服务启动保持 `spring.sql.init.mode=never`。
