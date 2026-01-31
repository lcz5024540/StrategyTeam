# 资金费率套利策略 (Funding Rate Arbitrage)

## 1. 策略原理

### 1.1 单交易所套利 (期现套利)
*   **操作**：现货买入 (做多) + 合约卖出 (做空)。
*   **收益**：赚取合约的资金费率 (通常为正，即多头付给空头)。
*   **成本**：仅需支付交易手续费，现货无资金费。
*   **优点**：无爆仓风险 (只要保持 1:1 对冲)，无跨所转账风险。

### 1.2 多交易所套利 (跨期/跨所套利)
*   **操作**：
    *   低费率交易所：开正向单 (做多)。
    *   高费率交易所：开反向单 (做空)。
*   **收益**：赚取两个交易所之间的费率差值 (Spread)。
*   **条件**：
    *   费率差 > (双边开平仓手续费 + 滑点成本)。
    *   开仓价与平仓价的价差 (Price Spread) 需在可控范围内。

## 2. 核心风险与注意事项

### 2.1 利润覆盖摩擦成本 (Friction Costs)
*   **痛点**：资金费率通常很低 (如 0.01%/8h)，而 Taker 手续费较高 (双边开平约 0.2%)。
*   **结论**：如果费率差仅 0.03%，需要连续吃费率约 3 天才能回本。
*   **对策**：
    *   设置高开仓阈值 (如费率差 > 0.1%)。
    *   尽量使用 Maker (挂单) 降低手续费。

### 2.2 单腿成交风险 (Legging Risk)
*   **痛点**：A所成交，B所失败，导致仓位裸露。
*   **对策**：
    *   **并发下单**：多线程同时发送订单。
    *   **IOC/FOK**：使用“立即成交或取消”订单类型。
    *   **自动对冲**：一旦出现单腿，立即市价平仓或追单。

### 2.3 保证金再平衡 (Rebalancing)
*   **痛点**：单边暴涨暴跌导致空头账户保证金不足，面临强平风险。
*   **对策**：
    *   **低杠杆**：建议 1x - 3x，绝不重仓。
    *   **动态划转**：监控保证金率，低于阈值 (如 200%) 自动报警或减仓。

### 2.4 时间博弈 (Timing)
*   **痛点**：结算前 (00:00, 08:00, 16:00 UTC) 费率波动剧烈。
*   **对策**：避开结算前后 5 分钟开仓，或专门研究回归策略。

## 3. 代码架构建议 (coin-strategy-core)

建议采用分层架构，避免逻辑耦合。

### 3.1 模块划分
*   **`monitor` (监控层)**
    *   `FundingRateMonitor`: 轮询各所 `getPremiumIndex`，计算 Spread。
    *   `DepthMonitor`: 检查盘口深度是否满足资金体量。
*   **`decision` (决策层)**
    *   `ArbitrageStrategy`: 订阅监控数据，生成 OPEN/CLOSE 信号。
*   **`execution` (执行层)**
    *   `OrderExecutor`: 负责“原子化”下单，处理并发请求。
    *   `RebalanceService`: 监控持仓比例。
*   **`risk` (风控层)**
    *   `RiskController`: 强制风控 (检查未完成订单、保证金、结算时间)。

### 3.2 伪代码结构

```java
public class FundingRateArbitrageStrategy implements Strategy {
    @Override
    public void run() {
        // 1. 获取数据 (并行)
        BigDecimal binanceRate = binanceService.getFundingRate(symbol);
        BigDecimal okxRate = okxService.getFundingRate(symbol);
        
        // 2. 计算差值
        BigDecimal diff = binanceRate.subtract(okxRate);
        
        // 3. 决策
        if (diff.abs().compareTo(THRESHOLD) > 0) {
            if (depthMonitor.checkLiquidity(symbol, volume)) {
                // 4. 执行
                executionEngine.executeDualSideOrder(
                    new OrderRequest(LONG, amount), 
                    new OrderRequest(SHORT, amount)
                );
            }
        }
    }
}
```

## 4. 实施路线图

1.  **基础建设**：
    *   确认 Gate.io 资金费率接口。
    *   实现 `FundingRateMonitor` 打印实时价差。
2.  **模拟盘**：
    *   仅监控不交易，记录“理论开仓点”和“理论收益”。
3.  **实盘测试**：
    *   小资金 (如 100 USDT) 跑通全流程。
