---
title: "常规技术分析策略方案 (Conventional Technical Analysis Strategy)"
type: strategy
status: active
tags: [technical-analysis, strategy]
created: 2026-02-01
updated: 2026-02-01
---

# 常规技术分析策略方案 (Conventional Technical Analysis Strategy)

## 1. 策略概述 (Overview)
**策略名称**: 经典趋势追踪与盘口动量策略 (Classic Trend Following & Order Book Momentum)
**核心目标**: 结合传统技术指标（MACD、EMA、VOL）确认趋势方向，利用盘口微观数据（买卖盘失衡）作为过滤和入场信号，捕捉短期确定性机会。
**适用周期**: 1H (判断趋势) + 15m/5m (入场信号)

## 2. 策略体系构建 (Strategy Architecture)

### 2.1 数据清洗与多因子合成 (Data Layer)
不仅仅是获取数据，而是要构建 **“趋势-入场-过滤”** 三层架构：

*   **趋势层 (Trend Filter)**:
    *   使用 **EMA144/169 隧道** 或 **EMA200** 判断大方向。
    *   **规则**: 价格在 EMA 上方只做多，下方只做空。
*   **入场层 (Entry Signal)**:
    *   利用 **MACD** (12, 26, 9) 金叉/死叉作为基础触发点。
    *   利用 **K线形态** (如吞没、启明星) 确认反转。
*   **过滤层 (Micro-structure Filter)**:
    *   **挂单失衡 (Imbalance)**: 计算买一/卖一档或前 5 档的挂单量比率。
    *   **主动买卖 (Taker Volume)**: 区分主动买入（Taker Buy）和主动卖出（Taker Sell）。
    *   **规则**: 只有当“价格上涨”且“主动买入量放大”时，才视为有效突破。

### 2.2 信号生成逻辑 (Signal Logic)
采用 **“计分制”** (Scoring System) 替代单一的 if-else 逻辑。

**示例打分卡 (Total > 20分开仓)**:
| 维度 | 指标条件 | 分值 |
| :--- | :--- | :--- |
| **趋势** | 价格 > EMA200 | +10 |
| **动量** | MACD 0轴上方金叉 | +10 |
| **盘口** | 买一量 / 卖一量 > 3 | +5 |
| **成交量** | 当前VOL > MA(VOL, 20) * 2 | +5 |

### 2.3 交易执行策略 (Execution)
*   **挂单 vs 吃单**: 短期策略追求速度，建议使用 **Taker (吃单)** 或 **IOC (Immediate or Cancel)**。
*   **分批进场**:
    *   **试错仓 (30%)**: 信号触发时先进场 30%。
    *   **加仓 (70%)**: 只有当试错仓产生浮盈（证明逻辑正确）后，再加仓剩余资金。

## 3. 风险分析 (Risk Analysis)

### 3.1 策略失效风险 (Model Risk)
*   **过拟合 (Overfitting)**: 参数（如 MACD 12,26,9）在过去表现好，不代表未来好。
*   **震荡磨损**: 趋势策略最怕震荡市，需通过波动率指标（如 ATR 低位）进行过滤。

### 3.2 系统与流动性风险 (System Risk)
*   **滑点 (Slippage)**: 盘口深度不够时，市价单成本不可控。
*   **API 延迟**: 极端行情下交易所接口可能卡顿，导致无法止损。

## 4. 风控体系 (Risk Management)

**优先级高于开单逻辑，必须硬编码在代码中。**

### 4.1 账户级风控 (Account Level)
*   **最大回撤熔断**: 当日亏损 > 本金 2%，**强制停止策略并清仓**，当日不再运行。
*   **总杠杆限制**: 限制最大名义杠杆不超过 3x-5x。

### 4.2 交易级风控 (Trade Level)
*   **固定止损 (Hard Stop Loss)**: 开仓后 **立即** 下达止损单 (Stop Market)。
*   **波动率止损 (ATR Stop)**: 止损距离 = 2 * ATR。波动大时止损宽（仓位小），波动小时止损窄（仓位大）。
*   **时间止损 (Time Stop)**: 开仓 30分钟后仍未脱离成本区，说明逻辑未兑现，**直接平仓离场**。

## 5. 实施路径建议 (Implementation Path)

1.  **回测 (Backtest)**:
    *   编写 `BacktestService`，使用过去 3 个月 K 线数据，验证 MACD+VOL 策略的胜率和盈亏比。
2.  **模拟盘 (Paper Trading)**:
    *   接入交易所 Testnet，在真实时间流中运行，检验 API 稳定性、滑点和日志。
3.  **实盘最小仓位 (Live - Minimum Size)**:
    *   使用最小下单单位（如 10 USDT）跑实盘，验证实盘成交价与策略理论价的偏差。

## 6. 代码实现参考 (Code Structure)

在 `coin-strategy-core` 中实现：

```java
// 核心接口
public interface Strategy {
    void onTick(MarketData data);
}

// 风控组件
public interface RiskManager {
    boolean canOpen(String symbol, double size); // 检查风控规则
    void onTick(double currentPrice); // 监控浮亏
    void emergencyClose(); // 一键清仓
}
```

---

<!-- kb-backlink -->
> 本文归属：[交易策略 知识地图](docs/MOC-交易策略.md) ｜ [知识库首页](docs/知识库首页.md)
