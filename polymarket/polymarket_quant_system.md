---
title: "Polymarket 量化交易系统设计文档"
type: design
status: active
tags: [polymarket, design]
created: 2026-04-10
updated: 2026-04-25
---

# Polymarket 量化交易系统设计文档

> 基于 Polymarket 官方 API 的完整量化交易系统规划，涵盖数据采集、策略计算、风控执行、监控收益全流程。

---

## 目录

1. [核心概念](#1-核心概念)
2. [系统架构](#2-系统架构)
3. [API 三层架构](#3-api-三层架构)
4. [主循环流程](#4-主循环流程)
5. [各步骤 API 接口详细规范](#5-各步骤-api-接口详细规范)
   - [步骤 1 — 市场扫描](#步骤-1--市场扫描)
   - [步骤 2 — 订单簿与价格](#步骤-2--订单簿与价格)
   - [步骤 3 — 持仓同步](#步骤-3--持仓同步)
   - [步骤 4 — 策略与信号](#步骤-4--策略与信号)
   - [步骤 5 — 风控检查](#步骤-5--风控检查)
   - [步骤 6 — 订单执行](#步骤-6--订单执行)
   - [步骤 7 — 监控与结算](#步骤-7--监控与结算)
6. [收益计算公式](#6-收益计算公式)
7. [关键设计决策](#7-关键设计决策)
8. [合约地址参考](#8-合约地址参考)

---

## 1. 核心概念

### 市场结构

Polymarket 采用层级结构组织市场：

```text
Event（事件）
  └── Market A（市场 A）
        ├── Yes Token (ERC-1155)
        └── No Token (ERC-1155)
  └── Market B（市场 B）
        ├── Yes Token
        └── No Token
```

- **Event**：顶层问题，如"2025年美联储降息几次？"，由 `event_id` 和 slug 标识。
- **Market**：事件下的具体选项，由 `conditionId` 标识。
- **Token**：每个 Market 有一对 Yes/No ERC-1155 Token，由 `tokenID` 标识。

### Token 定价机制

- 每枚 Yes Token + 每枚 No Token = $1.00 USDC.e（永远成立）
- Token 价格即隐含概率：Yes Token 报价 $0.65 = 市场认为该事件有 65% 概率发生
- 市场结算后：获胜 Token 兑换 $1.00，失败 Token 归零

### 交易方式

Polymarket 使用 **CLOB（中央限价订单簿）** 撮合：
- 所有订单均为限价单（含立即成交的"市价单"本质也是限价单）
- 订单通过 EIP-712 签名，链下撮合，链上结算
- 通过 CTF Exchange 合约完成链上交割

---

## 2. 系统架构

量化系统分为五大模块：

```text
┌─────────────────────────────────────────────────────────┐
│                    外部数据源                            │
│  Gamma API │ CLOB API │ Data API │ WebSocket │ Subgraph │
└─────────────────────┬───────────────────────────────────┘
                       │
┌─────────────────────▼───────────────────────────────────┐
│                   数据层 Data Layer                       │
│   市场扫描器 │ 实时价格订阅（WS）│ 仓位 & 余额同步       │
└─────────────────────┬───────────────────────────────────┘
                       │
          ┌────────────▼────────────┐   ┌──────────────────┐
          │     策略层 Strategy      │──▶│   风控层 Risk     │
          │  信号引擎 │ 定价模型     │   │ 仓位限额 │ 止损   │
          │  Kelly 仓位计算          │   │ 敞口控制          │
          └────────────┬────────────┘   └────────┬─────────┘
                       │                          │
┌─────────────────────▼──────────────────────────▼────────┐
│                   执行层 Execution Layer                   │
│  订单管理器 │ 批量下单 │ Redeem 执行器 │ Relayer 代理     │
└─────────────────────┬───────────────────────────────────┘
                       │
┌─────────────────────▼───────────────────────────────────┐
│                   监控层 Monitoring                        │
│  P&L 追踪 │ 订单状态轮询 │ 市场结算检测 │ 告警机制        │
└─────────────────────────────────────────────────────────┘
```

### 各层职责

| 层级 | 核心职责 | 依赖接口 |
|------|----------|----------|
| 数据层 | 拉取并维护市场数据、价格、持仓状态 | Gamma API, CLOB API, Data API, WS |
| 策略层 | 生成交易信号、计算仓位大小 | 本地计算（无直接 API 调用） |
| 风控层 | 过滤违规信号、控制总敞口 | 本地状态 |
| 执行层 | 提交/撤销订单、链上兑换 | CLOB API, Relayer |
| 监控层 | 追踪状态、检测结算、记录 P&L | CLOB API, Gamma API, Data API |

---

## 3. API 三层架构

### 3.1 Gamma API — 市场数据与发现

**域名**：`https://gamma-api.polymarket.com`

| 端点 | 说明 |
|------|------|
| `GET /markets` | 获取市场列表，支持过滤、排序、分页 |
| `GET /markets/{conditionId}` | 获取单个市场详情及结算状态 |
| `GET /events` | 获取事件列表 |
| `GET /events/{eventId}` | 获取事件下所有市场 |

### 3.2 CLOB API — 交易核心引擎

**域名**：`https://clob.polymarket.com`

| 端点 | 方法 | 说明 |
|------|------|------|
| `/order` | POST | 提交单笔订单（需 EIP-712 签名）|
| `/orders` | POST | 批量提交订单（最多 15 笔）|
| `/order/{orderID}` | GET | 查询订单状态 |
| `/order/{orderID}` | DELETE | 撤销单笔订单 |
| `/orders` | DELETE | 批量撤单 |
| `/book` | GET | 获取订单簿快照 |
| `/price` | GET | 获取最新成交价 |
| `/prices` | GET | 批量获取多 Token 价格 |
| `/prices-history` | GET | 获取历史价格（OHLC）|
| `/orders` | GET | 查询当前挂单列表 |
| `/trades` | GET | 查询历史成交记录 |

### 3.3 Data API — 用户数据与历史

**域名**：`https://data-api.polymarket.com`

| 端点 | 说明 |
|------|------|
| `GET /positions` | 查询用户持仓 |
| `GET /trade-history` | 查询交易历史 |
| `GET /pnl` | 查询盈亏数据 |
| `GET /activity` | 查询账户活动 |
| `GET /portfolio` | 查询组合概览 |
| `GET /balance` | 查询 USDC.e 余额 |

### 3.4 WebSocket — 实时数据流

**域名**：`wss://ws.polymarket.com`

```json
// 订阅示例
{
  "type": "market",
  "assets_ids": ["tokenID_1", "tokenID_2"]
}
```

| 事件类型 | 说明 |
|----------|------|
| `price_change` | 最新成交价变动 |
| `book` | 订单簿 delta 更新 |
| `last_trade_price` | 最新成交价 |
| `market_resolved` | 市场结算通知 |
| `tick_size_change` | 最小报价单位变更 |

### 3.5 Subgraph — 链上历史数据

通过 Goldsky 托管的 GraphQL 端点查询链上原始数据，适用于：
- 历史成交记录回溯
- Token 转账记录
- 链上兑换历史

---

## 4. 主循环流程

系统以固定间隔（建议 5-30 秒，视策略而定）执行以下循环：

```text
┌─ 主循环开始（每 N 秒）─────────────────────────────────┐
│                                                          │
│  步骤 1  扫描活跃市场                                    │
│          GET /markets?active=true&limit=100              │
│          过滤 resolved=false，按流动性排序               │
│                         │                               │
│  步骤 2  拉取订单簿 & 价格                               │
│          WS 订阅推送 / GET /book（降级）                 │
│          更新本地 orderbook 缓存                         │
│                         │                               │
│  步骤 3  同步持仓 & 余额                                 │
│          GET /positions + GET /balance                   │
│                         │                               │
│  步骤 4  策略计算 / 信号生成                             │
│          本地计算，无 API 调用                           │
│                         │                               │
│            ┌────────────▼──────────┐                   │
│            │   有交易信号？         │                   │
│            └──────┬──────┬─────────┘                   │
│                  是      否──────────────────┐          │
│                   │                          │          │
│  步骤 5  风控检查                             │          │
│          仓位上限 / 单市场敞口 / 余额充足     │          │
│                   │                          │          │
│            ┌──────▼──────────┐               │          │
│            │  通过风控？      │               │          │
│            └──────┬──────────┘               │          │
│                  是  否──────────────┐        │          │
│                   │                 │        │          │
│  步骤 6  提交订单                   │        │          │
│          POST /order（签名）        │        │          │
│                   │                 │        │          │
│  步骤 7  监控 & 结算检测  ◀─────────┴────────┘          │
│          轮询订单状态 / 检测 resolved / 触发 redeem      │
│                                                          │
│                   ↻ 等待下一轮                           │
└──────────────────────────────────────────────────────────┘
```

---

## 5. 各步骤 API 接口详细规范

---

### 步骤 1 — 市场扫描

**触发时机**：每轮循环开始，或启动时初始化

```http
GET https://gamma-api.polymarket.com/markets
    ?active=true
    &limit=100
    &order=volume24hr:desc
```

**关键响应字段**：

| 字段 | 类型 | 用途 |
|------|------|------|
| `conditionId` | string | 市场唯一标识符，后续所有操作的基础 |
| `question` | string | 市场问题文本 |
| `outcomePrices` | string (JSON) | Yes/No 当前价格，如 `["0.65","0.35"]` |
| `volume24hr` | number | 24小时成交量，用于筛选流动性 |
| `liquidity` | number | 当前流动性深度 |
| `endDate` | datetime | 市场截止日期，过滤已过期市场 |
| `resolved` | bool | 是否已结算，过滤已结算市场 |
| `tokens` | array | Yes/No Token 信息，包含 `tokenId` |

**过滤逻辑示例（Python 伪代码）**：

```python
markets = get_markets(active=True, limit=100)
tradeable = [
    m for m in markets
    if not m['resolved']
    and m['endDate'] > now() + timedelta(hours=1)  # 至少1小时后结算
    and m['liquidity'] > 5000                        # 流动性门槛 $5000
    and m['volume24hr'] > 1000                       # 24h成交量门槛
]
```

---

### 步骤 2 — 订单簿与价格

#### 2a. WebSocket 实时订阅（首选）

```javascript
// 连接 & 订阅
const ws = new WebSocket('wss://ws.polymarket.com');
ws.onopen = () => {
  ws.send(JSON.stringify({
    type: 'market',
    assets_ids: ['tokenID_yes', 'tokenID_no']
  }));
};

// 处理推送事件
ws.onmessage = (event) => {
  const data = JSON.parse(event.data);
  if (data.event_type === 'book') {
    updateLocalOrderbook(data.asset_id, data.bids, data.asks);
  }
  if (data.event_type === 'price_change') {
    onPriceUpdate(data.asset_id, data.price);
  }
};
```

**WS 事件关键字段**：

| 字段 | 说明 |
|------|------|
| `asset_id` | Token ID |
| `event_type` | `book` / `price_change` / `last_trade_price` |
| `price` | 最新价格 |
| `bids` | 买单列表 `[{price, size}]` |
| `asks` | 卖单列表 `[{price, size}]` |
| `timestamp` | 事件时间戳 |

#### 2b. REST 降级方案（WS 断线时）

```http
GET https://clob.polymarket.com/book?tokenID={yes_token_id}
```

**响应关键字段**：

| 字段 | 说明 |
|------|------|
| `bids` | 买单 `[{price, size}]`，按价格降序 |
| `asks` | 卖单 `[{price, size}]`，按价格升序 |
| `spread` | 买卖价差 |
| `midpoint` | 中间价 |

#### 2c. 批量价格快照

```http
GET https://clob.polymarket.com/prices?tokenIds={id1},{id2},{id3}
```

用于快速获取多个市场的最新价格，不含完整订单簿深度。

#### 2d. 历史价格（OHLC）

```http
GET https://clob.polymarket.com/prices-history
    ?tokenID={id}
    &interval=1h
    &fidelity=60
    &startTs=1700000000
    &endTs=1700100000
```

| 参数 | 可选值 |
|------|--------|
| `interval` | `1m`, `5m`, `1h`, `1d` |
| `fidelity` | 数据精度（分钟数）|

**响应字段**：`t`（时间戳），`p`（价格）

---

### 步骤 3 — 持仓同步

#### 3a. 查询用户持仓

```http
GET https://data-api.polymarket.com/positions
    ?user={walletAddress}
    &market={conditionId}   # 可选，不填则返回全部持仓
    &sizeThreshold=0.01     # 过滤极小仓位
```

**关键字段**：

| 字段 | 说明 |
|------|------|
| `asset` | Token 名称（Yes/No） |
| `tokenId` | Token ID |
| `size` | 持仓数量（Token 枚数）|
| `avgPrice` | 平均买入价 |
| `currentValue` | 当前市值（基于最新价）|
| `unrealizedPnl` | 未实现盈亏 |
| `conditionId` | 所属市场 |

#### 3a-补充. 查询某市场持仓分布

```http
GET https://data-api.polymarket.com/v1/market-positions
    ?market={conditionId}
    &status=OPEN
    &sortBy=TOKENS
    &sortDirection=DESC
    &limit=50
```

这个接口用于看某个 market 的持仓人分布；`user` 为可选过滤条件。当前官方接口里，`/positions` 仍然是“按用户查持仓”，不能只传 `market`。

#### 3b. 查询余额

```http
GET https://data-api.polymarket.com/balance?user={walletAddress}
```

返回可用 USDC.e 余额，下单前必须校验余额充足。

---

### 步骤 4 — 策略与信号

此步骤在本地执行，无 API 调用。以下是典型策略输入数据和计算逻辑：

#### 输入数据

```python
market_data = {
    'mid_price': 0.52,          # 来自订单簿 midpoint
    'best_bid': 0.51,
    'best_ask': 0.53,
    'spread': 0.02,
    'bid_depth': 1200,           # 最优买价的深度（USDC）
    'ask_depth': 800,
    'price_history': [...],      # 历史 OHLC
    'volume_24h': 50000,
    'liquidity': 20000,
}
```

#### 信号示例（均值回归）

```python
def mean_reversion_signal(price_history, current_price, window=24):
    mean = np.mean(price_history[-window:])
    std = np.std(price_history[-window:])
    z_score = (current_price - mean) / std

    if z_score < -2.0:
        return {'side': 'BUY', 'confidence': abs(z_score) / 4}
    elif z_score > 2.0:
        return {'side': 'SELL', 'confidence': abs(z_score) / 4}
    else:
        return None
```

#### Kelly 仓位计算

```python
def kelly_size(win_prob, payout, bankroll, kelly_fraction=0.25):
    """
    win_prob: 预期胜率（模型输出）
    payout: 赔率（1 / price - 1）
    kelly_fraction: 保守 Kelly（建议 0.25 防止过度下注）
    """
    q = 1 - win_prob
    b = payout
    kelly = (b * win_prob - q) / b
    return bankroll * kelly * kelly_fraction
```

---

### 步骤 5 — 风控检查

在执行层下单前，必须通过以下所有检查：

```python
def risk_check(signal, portfolio_state, config):
    checks = []

    # 1. 单市场最大敞口
    current_exposure = portfolio_state.get_exposure(signal.market_id)
    checks.append(current_exposure + signal.size <= config.MAX_PER_MARKET)

    # 2. 总持仓数量限制
    total_positions = portfolio_state.count_open_positions()
    checks.append(total_positions < config.MAX_OPEN_POSITIONS)

    # 3. 余额充足
    cost = signal.price * signal.size
    checks.append(portfolio_state.usdc_balance >= cost * 1.05)  # 5% buffer

    # 4. 买卖价差过大（流动性不足）
    checks.append(signal.spread < config.MAX_SPREAD_PCT)  # e.g., 0.05

    # 5. 距结算时间过短
    checks.append(signal.end_date > now() + timedelta(hours=config.MIN_HOURS_TO_EXPIRY))

    return all(checks)
```

**推荐风控参数**：

| 参数 | 建议值 | 说明 |
|------|--------|------|
| `MAX_PER_MARKET` | 账户余额的 10% | 单市场最大敞口 |
| `MAX_OPEN_POSITIONS` | 20 | 最大同时持仓市场数 |
| `MAX_SPREAD_PCT` | 5% | 最大可接受价差 |
| `MIN_HOURS_TO_EXPIRY` | 2 | 距结算最短时间 |
| `KELLY_FRACTION` | 0.25 | 保守 Kelly 系数 |

---

### 步骤 6 — 订单执行

#### 6a. 单笔下单

```http
POST https://clob.polymarket.com/order
Content-Type: application/json
Authorization: {CLOB_API_KEY}

{
  "tokenID": "0xabc...",
  "price": "0.52",
  "size": "100",
  "side": "BUY",
  "type": "GTC",
  "nonce": 12345,
  "signature": "0x..."   // EIP-712 签名
}
```

**订单类型说明**：

| 类型 | 行为 | 适用场景 |
|------|------|----------|
| `GTC` | 挂单直到成交或撤销 | 做市商挂单，享 maker 返佣 |
| `GTD` | 挂单到指定时间 | 有时间限制的策略 |
| `FOK` | 全部成交或全部撤销 | 需要确定性执行 |
| `FAK` | 部分成交，剩余撤销 | 可接受部分成交 |

**响应关键字段**：

| 字段 | 说明 |
|------|------|
| `orderID` | 订单唯一 ID，后续查询用 |
| `status` | `live` / `matched` / `canceled` |
| `price` | 成交价 / 挂单价 |
| `size` | 挂单数量 |
| `sizeMatched` | 已成交数量 |

#### 6b. 批量下单（做市商）

```http
POST https://clob.polymarket.com/orders
Content-Type: application/json

[
  {"tokenID": "...", "price": "0.51", "size": "50", "side": "BUY", "type": "GTC", ...},
  {"tokenID": "...", "price": "0.53", "size": "50", "side": "SELL", "type": "GTC", ...}
]
```

- 最多一次提交 15 笔订单
- 推荐先 `DELETE /orders?market=conditionId` 撤销旧报价，再批量提交新报价

#### 6c. 撤单

```http
# 撤单笔
DELETE https://clob.polymarket.com/order/{orderID}

# 批量撤单（指定市场）
DELETE https://clob.polymarket.com/orders?market={conditionId}

# 撤全部挂单
DELETE https://clob.polymarket.com/orders
```

#### 6d. 使用官方 SDK（推荐）

避免手写 EIP-712 签名逻辑，直接使用官方 SDK：

**TypeScript**：
```typescript
import { ClobClient, Side, OrderType } from "@polymarket/clob-client";

const client = new ClobClient(host, chainId, signer, creds);

// 创建并提交订单
const order = await client.createAndPostOrder({
  tokenID: "0xabc...",
  price: 0.52,
  size: 100,
  side: Side.BUY,
  type: OrderType.GTC,
}, { tickSize: "0.01", negRisk: false });
```

**Python**：
```python
from py_clob_client.client import ClobClient
from py_clob_client.clob_types import OrderArgs, OrderType

client = ClobClient(host, key=private_key, chain_id=137)

order = client.create_and_post_order(OrderArgs(
    token_id="0xabc...",
    price=0.52,
    size=100,
    side="BUY",
    order_type=OrderType.GTC,
))
```

---

### 步骤 7 — 监控与结算

#### 7a. 订单状态轮询

```http
GET https://clob.polymarket.com/order/{orderID}
```

**状态机**：

```text
live → matched（完全成交）
     → partially_filled（部分成交）
     → canceled（已撤销）
     → expired（已过期，GTD）
```

**轮询策略**：指数退避
```python
def poll_order_status(order_id, max_attempts=10):
    delay = 1
    for i in range(max_attempts):
        status = get_order_status(order_id)
        if status in ['matched', 'canceled', 'expired']:
            return status
        time.sleep(delay)
        delay = min(delay * 2, 30)  # 最大等待 30 秒
    return 'timeout'
```

#### 7b. 交易历史查询

```http
GET https://clob.polymarket.com/trades
    ?market={conditionId}
    &maker_address={walletAddress}
    &limit=50
    &after={timestamp}
```

**关键字段**：`tradeID`, `price`, `size`, `side`, `fee`, `timestamp`

#### 7c. 市场结算检测

将持仓中的所有市场放入"待结算 watchlist"，每分钟轮询：

```http
GET https://gamma-api.polymarket.com/markets/{conditionId}
```

**检测逻辑**：

```python
def check_resolution(market_id, my_position_side):
    market = get_market(market_id)

    if market['resolved']:
        outcome = market['resolutionOutcome']  # 'Yes' 或 'No'

        if outcome == my_position_side:
            # 持有获胜方 Token，触发 redeem
            trigger_redeem(market_id)
            log(f"市场 {market_id} 已结算，我方获胜，准备兑换收益")
        else:
            # 持有失败方 Token，记录亏损
            mark_loss(market_id)
            log(f"市场 {market_id} 已结算，我方失败")

        # 特殊情况：50/50 结算
        if market.get('resolutionOutcome') == 'Unknown':
            trigger_redeem_50(market_id)  # 每枚 Token 兑换 $0.50
```

**关键字段**：

| 字段 | 说明 |
|------|------|
| `resolved` | bool，是否已结算 |
| `resolutionOutcome` | `"Yes"` / `"No"` / `"Unknown"` |
| `resolutionTime` | 结算时间戳 |

#### 7d. 兑换收益（Redeem）

**方式一：通过 Relayer（无 gas，推荐）**

```http
POST https://relayer.polymarket.com/redeem
Authorization: {BUILDER_API_KEY}

{
  "conditionId": "0xabc...",
  "amounts": [100]
}
```

**方式二：直接调 CTF 合约（Polygon 链上）**

```solidity
// Conditional Token Framework 合约
CTF.redeemPositions(
  collateral,    // USDC.e 合约地址
  bytes32(0),    // parentCollectionId（标准市场为 0）
  conditionId,   // 市场 conditionId
  [1, 2]         // indexSets（Yes: 1, No: 2）
)
```

CTF 合约地址（Polygon Mainnet）：`0x4D97DCd97eC945f40cF65F87097ACe5EA0476045`

#### 7e. P&L 查询与记录

```http
GET https://data-api.polymarket.com/pnl
    ?user={walletAddress}
    &window=1d    # 1d | 7d | 30d | all
```

**关键字段**：

| 字段 | 说明 |
|------|------|
| `realizedPnl` | 已实现盈亏（已结算市场）|
| `unrealizedPnl` | 未实现盈亏（持仓中市场）|
| `totalVolume` | 总交易量 |
| `winRate` | 胜率 |
| `roi` | 投资回报率 |

**未实现盈亏自行计算**：

```python
unrealized_pnl = (current_price - avg_cost) * position_size
```

---

## 6. 收益计算公式

### 基础公式

```text
总收益（获胜时）= 持仓数量 × $1.00
总成本          = 买入均价 × 持仓数量 + 手续费
净利润          = 总收益 - 总成本
ROI             = 净利润 / 总成本 × 100%
```

### 手续费说明

| 角色 | 费率 | 说明 |
|------|------|------|
| Taker（吃单） | ~1% | FOK/FAK 立即成交 |
| Maker（挂单） | -0.01% ~ +0.02% | GTC 挂单等待撮合，实际有返佣 |

> 量化策略如果频繁吃单，手续费会显著侵蚀收益。尽量使用 GTC 挂单做 Maker。

### 示例计算

```text
场景：以 $0.40 买入 100 枚 Yes Token，最终获胜

总成本  = 0.40 × 100 + 0.40（手续费约1%）= $40.40
总收益  = 100 × $1.00 = $100.00
净利润  = $100.00 - $40.40 = $59.60
ROI     = 59.60 / 40.40 ≈ 147.5%
```

---

## 7. 关键设计决策

### 7.1 数据消费：WS 优先，REST 降级

```text
实时数据流：WebSocket（price_change / book delta）
              │ 断线时降级
              ▼
REST 补全：GET /book + GET /prices（一次性快照）
```

- 维护本地内存 orderbook，WS 推送后 apply delta
- WS 心跳检测间隔：30 秒
- 重连策略：指数退避，最大 60 秒

### 7.2 轮询频率规划

| 数据类型 | 方式 | 建议频率 |
|----------|------|----------|
| 实时价格/订单簿 | WebSocket | 推送驱动 |
| 持仓同步 | REST | 每 10-15 秒 |
| 市场列表刷新 | REST | 每 30-60 秒 |
| 待结算市场检测 | REST | 每 60 秒 |
| 订单状态（下单后）| REST | 指数退避 |
| P&L 快照记录 | REST | 每 5 分钟 |

> Gamma API + Data API 免费额度约 1000 次/小时。WebSocket 不占 REST 配额。

### 7.3 签名流程

所有 CLOB 写操作（POST /order）需要：

```text
1. 构建 Order 结构体（tokenID, price, size, side, nonce, expiration）
2. 用私钥生成 EIP-712 签名
3. 将签名附在 POST body 中
4. 服务端验签后进入撮合队列
```

**强烈建议使用官方 SDK**，手写 EIP-712 容易出错。

### 7.4 Maker vs Taker 策略选择

- 追求低延迟成交 → FOK/FAK（Taker），但手续费高
- 追求低成本 → GTC（Maker），挂单等待，有返佣
- 做市商策略 → 双边挂单（买卖各挂一档），赚取价差

### 7.5 结算监控独立运行

结算检测逻辑必须独立于主交易循环，即使当前没有新信号也不能停止：

```python
# 主线程：交易循环
# 子线程：结算监控（watchlist 中所有持仓市场每分钟轮询）

import threading

def settlement_monitor():
    while True:
        for market_id in watchlist:
            check_resolution(market_id)
        time.sleep(60)

monitor_thread = threading.Thread(target=settlement_monitor, daemon=True)
monitor_thread.start()
```

---

## 8. 合约地址参考

所有合约部署在 **Polygon Mainnet（Chain ID: 137）**

| 合约名称 | 地址 |
|----------|------|
| CTF Exchange（标准市场）| `0x4bFb41d5B3570DeFd03C39a9A4D8dE6Bd8B8982E` |
| Neg Risk CTF Exchange | `0xC5d563A36AE78145C45a50134d48A1215220f80a` |
| Conditional Token Framework | `0x4D97DCd97eC945f40cF65F87097ACe5EA0476045` |
| UmaCtfAdapter v3.0 | `0x157Ce2d672854c848c9b79C49a8Cc6cc89176a49` |
| UmaCtfAdapter v2.0 | `0x6A9D222616C90FcA5754cd1333cFD9b7fb6a4F74` |
| USDC.e（抵押品）| `0x2791Bca1f2de4661ED88A30C99A7a9449Aa84174` |

---

## 附录：推荐资源

| 资源 | 链接 |
|------|------|
| Polymarket 官方文档 | `https://docs.polymarket.com` |
| CLOB TypeScript SDK | `https://github.com/polymarket/clob-client` |
| Python SDK | `https://github.com/polymarket/py-clob-client` |
| UMA Oracle 门户 | `https://oracle.uma.xyz` |
| Polygon PolygonScan | `https://polygonscan.com` |
| Polymarket Discord | `https://discord.gg/polymarket` |

---

*文档生成时间：2026-04-09 | 基于 Polymarket 官方文档整理*

---

<!-- kb-backlink -->
> 本文归属：[Polymarket 知识地图](../docs/MOC-Polymarket.md) ｜ [知识库首页](../docs/知识库首页.md)
