# 资金回收退出方案（Capital Recycling Exit）

## 背景

Polymarket 跟单策略中，大量订单在未触发止盈/止损的情况下会一直持有到市场结束才能结算，导致资金利用率过低。需要一种机制主动释放"僵尸仓位"占用的资金。

## 核心思路

在现有 TP/SL/TimeWindow/FollowExit 之外，新增第五种退出机制——"资金回收退出"。针对长时间持有、ROI 在死区（既不盈利也不亏损）、占用资金却没有明显方向的仓位，主动评估并释放资金。

## 触发条件（必须同时满足）

1. 持仓时长超过 `holdingPeriodHours`（默认 8 小时，可配置 4~24h）
2. 当前 ROI 在"死区"内：`deadZoneLowRate` ~ `deadZoneHighRate`（默认 -5% ~ +8%）
3. 资金回收评分 ≥ `recycleThreshold`（默认 60 分）

## 评分模型（0~100 分）

| 因子 | 权重 | 计算逻辑 |
|------|------|----------|
| 持仓时长 | 40 分 | `min(holdingHours / 24, 1.0) × 40`，持仓越久分越高 |
| 资金占用比 | 25 分 | `(positionCost / totalOpenCost) × 25`，占总资金比越高越优先回收 |
| 市场流动性 | 15 分 | 价差大/成交量低 → 满分（流动性差的市场趁早退出） |
| 机会成本 | 20 分 | 当前排队信号数 / 最大仓位数 × 20（有好信号等着进场时提高分数） |

## 安全护栏

- `minRetainPositions = 3`：至少保留 3 个仓位不回收
- `maxRecyclePerRound = 3`：每个扫描周期最多回收 3 个仓位
- `dailyRecycleBudgetUsdc = 100`：每日回收金额上限
- `cooldownMinutes = 30`：同一仓位被评估后至少等 30 分钟再次评估
- ROI > +5% 时优先等待 TP 机制

## 执行策略

- 小仓位（< $20）：全量一次性卖出
- 中仓位（$20~50）：分 2 笔卖出，间隔 15 秒
- 大仓位（> $50）：分 3 笔卖出，间隔 30 秒
- 退出标记：`exitReason = "CAPITAL_RECYCLE_AUTO_EXIT"`

## 配置参数

```yaml
smart-wallet:
  signal-risk:
    capital-recycle:
      enabled: true
      holdingPeriodHours: 8
      deadZoneLowRate: -0.05
      deadZoneHighRate: 0.08
      recycleThreshold: 60
      maxRecyclePerRound: 3
      minRetainPositions: 3
      dailyRecycleBudgetUsdc: 100
      cooldownMinutes: 30
```

## 实现文件清单

| 步骤 | 文件 | 改动 |
|------|------|------|
| 1 | `SmartWalletProperties.java` | 新增 `CapitalRecycle` 配置内部类 |
| 2 | 新建 `CapitalRecycleExitService.java` | 核心评分逻辑 + 触发判断 |
| 3 | `SmartWalletPositionSyncService.java` | 在主循环中调用资金回收检查 |
| 4 | `SmartWalletAccountingService.java` | 支持新退出原因 |
| 5 | 前端 `OrdersTable.tsx` | 新增"资金回收"标签 |

## 执行流程

```
每5分钟扫描 →
  筛选候选仓位（持仓>8h && ROI在死区）→
    计算评分（时长+资金占比+流动性+机会成本）→
      score >= 60 且通过护栏检查 →
        按仓位大小分批卖出 →
          记录 exitReason = CAPITAL_RECYCLE_AUTO_EXIT
```

## 日期

2026-05-02
