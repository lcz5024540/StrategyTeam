# Polymarket 自动化交易策略深度分析报告

> 范围：跟单交易 (Copy Trading)、天气预测、5 分钟 BTC 短期预测、套利与做市
> 数据来源：GitHub 公开仓库 + Polymarket 官方文档 + 二级研报
> 报告日期：2026-05

---

## 摘要

Polymarket 是基于 Polygon 网络、采用 CLOB (Central Limit Order Book) 机制的预测市场协议。每个市场是二元结果：YES + NO ≈ 1 USDC。这一独特的结构使得它既可以"信号交易"（用外部世界的数据预测内部市场价格），也可以"结构性套利"（用 YES+NO≠1 的偏差套利）。

围绕 Polymarket 的 GitHub 仓库可大致分为四个流派：

1. **跟单交易 (Copy Trading)** — 追踪 leader 钱包，复制其下单行为，引入风控参数限制曝险。
2. **天气预测市场** — 基于 NOAA / GFS / Open-Meteo 等气象数据计算 "模型概率"，对比市场报价进行 edge trading。
3. **5 分钟 BTC 短期预测** — 利用 Coinbase/Binance 实时 1 分钟 K 线计算微结构信号 (RSI、动量、VWAP、SMA、市场偏度)，在 5 分钟二元市场结算前下单。
4. **套利与做市** — 包括 Polymarket 官方 `poly-market-maker` 的 AMM/Bands 策略、二元互补套利 (YES+NO<1)、跨平台 (Polymarket vs Kalshi) 套利、CLOB 闪电套利。

本报告分别从 **策略核心思想 → 执行架构 → 关键代码 → 风控** 四个维度，对每个流派进行深度拆解。

---

## 一、Polymarket 平台技术基础

理解策略实现前需先掌握 Polymarket 的 API 与撮合机制：

- **Gamma API** — REST 接口，公开市场元数据，含分类、流动性、过期时间。
- **CLOB API** — 中央限价订单簿，下单/撤单/查询。官方提供 [`py-clob-client`](https://github.com/Polymarket/py-clob-client) (Python) 和 `clob-client` (TS)。订单需 L1 (钱包签名生成 API Key) + L2 (HMAC 签名下单) 双层鉴权。
- **Data API** — 用户层接口：`GET /trades` 按钱包或市场查询历史成交，是跟单 bot 的核心。
- **WebSocket** — 实时推送 orderbook、trade、用户私有订单事件 ([Polymarket/real-time-data-client](https://github.com/Polymarket/real-time-data-client))。
- **Subgraph** (Goldsky) — GraphQL 查询持仓、活动、PnL，适合事后审计与统计研究。

**关键资产模型**：每个市场含两个 ERC-1155 "结果代币" (outcome tokens)，每对代币可以以 1 USDC 拆分铸造、也可以合并赎回。这是绝大多数套利策略的根基。

---

## 二、跟单交易 (Copy Trading) 策略

### 2.1 策略核心思想

跟单交易的本质是 **"信号外包"**：放弃自己研究，把决策权外包给"已被市场验证"的 leader 钱包。背后的两个假设：

1. **持续胜率假设**：Polymarket 上某些钱包在过去一段时间体现出显著高于平均的胜率（信息优势 + 仓位管理），这种 alpha 在短期内具有持续性。
2. **延迟可压缩假设**：从 leader 落单 → 链上确认 → API 可见 → follower 复制的链路延迟 (通常 2-30 秒) 短于市场 "价格反应消化" 信号所需时间，留出 follower 的入场窗口。

策略关键不在"是否复制"，而在 **"如何过滤 + 如何缩放 + 如何独立退出"**。

### 2.2 代表仓库

| 仓库 | 语言 | 特点 |
| --- | --- | --- |
| [GiordanoSouza/polymarket-copy-trading-bot](https://github.com/GiordanoSouza/polymarket-copy-trading-bot) | Python + Supabase | 使用 Supabase Realtime 触发器作为事件总线，订单缩放 |
| [unitmargaretaustin/Polymarket-copy-trading-bot](https://github.com/unitmargaretaustin/Polymarket-copy-trading-bot) | Python + Playwright | 浏览器自动化路线，强调风控 / 幂等性 / TP-SL |
| [Xyryllium/polymarket-tracker-bot](https://github.com/Xyryllium/polymarket-tracker-bot) | Python | Discord 通知 + 纸交易 + 自动跟单一体 |
| [LNLUTS/polymarket-copy-trading-bot](https://github.com/LNLUTS/polymarket-copy-trading-bot) | TypeScript | 轮询持仓变化，GTC limit order 复制 |
| [roswelly/polymarket-copy-trading-bot](https://github.com/roswelly/polymarket-copy-trading-bot) | TypeScript | 优化执行延迟 (<3 秒) |
| [gamma-trade-lab/polymarket-copy-trading-bot](https://github.com/gamma-trade-lab/polymarket-copy-trading-bot) | Rust | 低延迟版本 |

### 2.3 执行架构（综合多个仓库的通用范式）

```
┌────────────────────────────────────────────────────────────────────────┐
│                          LEADER 钱包源                                  │
│   (1...N 个 Polygon 地址，由用户在 config 中配置)                       │
└─────────────────────┬──────────────────────────────────────────────────┘
                      │
            ┌─────────▼──────────┐
            │  事件采集层 (3 路) │
            │ ① 轮询 Data API    │   /trades?user=0x...&limit=50
            │ ② WebSocket trade  │   real-time channel
            │ ③ 链上 logs 监听   │   Polygon RPC eth_getLogs
            └─────────┬──────────┘
                      │ normalized TradeEvent{market_id, side, size, price, ts}
            ┌─────────▼──────────┐
            │   去重 / 状态机    │   leader_event_id → 幂等键
            │ (Supabase / Redis) │   防止同笔成交被多次复制
            └─────────┬──────────┘
                      │
            ┌─────────▼──────────┐
            │   过滤 & 缩放器    │
            │ - 流动性最小值     │
            │ - 价差阈值         │
            │ - 类别白名单       │
            │ - 单笔最大 USD     │
            │ - leader 占比 → follower 占比 (按 capital 比例缩放) │
            └─────────┬──────────┘
                      │ ScaledOrderIntent
            ┌─────────▼──────────┐
            │   预交易模拟       │   再次抓 orderbook，验证 slippage、spread
            │   (Pre-flight)     │
            └─────────┬──────────┘
                      │ OK / REJECT
            ┌─────────▼──────────┐
            │ py-clob-client 下单│   post limit order (GTC)
            └─────────┬──────────┘
                      │ order_id
            ┌─────────▼──────────┐
            │   退出策略管理     │
            │ A. 镜像退出: 监听 leader 卖出，同步跟卖 │
            │ B. 独立 TP/SL: 价格 ≥ +20% or ≤ -30%   │
            │ C. 到期解算: 等待 market 结算          │
            └────────────────────┘
```

**Supabase Realtime 子架构**（GiordanoSouza 流派）：把所有 `trade_events` 写入 Postgres 表，开启 Realtime，bot 监听 `INSERT` 事件，几乎实现 push 式触发，避免 API 轮询。这是把"事件驱动"做得最简洁的开源实现。

### 2.4 核心代码片段（汇编自多个仓库的典型实现）

**1) 拉取 leader 成交并去重**（仿 GiordanoSouza 风格）

```python
from py_clob_client.client import ClobClient
import time

LEADER = "0xLeaderWalletAddress"
seen_ids = set()  # 实际生产用 Redis SET 持久化

def poll_leader_trades(client):
    resp = client.get_trades(user=LEADER, limit=50)
    new_trades = []
    for t in resp:
        tid = t["transaction_hash"] + ":" + t["asset_id"]
        if tid in seen_ids:
            continue
        seen_ids.add(tid)
        new_trades.append(t)
    return new_trades
```

**2) 仓位缩放与风控门**（综合 unitmargaretaustin / GiordanoSouza）

```python
def size_follower_order(leader_trade, capital_follower, capital_leader_est,
                        max_per_trade_usd=50, min_liquidity=500,
                        max_spread_bps=300):
    market = get_market(leader_trade["market_id"])
    # 流动性过滤
    if market["liquidity"] < min_liquidity:
        return None
    spread_bps = (market["best_ask"] - market["best_bid"]) / market["mid"] * 1e4
    if spread_bps > max_spread_bps:
        return None
    # 按 capital 比例缩放
    ratio = capital_follower / capital_leader_est
    size = leader_trade["size_usd"] * ratio
    size = min(size, max_per_trade_usd)
    return {
        "market_id": leader_trade["market_id"],
        "side": leader_trade["side"],
        "price": market["best_ask"] if leader_trade["side"] == "BUY"
                                      else market["best_bid"],
        "size": size,
    }
```

**3) 独立 TP/SL 退出**（unitmargaretaustin 流派）

```python
TP_PCT = 0.20   # 20% take profit
SL_PCT = 0.30   # 30% stop loss

def manage_positions(positions):
    for pos in positions:
        cur = get_market_mid(pos["market_id"])
        pnl_pct = (cur - pos["entry"]) / pos["entry"] * (1 if pos["side"]=="BUY" else -1)
        if pnl_pct >= TP_PCT or pnl_pct <= -SL_PCT:
            place_market_close(pos)
```

### 2.5 风险控制要点

- **幂等键 (idempotency key)**：用 `tx_hash + asset_id + side` 作为唯一键，跨重启 / 多 worker 不重复下单。
- **类别上限**：避免同一新闻热点（如"美国大选"）下多个市场同时触发，导致主题集中度过高。
- **断路器**：连续 N 笔 leader 亏损 / API 错误 / spread 异常 → 自动暂停。
- **leader 池动态评分**：定期重算 leader 30 天滚动胜率与 Sharpe，低分自动下线。

### 2.6 关键风险与陷阱

- **MEV / 抢跑反向**：leader 钱包公开后，他人可能在你之前抢跑，反而把价格推到对你不利。建议加最小延迟随机化 + 滑点上限。
- **leader 测试场 / 自反交易**：部分"高胜率"钱包是项目方/做市商的对冲账户，胜率高但仓位收益小，不适合无差别复制。
- **结算延迟与回购成本**：Polymarket 结算依赖 UMA Oracle，争议市场可能延后 2-7 天，期间资金被锁。

---

## 三、天气预测市场策略

### 3.1 策略核心思想

天气类市场（例如 "今天纽约最高气温是否高于 80°F"）具有 **客观、可观测、可建模** 三大特性，是预测市场中最干净的"模型 vs 价格"赛道。核心思想：

> 用专业气象集合预报 (ensemble forecast) 计算客观概率 `p_model`，与市场报价 `p_market` 比较，当 `|edge| = |p_model − p_market| > 阈值` 时下注边缘较大的一侧。

这条策略的盈利来源是 **零售 / 情绪交易者** 与 **专业气象模型** 之间的信息差。Polymarket 上的天气市场流动性偏低，零售常根据"昨天天气"或"直觉"下注，给量化模型留出系统性 edge。

### 3.2 代表仓库

| 仓库 | 概率模型 | 平台 |
| --- | --- | --- |
| [suislanchez/polymarket-kalshi-weather-bot](https://github.com/suislanchez/polymarket-kalshi-weather-bot) | 31-member GFS ensemble (Open-Meteo) | Polymarket + Kalshi |
| [alteregoeth-ai/weatherbot](https://github.com/alteregoeth-ai/weatherbot) | Kelly + EV filter | Polymarket |
| [yangyuan-zhen/PolyWeather](https://github.com/yangyuan-zhen/PolyWeather) | 多模型集成 + Bayesian | Polymarket |
| [hcharper/polyBot-Weather](https://github.com/hcharper/polyBot-Weather) | NOAA + 高斯误差模型 | Polymarket |
| [MoonsatProtocol/Polymarket-Weather-Bot](https://github.com/MoonsatProtocol/Polymarket-Weather-Bot) | 商业化封装 | Polymarket |

### 3.3 三种概率模型的对比

#### 模型 A：单点预报 + 高斯误差 (hcharper / 初学者方案)

设 NOAA 给出最高温点预报 `μ = 85°F`，历史 1 日 RMSE = `σ = 2.5°F`，对阈值 `T = 80°F`：

```
p_model(High > T) = 1 - Φ((T - μ) / σ) = 1 - Φ((80-85)/2.5) = 1 - Φ(-2) ≈ 0.977
```

简单但只用了 1 个数字，丢失了天气模型本身的不确定性。

#### 模型 B：集合预报频率法 (suislanchez 方案，最主流)

GFS Ensemble 给出 31 个 perturbed forecast member，每个 member 给出一个气温值。直接数 "多少个 member 超过阈值"：

```python
def ensemble_probability(member_forecasts, threshold):
    above = sum(1 for f in member_forecasts if f > threshold)
    return above / len(member_forecasts)   # e.g. 28/31 ≈ 0.903
```

优点：天然反映模型不确定性；当 31 个 member 全部高于阈值时 confidence 接近 1；意见分裂时给出中间值。

#### 模型 C：贝叶斯更新 (PolyWeather 方案)

先用气候学先验 `P(T > t | climate)`，再用当日观测 (上午温度、风向、云量) 用 Bayes 更新到 `P(T > t | obs)`。适用于市场临近结算 (still trading) 时叠加 NWS 实时观测。

### 3.4 执行架构（suislanchez/polymarket-kalshi-weather-bot）

```
┌───────────────────────────────────────────────────────────────────┐
│                        DATA SOURCES                               │
│  Open-Meteo (GFS 31-member)   NWS Observations   Polymarket Gamma │
│  Kalshi KXHIGH series                                             │
└──────────┬────────────────┬──────────────┬───────────────┬────────┘
           │                │              │               │
           ▼                ▼              ▼               ▼
┌──────────────────────────────────────────────────────────────┐
│  Weather Signal Engine  (backend/core/weather_signals.py)    │
│                                                              │
│  for city in CITIES:                                         │
│     ensemble = open_meteo.fetch(city, ensemble=True)         │
│     for market in active_markets(city):                      │
│         T = market.threshold                                 │
│         p_model = ensemble_above(ensemble, T)                │
│         p_market = market.best_ask  (for YES side)           │
│         edge = p_model - p_market                            │
│         if abs(edge) > 0.08:                                 │
│             confidence = ensemble_agreement(ensemble, T)     │
│             emit Signal(market, side, edge, confidence)      │
└──────────────────────┬───────────────────────────────────────┘
                       │
            ┌──────────▼───────────┐
            │  Kelly Sizing 0.15×  │   size = 0.15 × kelly × bankroll
            │  Max $100/trade      │   capped
            │  Edge ≥ 8%           │
            │  Entry price ≤ 0.70  │   不追高已经稳赢的腿
            └──────────┬───────────┘
                       │
            ┌──────────▼───────────┐
            │  CLOB Order Placer   │   py-clob-client / TS clob-client
            └──────────┬───────────┘
                       │
            ┌──────────▼───────────┐
            │ Settlement Engine    │   到期后用 NWS 真实观测验证 → 计算 Brier score
            │ Calibration Tracker  │   反推模型漂移
            └──────────────────────┘
```

### 3.5 核心代码片段

**1) Edge 检测核心**（仿 suislanchez `weather_signals.py`）

```python
def compute_weather_signal(market, ensemble, min_edge=0.08, max_entry=0.70):
    T = market.threshold_f
    members_above = [f for f in ensemble if f > T]
    p_model = len(members_above) / len(ensemble)
    p_market_yes = market.yes_ask
    p_market_no  = market.no_ask
    
    edge_yes = p_model - p_market_yes
    edge_no  = (1 - p_model) - p_market_no
    
    # 选 edge 更大且 entry 不超过 70¢ 的一侧
    best_side, best_edge, best_price = None, 0, None
    if edge_yes > min_edge and p_market_yes <= max_entry:
        best_side, best_edge, best_price = "YES", edge_yes, p_market_yes
    if edge_no > min_edge and p_market_no <= max_entry and edge_no > best_edge:
        best_side, best_edge, best_price = "NO", edge_no, p_market_no
    
    if not best_side:
        return None
    # 集合一致性作为 confidence (0~1)
    agree = abs(p_model - 0.5) * 2
    return Signal(market.id, best_side, best_edge, best_price, agree)
```

**2) 分数 Kelly 头寸**（通用，0.15 Kelly 比例）

```python
def kelly_size(p_win, price, bankroll, fraction=0.15, cap=100):
    # b = (1/price - 1) 是赔率
    b = (1.0 - price) / price
    f_full = (p_win * b - (1 - p_win)) / b
    f = max(0, f_full) * fraction
    size = min(f * bankroll, cap)
    return size
```

### 3.6 风控与坑

- **结算源对齐**：必须确认市场用的是机场代码 (KNYC = LaGuardia? Central Park?)，否则 NOAA 观测值与市场判定可能差 1-2°F，决定盈亏。
- **预报截止时间**：GFS 每 6 小时更新，临近结算前 6h 内预报误差骤减，应提高 edge 阈值或减仓避免反向。
- **流动性陷阱**：天气市场单日成交可能 < $2000，下单大于 $100 时 slippage 显著。仓位上限 = `min($100, 0.05 × bankroll, depth × 0.3)`。
- **校准与停手**：定期计算 Brier score (`mean((p_model - outcome)^2)`)，若滚动 30 笔 Brier > 0.25，说明模型 drift，触发暂停。

---

## 四、5 分钟 BTC 短期预测策略

### 4.1 策略核心思想

Polymarket 的 "BTC up/down in 5 min" 市场结构极简：在 T+5 分钟 BTC 价格相对当前 ↑ 还是 ↓。它在某种意义上是 **微型二元期权**，且：

- 结算源公开（CoinMarketCap mid 或类似 oracle）；
- 5 分钟内市场情绪噪声大，价格往往偏离 50/50；
- 散户经常基于情绪押注 (e.g. 大涨后追多)，制造系统性偏度。

策略目标：用 **可数字化的微结构信号** 估算 `P(up_in_5min)`，与市场报价 `p_market` 对比，下注 edge ≥ 2% 的一侧。

不同于天气策略的"等待集合数据"，这里 **延迟敏感**——市场每分钟在变，5 分钟结算窗的进入时机 = 阿尔法本身。

### 4.2 代表仓库

| 仓库 | 信号 | 架构亮点 |
| --- | --- | --- |
| [suislanchez/polymarket-kalshi-weather-bot](https://github.com/suislanchez/polymarket-kalshi-weather-bot) | RSI + Momentum(1/5/15m) + VWAP + SMA + Skew | 加权融合,convergence filter |
| [aulekator/Polymarket-BTC-15-Minute-Trading-Bot](https://github.com/aulekator/Polymarket-BTC-15-Minute-Trading-Bot) | Spike Detection + Sentiment + Divergence | 7-phase, NautilusTrader, Grafana |
| [ThinkEnigmatic/polymarket-bot-arena](https://github.com/ThinkEnigmatic/polymarket-bot-arena) | 4 strategies arena | bandit-style self-learning |
| [solcanine/openclaw-ai-polymarket-trading-bot](https://github.com/solcanine/openclaw-ai-polymarket-trading-bot) | LLM agent | TypeScript |
| [PolyScripts/polymarket-5min-15min-1hr-btc-arbitrage-trading-bot-rust](https://github.com/PolyScripts/polymarket-5min-15min-1hr-btc-arbitrage-trading-bot-rust) | 套利 + 短期 | Rust, 20ms 下单 |
| [FrondEnt/PolymarketBTC15mAssistant](https://github.com/FrondEnt/PolymarketBTC15mAssistant) | 辅助看盘 | 实时面板 |

### 4.3 多信号融合范式（suislanchez 详细拆解）

bot 每 60 秒做一次扫描，对每个 active 5-min 窗口：

| 信号 | 计算 | 含义 |
| --- | --- | --- |
| **RSI(14)** | 14 根 1m K 线的相对强弱 | 超买 (>70) 偏空，超卖 (<30) 偏多 |
| **Momentum 1m / 5m / 15m** | 各窗收益率 | 多窗一致 → 趋势确认 |
| **VWAP Deviation** | `(P − VWAP)/VWAP` | 偏离过大可能均值回归 |
| **SMA Crossover** | 5m vs 20m SMA | 多空趋势方向 |
| **Market Skew** | `p_market_yes − 0.5` | 群体偏度 (反指信号) |

**关键设计**：单信号常被噪声欺骗，强制要求 **convergence filter** —— 4 个独立信号中至少 2 个同向才执行。最终用 weighted vote 把信号映射到 `p_model ∈ [0.35, 0.65]` (避免极端值，因 5 分钟内确定性极低)。

```python
def fuse_signals(rsi, mom_1m, mom_5m, vwap_dev, sma_sig, skew):
    votes = []
    votes.append(1 if rsi < 30 else (-1 if rsi > 70 else 0))
    votes.append(1 if mom_1m > 0 else -1)
    votes.append(1 if sma_sig == "GOLDEN" else -1)
    votes.append(-1 if vwap_dev > 0.003 else (1 if vwap_dev < -0.003 else 0))
    
    nonzero = [v for v in votes if v != 0]
    if len(nonzero) < 2: return None
    if sum(1 for v in nonzero if v > 0) < 2 and sum(1 for v in nonzero if v < 0) < 2:
        return None  # convergence not reached
    
    raw = sum(votes) / 4
    p_up = 0.5 + raw * 0.075        # 映射到 [0.35, 0.65]
    p_up = max(0.35, min(0.65, p_up))
    return p_up
```

### 4.4 7-Phase 生产架构（aulekator 仓库）

```
┌────────────┐  ┌─────────────┐  ┌────────────┐  ┌──────────────┐
│ Phase 1    │  │ Phase 2     │  │ Phase 3    │  │ Phase 4      │
│ Data       │→ │ Ingestion   │→ │ Nautilus   │→ │ Strategy     │
│ Sources    │  │ adapters    │  │ Core (data │  │ Brain        │
│ (Coinbase, │  │ + validators│  │ engine,    │  │ - spike      │
│ Binance,   │  │ + rate-limit│  │ event bus) │  │ - sentiment  │
│ News, F&G) │  │             │  │            │  │ - divergence │
└────────────┘  └─────────────┘  └────────────┘  └──────┬───────┘
                                                        │
                                                        ▼
┌──────────────────────────────────────────────────────────────┐
│ Phase 5 Execution                                            │
│  - risk_engine.py  (size cap, SL 30%, TP 20%, exposure)      │
│  - polymarket_client.py  (py-clob-client wrapper)            │
└────────────────┬─────────────────────────────────────────────┘
                 │
       ┌─────────▼─────────┐    ┌─────────────────────────┐
       │ Phase 6 Monitoring│    │ Phase 7 Feedback        │
       │ Grafana + Prom    │←──→│ learning_engine.py      │
       │ - win rate        │    │ - re-weight signals     │
       │ - latency         │    │   based on rolling PnL  │
       └───────────────────┘    └─────────────────────────┘
```

`Phase 7` 的精髓：每笔交易结算后，把 (signals_at_entry, outcome) 写入 store，每 200 笔做一次 logistic regression 重新拟合 signal weights，实现 **在线学习**。

### 4.5 Bot Arena (ThinkEnigmatic) 的多臂老虎机思路

不是设计一个"好"策略，而是让 4 个策略并行下注、并行结算，用 **Thompson sampling** 或 **UCB1** 选择当下分配权重最高的策略。优势：

- 不需要预先确定哪种信号最稳。
- 市场风格切换时自动迁移（趋势 vs 震荡）。
- 失败策略的曝险自然收缩到 0。

### 4.6 风控要点

- **最小价差**：5min 市场 spread 通常 2-5¢，要求 `entry_price ≤ 0.55` 防止下半场赔率不对称。
- **单笔上限**：`$75 ~ $100`，1 vCPU/512MB worker 配合。
- **每日止损**：`DAILY_LOSS_LIMIT = $300` (suislanchez)，触发后停止。
- **结算源争议**：建议同时用 Coinbase/Kraken/Binance 三源 (fallback chain)；若三源价差 > 0.5%，跳过该轮。
- **不在收尾 30s 内下单**：避免被 maker 退场后的 wide spread 噬咬。

---

## 五、套利与做市策略

### 5.1 二元互补套利 (YES + NO < 1)

**思想**：因新闻冲击、流动性时滞、单边追买，YES_ask + NO_ask 偶尔降到 < 1.00。例如 YES=$0.517, NO=$0.449，两个一起买 = $0.966，到期任一结算都拿回 $1.00 → 净利 3.4%（不计 gas + 撮合费）。

```python
def find_yes_no_arb(orderbook, max_cost=0.985, min_size=10):
    yes_levels = orderbook.yes_asks   # [(price, size), ...]
    no_levels  = orderbook.no_asks
    cost = yes_levels[0][0] + no_levels[0][0]
    if cost >= max_cost: return None
    size = min(yes_levels[0][1], no_levels[0][1])
    if size < min_size: return None
    profit_per_unit = 1.0 - cost
    return ArbOpp(size=size, cost=cost, profit=size * profit_per_unit)
```

实务要点：

- 同一 condition 的 YES/NO 共用 outcome token，本质上是 `mint by USDC` 的反向操作。若平台支持 "merge for 1 USDC"，则可即时兑现而非等到期。
- 这种 edge 通常 < 1%，需要 Rust/Go 级别低延迟（参考 [PolyScripts Rust bot](https://github.com/PolyScripts/polymarket-5min-15min-1hr-btc-arbitrage-trading-bot-rust)，号称 20ms 下单）。
- 注意 Polymarket 的 maker rebate vs taker fee 结构：吃单方付费 $0.02/$1 等，要把费用算进 `max_cost`。

### 5.2 跨平台套利 (Polymarket vs Kalshi)

[suislanchez/polymarket-kalshi-weather-bot](https://github.com/suislanchez/polymarket-kalshi-weather-bot) 同时接两个平台的天气市场。如果同一事件 (e.g. "NYC high > 80F today") 在 Kalshi 价格 0.65，Polymarket 0.55，可以 Polymarket 买 YES + Kalshi 卖 YES (或反向)，锁定 10¢ 价差。

挑战：

- 两个平台的合约定义微妙不同（机场代码、四舍五入规则、时区）。
- 资金跨平台无法瞬移；需要在双方各预存余额。
- Kalshi 需 RSA-PSS 签名认证 (CFTC 合规)；Polymarket 是钱包签名。

### 5.3 官方做市 — `Polymarket/poly-market-maker` 的 Bands & AMM

[Polymarket/poly-market-maker](https://github.com/Polymarket/poly-market-maker) 是官方开源的市场做市 keeper。每 `sync_interval` (默认 30s) 执行：

```
1. 抓取当前 midpoint
2. 计算 expected orders (按策略)
3. diff(expected, open_orders) → cancels + new placements
4. 执行 cancel + place
```

#### Bands 策略

围绕 midpoint 创建 N 层 "bands"，每层定义 `[margin_min, margin_max, target_amount]`。例如 mid = $0.50：

| Band | margin | 报价区间 | 目标累计 size |
| --- | --- | --- | --- |
| Buy 1 | 0.5%-2% | $0.49 ~ $0.4975 | 30 tokens |
| Buy 2 | 2%-5%   | $0.475 ~ $0.49 | 50 tokens |
| Sell 1 | 0.5%-2% | $0.5025 ~ $0.51 | 30 tokens |
| Sell 2 | 2%-5%  | $0.51 ~ $0.525 | 50 tokens |

代码片段（仿 `bands.py`）：

```python
def expected_orders_bands(mid, bands, side):
    orders = []
    for b in bands:
        if side == "BUY":
            p_high = mid * (1 - b.margin_min)
            p_low  = mid * (1 - b.margin_max)
        else:
            p_low  = mid * (1 + b.margin_min)
            p_high = mid * (1 + b.margin_max)
        price = (p_high + p_low) / 2
        orders.append(Order(side, price, b.target_amount))
    return orders
```

**核心思想**：用多层挂单覆盖"价格不确定的范围"，既能在 mid 附近捕获 maker rebate，又在远端用大单挂等极端行情提供深度，从中赚取价差 + Polymarket maker reward。

#### AMM 策略

借鉴 Uniswap-style `x·y = k` 模型，在订单簿上模拟 AMM 报价曲线，对于市场偏离虚拟 reserve 平衡点的程度做反方向报价。优点：不需要主动调参，参数化为 `(k, fee)` 即可；缺点：极端单边行情下持续被吃掉一侧仓位（无常损失）。

### 5.4 做市风控

| 风险 | 控制手段 |
| --- | --- |
| 单边行情把库存吃光 | inventory band：到达 max_long 后只挂卖 |
| 信息事件 (新闻) | 价格跳变阈值触发 cancel-all |
| 结算前价格收敛 | 距 expiry < 1h 自动收窄 spread / 退出 |
| API 速率限制 | 每 syncInterval 内 diff，避免无意义撤换 |
| MEV / 抢跑撤单 | 用 batch cancel + replace |

### 5.5 资金效率 & maker reward

Polymarket 推出 maker reward 计划，挂单越接近 midpoint、size 越大、占报价时长越长，分得的奖励越多。一些做市策略 **不追求 alpha**，只追求最大化 reward：

- 在低波动期挂窄 spread（如 0.5%-1%）；
- size 配置略大于市场平均；
- 频繁撤换 → 维持 best bid/ask 占位。

---

## 六、横向对比与策略选择建议

| 维度 | 跟单 | 天气 | 5 分钟 BTC | 套利做市 |
| --- | --- | --- | --- | --- |
| 主要 alpha 来源 | leader 信息 | 气象模型 | 微结构 | 流动性失衡 / 价差 |
| 延迟敏感度 | 中 (秒级) | 低 (分钟级) | 高 (亚秒) | 极高 (毫秒) |
| 资金需求 | 中 | 小 ($1k-$5k) | 小 ($100-$1k) | 中到大 |
| 单笔预期收益 | 5-30% | 8-25% | 2-5% | 0.3-3% |
| 胜率 | 跟随 leader | 模型校准良好时 60-70% | 50-55% | 接近确定（但稀有） |
| 主要风险 | leader 失效/反向 | 模型漂移、结算源 | 噪声/信号失效 | 撞单/费用吃掉利润 |
| 适合人群 | 没时间研究者 | 有量化基础 | 高频交易经验 | 工程能力强 |
| 技术栈门槛 | 低 | 中 | 中-高 | 高 |
| 推荐起步仓库 | GiordanoSouza | suislanchez | aulekator | poly-market-maker |

**组合建议**：

1. **保守组合**（资金 < $5k）：跟单 70% + 天气 30%。
2. **激进组合**（资金 $5k-$20k）：5 分钟 BTC 40% + 天气 30% + 套利 30%。
3. **机构组合**（资金 > $50k）：做市 50% + 套利 30% + 跟单 (作为 alpha source) 20%。

---

## 七、风险控制与合规

### 7.1 通用风控清单（适用所有策略）

- **资金分仓**：交易 wallet 与冷钱包分离，单 wallet 不放超过 2 周交易量。
- **API key 隔离**：每个 strategy 独立 API key，便于权限和审计。
- **断路器**：单日亏损 / 单笔异常滑点 / API 错误率均设阈值。
- **日志与对账**：所有 (signal, intent, order, fill, settle) 落地 SQL，每周对账。
- **撤单优先**：检测异常时先撤单再退场，避免被动持仓被夹。
- **gas 余额监控**：Polygon 上 MATIC 余额低 → 下单失败，需告警。

### 7.2 合规与法律提示

Polymarket [服务条款](https://polymarket.com/tos) 明确禁止美国及部分司法辖区居民交易（包括用 bot）。任何在受限地区运行 bot 的部署，存在合规风险。Polymarket/agents 仓库 README 中亦专门提示。

天气与体育市场在某些司法辖区可能被认定为博彩，需关注本地法律。

### 7.3 操作安全

- 私钥不进 git；用 `.env` + `.gitignore` + 必要时硬件钱包或 KMS。
- 切勿把 leader 钱包跟单与自有钱包共用同一私钥 / 同一 nonce 序列。
- 公网部署用 fail2ban + key-only SSH + 容器化隔离。

---

## 八、参考仓库与资源总览

**官方仓库**

- [Polymarket/py-clob-client](https://github.com/Polymarket/py-clob-client) — Python CLOB 客户端
- [Polymarket/py-clob-client-v2](https://github.com/Polymarket/py-clob-client-v2) — v2 (双层鉴权)
- [Polymarket/poly-market-maker](https://github.com/Polymarket/poly-market-maker) — 官方做市机器人 (Bands / AMM)
- [Polymarket/agents](https://github.com/Polymarket/agents) — AI 交易代理框架 (LangChain + Chroma RAG)
- [Polymarket/real-time-data-client](https://github.com/Polymarket/real-time-data-client) — WebSocket 实时数据 TS 客户端

**跟单交易**

- [GiordanoSouza/polymarket-copy-trading-bot](https://github.com/GiordanoSouza/polymarket-copy-trading-bot)
- [unitmargaretaustin/Polymarket-copy-trading-bot](https://github.com/unitmargaretaustin/Polymarket-copy-trading-bot)
- [Xyryllium/polymarket-tracker-bot](https://github.com/Xyryllium/polymarket-tracker-bot)
- [LNLUTS/polymarket-copy-trading-bot](https://github.com/LNLUTS/polymarket-copy-trading-bot)
- [roswelly/polymarket-copy-trading-bot](https://github.com/roswelly/polymarket-copy-trading-bot)
- [earthskyorg/Polymarket-Copy-Trading-Bot](https://github.com/earthskyorg/Polymarket-Copy-Trading-Bot)
- [gamma-trade-lab/polymarket-copy-trading-bot](https://github.com/gamma-trade-lab/polymarket-copy-trading-bot)

**天气策略**

- [suislanchez/polymarket-kalshi-weather-bot](https://github.com/suislanchez/polymarket-kalshi-weather-bot) ★ 最详尽
- [alteregoeth-ai/weatherbot](https://github.com/alteregoeth-ai/weatherbot)
- [yangyuan-zhen/PolyWeather](https://github.com/yangyuan-zhen/PolyWeather)
- [hcharper/polyBot-Weather](https://github.com/hcharper/polyBot-Weather)
- [MoonsatProtocol/Polymarket-Weather-Bot](https://github.com/MoonsatProtocol/Polymarket-Weather-Bot)

**5 分钟 BTC**

- [aulekator/Polymarket-BTC-15-Minute-Trading-Bot](https://github.com/aulekator/Polymarket-BTC-15-Minute-Trading-Bot) ★ 工业级架构
- [ThinkEnigmatic/polymarket-bot-arena](https://github.com/ThinkEnigmatic/polymarket-bot-arena) ★ 多臂老虎机
- [solcanine/openclaw-ai-polymarket-trading-bot](https://github.com/solcanine/openclaw-ai-polymarket-trading-bot)
- [FrondEnt/PolymarketBTC15mAssistant](https://github.com/FrondEnt/PolymarketBTC15mAssistant)
- [Poly-Dev05/polymarket-trading-bot](https://github.com/Poly-Dev05/polymarket-trading-bot)

**套利与做市**

- [PolyScripts/polymarket-5min-15min-1hr-btc-arbitrage-trading-bot-rust](https://github.com/PolyScripts/polymarket-5min-15min-1hr-btc-arbitrage-trading-bot-rust)
- [Trum3it/polymarket-arbitrage-bot](https://github.com/Trum3it/polymarket-arbitrage-bot) — market-neutral
- [pontiggia/poly-bot](https://github.com/pontiggia/poly-bot) — Rust HFT
- [crellOS/polymarket-arbitrage-bot-pre-order-15m-markets](https://github.com/crellOS/polymarket-arbitrage-bot-pre-order-15m-markets)
- [cakaroni/polymarket-arbitrage-bot-btc-15m](https://github.com/cakaroni/polymarket-arbitrage-bot-btc-15m)
- [Sectionnaenumerate/Polymarket-Kalshi-btc-arbitrage-bot](https://github.com/Sectionnaenumerate/Polymarket-Kalshi-btc-arbitrage-bot)
- [elielieli909/polymarket-marketmaking](https://github.com/elielieli909/polymarket-marketmaking)

**资源汇总**

- [aarora4/Awesome-Prediction-Market-Tools](https://github.com/aarora4/Awesome-Prediction-Market-Tools)
- [harish-garg/Awesome-Polymarket-Tools](https://github.com/harish-garg/Awesome-Polymarket-Tools)
- Polymarket 官方文档：https://docs.polymarket.com/

---

## 附录 A：典型 `.env` 与 config 模板

```env
# Polymarket
POLYMARKET_PK=0x...
POLYMARKET_PROXY_ADDRESS=0x...
POLYGON_RPC_URL=https://polygon-rpc.com

# Copy trading
LEADER_WALLETS=0xabc,0xdef
COPY_CAPITAL_USD=1000
MAX_PER_TRADE_USD=50
CATEGORY_LIMITS=politics:300,crypto:300,sports:200

# Weather
OPEN_METEO_BASE=https://api.open-meteo.com/v1
WEATHER_CITIES=nyc,chi,mia,lax,den
WEATHER_MIN_EDGE=0.08

# BTC 5m
BTC_SCAN_INTERVAL=60
BTC_MIN_EDGE=0.02
BTC_MAX_ENTRY=0.55
BTC_MAX_TRADE=75

# Market maker
STRATEGY=Bands
CONDITION_ID=0x...
SYNC_INTERVAL=30

# Risk
DAILY_LOSS_LIMIT=300
MAX_OPEN_POSITIONS=20
```

## 附录 B：策略实现工程化的几个统一模式

1. **三层日志**：`signal` → `intent` → `order`，每层带 trace_id，可端到端追踪。
2. **dry-run / paper trading 开关**：所有下单函数支持 `dry_run=True` 走 mock fill，CI 跑 e2e。
3. **状态可持久化**：开/重启不丢仓位状态，需将 (positions, leader_state, last_seen_event) 写 PostgreSQL 或 Redis。
4. **每日 EOD 报告**：自动出 PnL、Sharpe、信号校准、滑点统计 → email/Discord/Slack。
5. **Watchdog**：另起一个进程监控主 bot 心跳与 PnL 异常，触发紧急 cancel-all。

---

> **免责声明**：本报告为开源代码研究汇总，所有策略与代码片段仅供学习。预测市场涉及资金损失风险，并在部分地区受法律限制。任何实操前请充分回测、纸交易、并咨询合规意见。
