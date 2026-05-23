---
title: "TOP50 策略方案 (TOP50 Strategy Proposal)"
type: strategy
status: active
tags: [top50, strategy]
created: 2026-02-01
updated: 2026-02-01
---

# TOP50 策略方案 (TOP50 Strategy Proposal)

## 1. 策略概述 (Overview)
**策略名称**: TOP50 趋势与波动率策略 (TOP50 Trend & Volatility Strategy)
**核心目标**: 捕捉市场前50大交易量代币的波动机会，结合盘口买卖压力与外部数据支撑，进行趋势跟随或反转交易。
**适用周期**: 15分钟 (15m) / 1小时 (1H) / 4小时 (4H)

## 2. 核心模块详解 (Detailed Modules)

### 2.1 标的筛选器 (Universe Selector)
- **规则**: 动态筛选排名前50的代币。
- **数据源**: 交易所 (Binance/OKX) 24小时成交量 (Quote Volume) 排名。
- **过滤条件 (Filter Criteria)**:
  - **剔除稳定币**: USDT, USDC, TUSD, FDUSD, DAI 等。
  - **剔除杠杆代币**: UP, DOWN, BULL, BEAR 等。
  - **剔除低流动性**: 虽有成交量但盘口价差 (Spread) 过大的代币。
- **更新机制**: 每 4 小时重新扫描并更新一次白名单。

### 2.2 波动率分析引擎 (Volatility Engine)
- **核心逻辑**: 寻找“静默后爆发”或“异常剧烈波动”的时刻。
- **关键指标**:
  - **ATR (Average True Range)**: 使用 14 周期 ATR 衡量绝对波动值。
  - **BBW (Bollinger Band Width)**: 布林带带宽，当 BBW 极度收窄时预示变盘。
  - **Volume Spike**: 当前周期成交量 > 过去 20 周期平均成交量的 2-3 倍。

### 2.3 订单流与盘口分析 (Order Flow & Depth Analysis)
- **核心逻辑**: 确认价格变动背后的资金真实意图。
- **关键指标**:
  - **挂单深度 (Order Book Depth)**:
    - 计算 +/- 2% 价格范围内的买单总额 (Bid Sum) 与卖单总额 (Ask Sum)。
    - **Depth Ratio** = Bid Sum / Ask Sum。 >1.2 视为买盘强劲，<0.8 视为卖盘强劲。
  - **主动成交 (Taker Volume)**:
    - 统计最近 15 分钟的主动买入量 (Taker Buy) 和主动卖出量 (Taker Sell)。
    - **Net Taker Flow** = Taker Buy - Taker Sell。
  - **大单监控 (Whale Orders)**:
    - 监控单笔价值 > $100,000 的成交单，判断主力方向。

### 2.4 外部数据集成 (External Data Integration)
- **核心逻辑**: 引入场外/衍生品数据作为趋势确认器。
- **推荐数据源**:
  - **Coinglass API** (或交易所自带数据):
    - **Open Interest (OI)**: 持仓量上升 + 价格上升 = 强趋势；持仓量下降 + 价格上升 = 空头回补（弱趋势）。
    - **Long/Short Ratio**: 大户多空比。
  - **Funding Rate**: 资金费率。异常高费率可能预示回调风险。
  - **Fear & Greed Index**: 极端恐惧时考虑反转做多，极端贪婪时考虑止盈或做空。

## 3. 详细执行步骤 (Execution Steps)

### 阶段一：扫描与准备 (Scanning Phase)
1.  **定时任务 (每4小时)**: 调用交易所 API 获取所有 USDT 交易对的 24h 成交量。
2.  **过滤**: 剔除稳定币、杠杆代币，选取 Top 50。
3.  **订阅**: 建立 WebSocket 连接，订阅这 50 个代币的 K线 (15m) 和 归集后的深度数据。

### 阶段二：信号生成 (Signal Generation Phase) - 伪代码
```python
# 对每个代币每 15 分钟执行一次
def check_signal(token):
    # 1. 波动率检查 (Volatility Check)
    bbw = calculate_bollinger_band_width(token, period=20)
    atr = calculate_atr(token, period=14)
    if bbw > threshold_high or is_breaking_out(token):
        
        # 2. 盘口验证 (Order Flow Validation)
        depth_ratio = get_depth_ratio(token, range=0.02) # +/- 2%
        net_taker_flow = get_net_taker_flow(token, window="15m")
        
        # 3. 外部数据确认 (External Confirmation)
        oi_change = get_oi_change(token, window="1h")
        funding_rate = get_funding_rate(token)

        # 4. 综合打分 (Scoring)
        score = 0
        if depth_ratio > 1.2: score += 1
        if net_taker_flow > 0: score += 2
        if oi_change > 0: score += 1
        
        # 5. 生成信号
        if score >= 3:
            return BUY_SIGNAL
        elif score <= -3:
            return SELL_SIGNAL
    
    return NO_SIGNAL
```

### 阶段三：交易执行 (Execution Phase)
1.  **开仓**:
    - 使用限价单 (Limit Order) 挂在买一价，或者使用 IOC (Immediate or Cancel) 吃单，取决于信号的紧急程度。
    - 建议初始仓位: 总资金的 2% - 5% (固定比例)。
2.  **风控设置**:
    - **止损 (Stop Loss)**: 价格 - 2 * ATR。
    - **止盈 (Take Profit)**: 分批止盈。例如 价格 + 3 * ATR 止盈 50%，剩余追踪止盈。
    - **最大持仓时间**: 例如 24小时，超时强制平仓。

## 4. 技术架构 (Technical Architecture)

基于 `coin-strategy-core` 模块进行开发：

```text
com.metaverse.coin.strategy.core.top50
├── Top50Strategy.java        // 策略主控
├── config
│   └── Top50Config.java      // 参数配置 (Top N, 阈值, 风控参数)
├── selector
│   └── UniverseSelector.java // Top 50 筛选与黑名单过滤
├── market
│   ├── VolatilityMonitor.java // 计算 ATR, BBW
│   └── OrderFlowAnalyzer.java // 计算 Depth Ratio, Taker Flow
├── external
│   └── ExternalDataProvider.java // 获取 OI, Funding Rate
└── execution
    ├── SignalEngine.java     // 信号打分逻辑
    └── StrategyExecutor.java // 下单与持仓管理
```

## 5. 开发路线图 (Roadmap)

1.  **基础数据层**:
    - 实现 `UniverseSelector`: 完成 Top 50 动态筛选。
    - 完善 `OrderFlowAnalyzer`: 对接 WebSocket 深度和成交数据。
2.  **指标计算层**:
    - 实现 ATR 和 Bollinger Band 计算逻辑。
    - 实现简单的 Order Book Imbalance 计算。
3.  **外部数据层**:
    - 优先对接交易所自带的 Public Data (如 Binance Futures 提供 OI 和 Funding Rate)。
    - (可选) 后续接入 Coinglass API。
4.  **策略组装**:
    - 编写 `SignalEngine` 将上述指标串联。
5.  **模拟盘验证**:
    - 在测试网或模拟环境运行，记录信号触发频率和胜率。

## 6. 已确认配置 (Confirmed Configuration)
- **交易所**: Binance 和 OKX 均支持 (双交易所模式)。
- **交易市场**: 支持 现货 (Spot) 和 永续合约 (Perpetual Futures)。
- **外部数据源**: 后续接入 Coinglass, CoinGecko 等第三方数据 (初期使用交易所 Public Data)。

---

<!-- kb-backlink -->
> 本文归属：[交易策略 知识地图](docs/MOC-交易策略.md) ｜ [知识库首页](docs/知识库首页.md)
