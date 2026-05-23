---
title: "跟单策略优化设计文档"
type: design
status: active
tags: [polymarket, copy-trading, design]
created: 2026-04-22
updated: 2026-04-27
---

# 跟单策略优化设计文档

**日期**：2026-04-22  
**方案**：B（动态 Kelly 仓位 + 时间感知止损 + PnL 归因分析）  
**后续演进**：方案 C（ML 驱动退出决策）

---

## 1. 问题背景

测试环境数据呈现以下特征：

- **止盈订单占比大**：大量仓位在 +60% ROI 时被取出，整体胜率看似较高
- **胜率高但亏损严重**：少数"大亏"仓位（市场结算时价格归零）的损失远超止盈积累的收益
- **归因数据缺失**：无法定位哪类信号产生了亏损、亏损与入场质量/仓位规模的关系

根因分析：

1. 止损在上次提交前未启用（Bug 已修复），部分历史仓位持有至结算归零
2. 现有固定止损阈值（-35%）在预测市场结算前的快速收敛阶段可能来不及触发
3. 仓位规模计算未反映单笔信号的真实置信度（Kelly 维度缺失），导致高/低信心信号下注额趋同

---

## 2. 设计目标

| 目标 | 衡量标准 |
|------|---------|
| 建立归因分析能力 | 能按钱包质量、信号置信度、市场剩余时间分组查询胜率/盈亏 |
| 降低单笔最大亏损 | 时间感知止损使结算前大幅亏损减少 |
| 仓位规模与置信度挂钩 | Kelly 计算后低信心信号下注额明显缩减 |
| 为方案 C 预留接入点 | `ExitSignalProvider` 接口无需改调用链即可替换退出逻辑 |

---

## 3. §1 PnL 归因分析层

### 3.1 新增组件

**`FollowTradeAttributionService`**（只读，不影响执行路径）

聚合 `SmartWalletPositionEntity` + 关联 follow task 决策字段，按以下维度分组：

| 维度 | 数据来源 |
|------|--------|
| 钱包评分段（低 <0.72 / 中 0.72-0.85 / 高 >0.85） | `SmartWalletProfileEntity.walletScore` |
| 共识质量（单钱包 / 弱共识 2 钱包 / 强共识 3+） | follow task 关联的 `dedupedEntityCount` |
| 入场 calibratedFollowProbability 区间（<0.6 / 0.6-0.75 / >0.75） | follow task 决策字段 |
| 入场时市场剩余时间（<24h / 1-7 天 / >7 天） | `marketEndAt - executedAt` |
| 退出原因 | `SmartWalletOrderEntity.exitReason`（TAKE_PROFIT / STOP_LOSS / SETTLEMENT / MANUAL） |

**`FollowTradeAttributionView`** 输出字段：

```
groupKey          // 分组维度描述
tradeCount        // 笔数
winRate           // 胜率
avgPnlUsdc        // 平均盈亏（USDC）
totalPnlUsdc      // 总盈亏（USDC）
avgHoldingHours   // 平均持仓时长
exitTypeBreakdown // 各退出类型占比 Map<String, Integer>
```

### 3.2 API 端点

`GET /strategy/api/smart-wallet/follow-trade/attribution?groupBy={WALLET_SCORE|CONSENSUS|PROBABILITY|MARKET_TIMING}`

### 3.3 历史数据回刷

首次上线时对现有 `CLOSED` / `SETTLED` 仓位执行一次全量聚合，补充缺失的归因维度字段。

---

## 4. §2 动态 Kelly 仓位计算

### 4.1 公式

预测市场二元结果的 Kelly 比例：

```
f* = (p × b - (1 - p)) / b

其中：
  p = calibratedFollowProbability（校准后跟单胜率）
  b = (1 - entryPrice) / entryPrice（赔率，由入场价格推导）
```

实际下注额：

```
stake = availableCapital × kellyFraction × f*
        ↓ floor: minOrderUsdc
        ↓ ceil:  maxStakeUsdc
```

### 4.2 边界处理

| 情况 | 处理方式 |
|------|---------|
| `f* ≤ 0`（EV 为负） | stake = 0，联动现有 EV 门控直接拒绝信号 |
| `calibratedFollowProbability` 为 null | 回退到现有公式（兼容旧逻辑） |
| `entryPrice` ∈ {0, 1} 附近（< 0.02 或 > 0.98） | 赔率异常，stake 截断到 minOrderUsdc |

### 4.3 实现位置

- 新增 `KellyStakeCalculator`（工具类，无 Spring 依赖，易于单测）
- 在 `SmartWalletSignalRiskService.evaluate()` 末尾替换 `recommendedStake` 计算逻辑
- `application.yml` 新增配置：
  ```yaml
  smart-wallet:
    signal-risk:
      kelly-fraction: 0.3        # Kelly 折扣因子（初始保守值）
      kelly-stake-enabled: true  # 独立开关，false 时回退旧逻辑
  ```

---

## 5. §3 时间感知止损

### 5.1 动态阈值规则

| 市场剩余时间 | 止损阈值 |
|------------|---------|
| > 168 小时（7 天） | -35% |
| 24 - 168 小时 | -25% |
| 6 - 24 小时 | -15% |
| < 6 小时 | -5% |

### 5.2 趋势加速止损（附加规则）

仓位 ROI < 0 且价格连续 N 个扫描周期（可配置，默认 3 次 × 5 分钟）单调下跌，提前触发止损，不等待阈值。

### 5.3 实现位置

- 新增 `TimeAwareStopLossService`，实现 `ExitSignalProvider` 接口
- `SmartWalletPositionSyncService.checkStopLoss()` 改为调用 `TimeAwareStopLossService.resolveThreshold(position)` 获取动态阈值
- `application.yml` 新增配置：
  ```yaml
  smart-wallet:
    signal-risk:
      time-aware-stop-loss-enabled: true
      stop-loss-tiers:
        - remaining-hours: 168
          threshold-rate: 0.35
        - remaining-hours: 24
          threshold-rate: 0.25
        - remaining-hours: 6
          threshold-rate: 0.15
        - remaining-hours: 0
          threshold-rate: 0.05
      trend-stop-loss-enabled: true
      trend-stop-loss-consecutive-periods: 3
  ```

### 5.4 方案 C 接入点

```java
public interface ExitSignalProvider {
    ExitDecision shouldExit(SmartWalletPositionEntity position);
}
```

`TimeAwareStopLossService` 实现此接口。方案 C 的 ML 模型只需提供另一个实现，通过配置切换，调用链不变。

---

## 6. 整体架构

```
┌─────────────────────────────────────────────────────────┐
│                    信号入场层（已有）                      │
│  SmartWalletSignalRiskService.evaluate()                │
│  + § 2 KellyStakeCalculator（替换 recommendedStake）     │
└────────────────────────┬────────────────────────────────┘
                         │ 开仓
┌────────────────────────▼────────────────────────────────┐
│                    持仓管理层                             │
│  SmartWalletPositionSyncService                         │
│  + § 3 TimeAwareStopLossService : ExitSignalProvider    │
│  ← 方案 C ML 模型只需实现 ExitSignalProvider 接口         │
└────────────────────────┬────────────────────────────────┘
                         │ 平仓/结算
┌────────────────────────▼────────────────────────────────┐
│                    分析归因层（新增）                      │
│  § 1 FollowTradeAttributionService                      │
│  → GET /follow-trade/attribution                        │
└─────────────────────────────────────────────────────────┘
```

---

## 7. 并行推进安排

| 线 | 内容 | 依赖 | 风险 |
|----|------|------|------|
| 分析线 | §1 归因视图 | 无，独立只读 | 低 |
| 改进线 A | §2 Kelly 仓位 | 无，替换计算逻辑 | 中（需验证 calibratedFollowProbability 准确性） |
| 改进线 B | §3 时间感知止损 | `marketEndAt` 已有 | 低 |

所有功能均有独立 `enabled` 开关，可灰度验证后再全量开启。

---

## 8. 方案 C 后续演进路线

待方案 B 数据积累（建议至少 200 笔跟单结算记录）后：

1. 用归因数据集训练退出时机预测模型
2. 实现 `MlExitSignalProvider implements ExitSignalProvider`
3. 通过配置将 `TimeAwareStopLossService` 替换为 ML 实现
4. A/B 测试：部分仓位走规则退出，部分走 ML 退出，对比结果

---

## 9. 不在本次范围内

- 入场信号源的改造（watchlist 钱包筛选标准调整）
- 共识门控参数调整
- 非跟单策略模块的任何改动

---

<!-- kb-backlink -->
> 本文归属：[Polymarket 知识地图](../../MOC-Polymarket.md) ｜ [知识库首页](../../知识库首页.md)
