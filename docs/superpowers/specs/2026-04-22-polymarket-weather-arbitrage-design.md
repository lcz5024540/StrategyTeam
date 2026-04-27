# Polymarket 天气套利自主开单系统 — 设计文档

**日期**：2026-04-22  
**分支**：polymarket-hedge  
**模块定位**：独立于跟单逻辑的全自动天气套利策略

---

## 1. 背景与目标

Polymarket 上存在低价格（0.1¢–10¢）的天气预测市场，市场定价与气象预报数据之间偶尔出现可利用的定价偏差。本系统通过 tomorrow.io API 获取城市天气预报，使用正态分布模型计算公允概率，与市场价格对比后全自动开单，实现天气套利。

**目标城市**：伦敦（London）、纽约（New York）、首尔（Seoul）  
**入场条件**：公允价与市场价偏差 > 10%，流动性 ≥ 50 USDC，YES token 价格 < 10¢  
**执行模式**：全自动，独立资金池，每15分钟扫描一次

**不影响**：现有跟单逻辑（`SmartWalletFollowTaskService`）。

---

## 2. 整体架构

### 2.1 新建模块位置

```
coin-strategy-job/src/main/java/.../job/weather/
├── WeatherArbitrageJob.java              # 调度入口（每15分钟）
├── config/
│   └── WeatherArbitrageProperties.java  # 独立配置：资金池、阈值、城市
├── client/
│   └── TomorrowIoApiClient.java         # tomorrow.io HTTP 客户端
├── service/
│   ├── WeatherMarketScanService.java    # 扫描 Polymarket 天气市场
│   ├── WeatherMarketTitleParser.java    # 从市场标题解析温度阈值
│   ├── WeatherFairValueService.java     # 概率模型：预报 → 公允价
│   ├── WeatherArbitrageSignalService.java # 对比价格，生成开单信号
│   ├── WeatherCapitalPoolService.java   # 独立 USDC 预算追踪
│   └── WeatherPositionSyncService.java  # 持仓监控 + 触发止损
└── model/
    ├── WeatherForecast.java
    ├── WeatherArbitrageSignal.java
    └── WeatherMarketSpec.java           # 解析后的市场结构体
```

### 2.2 与现有系统的接口边界

天气模块只**调用**以下现有接口，不修改任何现有代码：

| 调用方向 | 现有组件 | 用途 |
|---------|---------|------|
| 天气模块 → | `PolymarketClobExecutionService` | 下限价单 |
| 天气模块 → | `PolymarketApiClient` | 拉市场列表 / 订单簿 |
| 天气模块 → | `ExitSignalProvider`（`TimeAwareStopLossService`） | 动态止损阈值 |
| 天气模块 → | `SmartWalletPositionEntity` 表 | 持仓存储（`source=WEATHER` 字段隔离） |

---

## 3. 概率模型

### 3.1 tomorrow.io 数据拉取

```
GET /v4/weather/forecast
  ?location={city}
  &timesteps=1d
  &fields=temperatureMax,temperatureMin,temperatureAvg
  &apikey={key}
```

输出模型 `WeatherForecast`：`city, forecastTempMaxF, forecastTempMinF, forecastTempAvgF, fetchedAt`

### 3.2 公允概率计算（WeatherFairValueService）

假设天气预报误差服从正态分布，σ = 3.5°F（初始假设，未经验证，后续可按城市/季节校准）。

**最高温超过阈值类市场**（`type=MAX`）：
```
P(YES) = 1 - Φ((threshold - forecastTempMaxF) / 3.5)
```

**最低温低于阈值类市场**（`type=MIN`）：
```
P(YES) = Φ((threshold - forecastTempMinF) / 3.5)
```

其中 `Φ` 为标准正态累积分布函数。

### 3.3 信号生成（WeatherArbitrageSignalService）

```
edge = (fairValue - marketYesPrice) / marketYesPrice

if edge > 0.10 && liquidity >= 50 USDC && yesPrice < 0.10:
    → BUY_YES 信号

if edge < -0.10 && noPrice < 0.10 && noLiquidity >= 50 USDC:
    → BUY_NO 信号（等价于 NO token 被低估）
```

每个信号携带：`marketId, tokenId, side, fairValue, marketPrice, edge, stakeUsdc`

---

## 4. 资金池管理

### 4.1 配置

```yaml
weather-arbitrage:
  capital-pool-usdc: 500        # 总预算上限
  max-single-stake-usdc: 50     # 单笔最大下注
  max-open-positions: 5         # 同时持仓上限
  entry-edge-threshold: 0.10    # 偏差阈值 10%
  min-liquidity-usdc: 50        # 市场最低流动性
  cities:
    - London
    - NewYork
    - Seoul
  tomorrow-io-api-key: ${TOMORROW_IO_API_KEY}
```

### 4.2 WeatherCapitalPoolService 逻辑

- `allocatedUsdc` = 所有 `OPEN` 状态天气仓位的 `stakeUsdc` 之和
- 开单前校验：`allocatedUsdc + stakeUsdc ≤ capitalPoolUsdc`
- 单笔 `stakeUsdc = min(edge × capitalPoolUsdc × 0.25, maxSingleStakeUsdc)`（简化 Kelly）
- 仓位平仓/结算后释放额度

---

## 5. 市场扫描与标题解析

### 5.1 WeatherMarketScanService 过滤规则

- Polymarket API 按 `tag=weather` 过滤，再二次筛选：
  - 标题包含目标城市名
  - 标题包含温度关键词（`temperature / degrees / °F / °C / high / low`）
  - YES token 价格在 `[0.001, 0.10]`
  - 流动性 ≥ 50 USDC
  - 结算时间在未来 **12–72 小时**内（太近风险高，太远预报误差大）

### 5.2 WeatherMarketTitleParser

正则解析市场标题，提取结构化信息：

| 示例标题 | 解析结果 |
|---------|---------|
| `Will London high exceed 68°F tomorrow?` | `type=MAX, threshold=68, city=London` |
| `NYC low below 45°F on April 23?` | `type=MIN, threshold=45, city=NewYork` |

无法解析的市场直接跳过，不参与定价。

---

## 6. 执行主循环

`WeatherArbitrageJob`，`@Scheduled(fixedDelay = 15min)`：

```
1. WeatherMarketScanService      → 拉 Polymarket 天气市场，应用过滤规则
2. TomorrowIoApiClient           → 拉各城市次日预报
3. WeatherFairValueService       → 计算各市场公允概率
4. WeatherArbitrageSignalService → 筛选 edge > 10% 的机会
5. WeatherCapitalPoolService     → 校验额度，计算 stakeUsdc
6. PolymarketClobExecutionService → 下限价单（YES 或 NO token）
7. WeatherPositionSyncService    → 写入持仓记录（source=WEATHER）
8. 记录 weather_arbitrage_signal_log（所有扫描结果，无论是否开单）
```

---

## 7. 持仓监控与退出

`WeatherPositionSyncService`，每5分钟运行：

1. 查询所有 `source=WEATHER` 且 `status=OPEN` 的仓位
2. 拉最新 mark price（`PolymarketApiClient`）
3. 调用 `exitSignalProvider.recordMarkPrice(...)`
4. 调用 `exitSignalProvider.resolveStopLossThreshold(position)` 获取动态阈值
5. 若当前 ROI ≤ -threshold，或 `isTrendStopLossTriggered(...)` 返回 true → 平仓
6. 平仓：`PolymarketClobExecutionService` 卖出，写 `exitReason=WEATHER_STOP_LOSS`
7. 市场结算后自动归零，写 `exitReason=SETTLEMENT`

---

## 8. 数据持久化

### 8.1 复用现有表

`smart_wallet_position` 表新增 `source` 字段（枚举：`FOLLOW_TRADE / WEATHER`），天气仓位写 `source=WEATHER`，所有查询和归因分析按此字段隔离。

### 8.2 新增信号日志表

`weather_arbitrage_signal_log`：

```sql
market_id     VARCHAR
city          VARCHAR
market_price  DECIMAL
fair_value    DECIMAL
edge          DECIMAL
action        VARCHAR  -- PASS / BUY_YES / BUY_NO
reason        VARCHAR  -- 跳过原因 或 开单原因
scanned_at    DATETIME
```

用于后续验证概率模型准确性（预报 vs 实际结算对比）。

---

## 9. 算法演进路径

| 阶段 | σ 来源 | 模型复杂度 |
|-----|-------|---------|
| 初期（当前） | 固定 3.5°F 假设 | 简单正态分布 |
| 中期 | 按城市/季节校准（基于 signal_log 历史） | 分组正态分布 |
| 后期 | 接入 `ExitSignalProvider` 的 ML 扩展点 | 方案 C（ML 驱动） |

---

## 10. 风险与约束

| 风险 | 缓解措施 |
|-----|---------|
| tomorrow.io API 限流/故障 | 本轮跳过，下轮重试，不影响持仓监控 |
| 市场标题无法解析 | 跳过该市场，记录日志 |
| 资金池耗尽 | 开单前硬校验，拒绝超额信号 |
| 止损未触发导致持有至结算归零 | 结算时间 ≤ 12h 的市场不开新仓 |
| 与跟单/对冲逻辑互扰 | `source=WEATHER` 字段隔离，执行层仅通过接口调用 |
