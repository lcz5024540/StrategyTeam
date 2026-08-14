---
title: "Hyperliquid × Polymarket 跨平台二元市场套利可行性调研"
type: research
status: draft
tags: [hyperliquid, polymarket, arbitrage, prediction-market, cross-platform]
created: 2026-06-24
updated: 2026-06-24
---

# Hyperliquid × Polymarket 跨平台二元市场套利可行性调研

## 一、核心结论（TL;DR）

**Hyperliquid 本身并不是一个原生的二元预测市场**，但它通过以下方式与预测市场产生交集：

1. **Pre-launch / Event Perps**：Hyperliquid 有上线过基于事件的永续合约（如 token launch pre-markets），这些合约在特定条件下类似二元期权。
2. **HyperEVM 生态**：2024-2025 年 Hyperliquid 推出了 HyperEVM（EVM 兼容层），理论上可以部署预测市场协议，但截至调研时点尚未见到成熟的二元预测市场 dApp。
3. **与 Polymarket 直接对标套利的可行性有限**：两者的市场结构、结算机制、合约定义差异较大，不是简单的"同一事件两个价格"。

**但策略方向并非完全不可行**——如果能找到两平台对同一底层事件的定价差异（如 BTC 价格到期 above/below 某阈值），且结算源一致或高度相关，存在统计套利空间。

---

## 二、Hyperliquid 平台概况

### 2.1 核心定位

Hyperliquid 是一个**高性能永续合约去中心化交易所（Perp DEX）**，运行在自有 L1 链（Hyperliquid L1，基于 HyperBFT 共识）上。核心特点：

- **订单簿模型**（非 AMM），链上撮合，延迟 ~200ms
- **交叉保证金 / 逐仓保证金**支持
- **最高 50x 杠杆**
- **无 gas 交易**（交易本身不收 gas，链由验证器运营）
- **自有代币 HYPE**（2024 年 11 月空投后上线）

### 2.2 Hyperliquid 的"类预测市场"产品

#### (a) Pre-launch Markets（预上市市场）

Hyperliquid 会为即将上市的代币开设 pre-launch perpetual markets。例如在某 token TGE 之前开放交易，以 index price = 0 开始，根据市场预期定价。这类市场有以下特征：

- **结算方式**：TGE 后转为正常 perp；或在特定条件下按最终价格结算
- **不是严格的二元市场**：价格是连续的，不是 Yes/No 二选一
- **但可以构造类二元头寸**：如果你认为 TGE 价格 > $X，买入 perp 并设 TP/SL 相当于一个 pseudo-binary bet

#### (b) Spot（现货）交易

2024 年底 Hyperliquid 开放了现货交易。部分低流动性 spot tokens 在某种程度上也是"预测"性质（meme coins、新项目代币等），但这不构成结构化的预测市场。

#### (c) HyperEVM 生态可能性

HyperEVM（2024 Q4 - 2025 推出）允许在 Hyperliquid L1 上部署 EVM 智能合约。理论上：

- 可以在 HyperEVM 上部署类 Polymarket 的 CTF（Conditional Token Framework）合约
- 可以利用 Hyperliquid 的 orderbook 作为撮合层
- **但截至 2025 年 5 月，尚未见到主流预测市场协议部署在 HyperEVM 上**

### 2.3 Hyperliquid API 结构

Hyperliquid 提供完整的 API：

```
REST: https://api.hyperliquid.xyz
WebSocket: wss://api.hyperliquid.xyz/ws

主要端点：
- POST /info (查询市场数据、账户、订单簿)
- POST /exchange (下单、取消、转账等)

签名方式：EIP-712 类型签名（与 Polymarket 类似！）
链 ID：Hyperliquid L1 (chain id: 1337 testnet / 42161 mainnet proxy)
```

**关键一致性**：Hyperliquid 和 Polymarket 都使用 EIP-712 签名认证订单，这意味着你现有的 `PolymarketJavaClobClient` 中的签名逻辑有较高复用性。

---

## 三、两平台对比分析

### 3.1 市场结构对比

| 维度 | Polymarket | Hyperliquid |
|------|-----------|-------------|
| **市场类型** | 二元事件市场（Yes/No 结果代币） | 永续合约（连续价格） |
| **价格范围** | $0.00 - $1.00（概率定价） | $0 - ∞（资产价格） |
| **结算方式** | 事件发生后 Oracle 判定 → CTF 合约兑付 | 永续无到期（除 pre-launch 结算） |
| **结算时点** | 事件发生时（可能数天到数月） | 无自然结算；pre-launch 在 TGE 时 |
| **抵押品** | USDC（Polygon） | USDC（Hyperliquid L1，从 Arbitrum 桥入） |
| **杠杆** | 无杠杆（1x，花多少买多少份） | 最高 50x |
| **订单簿** | CLOB（链下撮合 + 链上结算） | CLOB（链上撮合） |
| **手续费** | Maker: 0 / Taker: ~2% of notional | Maker: -0.02% / Taker: 0.05% |
| **EIP-712 签名** | ✅ | ✅ |

### 3.2 可套利场景分析

#### 场景 A：同一事件的直接对冲 ❌ 不可行

**原因**：Hyperliquid 没有原生的二元预测市场。它没有"BTC 明天 > $70K? Yes/No"这种市场。所以无法直接找到与 Polymarket 完全对称的二元合约进行对冲。

#### 场景 B：用 Perps 合成二元头寸 ⚠️ 有条件可行

**思路**：Polymarket 上有大量与加密资产价格相关的市场（如"BTC 将在 X 日期前达到 $100K?"），你可以：

- 在 Polymarket 上买 YES（BTC > $100K），假设价格 $0.35
- 在 Hyperliquid 上做空 BTC 永续合约，delta-hedge 掉方向性风险

**问题**：
1. 这不是纯套利，是 **delta-hedged 方向性交易**
2. Perp 的 funding rate 是持续成本
3. Strike price 和 expiry 不精确对应
4. Polymarket 的结算时间不确定，Perp 需要一直持有

#### 场景 C：利用 Hyperliquid Pre-launch 市场 ⚠️ 窗口期极短

**思路**：如果 Polymarket 上有"某 Token 将在 X 天内上线?"的市场，同时 Hyperliquid 上已经开了这个 token 的 pre-launch market，存在信息套利空间。

**问题**：
1. 这是信息不对称套利，不是结构性套利
2. Pre-launch 市场流动性通常极差
3. 窗口期很短

#### 场景 D：等 HyperEVM 生态成熟 🔮 未来可期

如果未来有人在 HyperEVM 上部署了一个类 Polymarket 的预测市场协议（用 Hyperliquid 的 orderbook 做流动性层），那就真的可以做跨平台同事件套利。这和你现有的 Polymarket 内部套利（Yes+No < $1）逻辑完全一致。

---

## 四、真正可行的跨平台套利路径

### 4.1 Polymarket × Kalshi（已验证）

你的 `Polymarket策略深度分析报告.md` 中已经提到了这个方向。Kalshi 是 CFTC 合规的美国预测市场，与 Polymarket 有大量重叠事件（天气、选举、经济指标）。

**优势**：
- 都是二元市场，相同事件
- 结算定义高度接近（但有微妙差异需核实）
- Kalshi 已有 API（RSA-PSS 签名）

**你已经有参考**：`suislanchez/polymarket-kalshi-weather-bot`

### 4.2 Polymarket × 其他预测市场 DEX

| 平台 | 状态 | 市场类型 | 与 Polymarket 重叠度 |
|------|------|---------|---------------------|
| **Kalshi** | 成熟 | 二元 | 高（天气、政治、经济） |
| **Drift Protocol (Solana)** | 运营中 | BET markets | 中等（主要是加密事件） |
| **Azuro** | 运营中 | 体育/事件 | 低-中 |
| **Thales / Overtime (Optimism)** | 运营中 | 体育/二元 | 中等 |
| **Hedgehog Markets (Solana)** | 运营中 | 二元 | 低 |
| **sx.bet** | 运营中 | 体育 | 低 |

### 4.3 Polymarket 内部多市场套利（已有架构）

你现有的 `polymarket_arbitrage_system_design.md` 的核心逻辑（Yes+No < $1）仍然是最纯粹的套利形式。

---

## 五、如果要基于 Hyperliquid 推进，建议路径

### 5.1 短期（可立即开始）

1. **集成 Hyperliquid API Client 到 CoinStrategy**
   - 复用现有 EIP-712 签名框架
   - 实现 `HyperliquidApiService`（类比 `OkxApiService`）
   - 获取 market data、orderbook、执行交易

2. **构建 BTC/ETH 价格类预测市场的 Delta-Hedge 策略**
   - Polymarket YES("BTC > $X by date") + Hyperliquid short BTC perp
   - 不是纯套利，但如果 Polymarket 定价偏离显著，有正期望值

### 5.2 中期

3. **监控 HyperEVM 生态**
   - 关注是否有预测市场协议部署在 HyperEVM 上
   - 如果有，立即集成并实现跨平台 Yes/No 套利

4. **扩展到 Kalshi**（更成熟的跨平台套利对象）
   - 天气市场（5-min weather contracts）与你的 `polymarket-5-min-weather` branch 完美对应
   - 共享事件源（NWS / NOAA），结算风险极低

### 5.3 长期

5. **构建通用跨平台预测市场套利框架**
   - 抽象 `IPredictionMarketClient` 接口
   - 实现 `PolymarketClient` / `KalshiClient` / `HyperliquidPredictionClient`
   - 统一事件匹配引擎（NLP 或规则匹配同一事件在不同平台的 ID）
   - 结算差异风控模块

---

## 六、关键风险总结

| 风险 | 严重度 | 缓解措施 |
|------|--------|---------|
| **结算分歧风险**：两平台对同一事件判定不同 | 🔴 高 | 只选结算源完全一致的事件；设置 dispute 期自动平仓 |
| **流动性风险**：预测市场深度不够 | 🟡 中 | 监控实时深度，仅在双边深度 > 阈值时入场 |
| **资金效率**：两平台各需预存 | 🟡 中 | 预存比例动态调整；考虑使用跨链桥自动化 |
| **执行时序**：两腿不能同时成交 | 🟡 中 | 先成交流动性差的一腿；第二腿用 IOC 单 |
| **合约定义微差**：四舍五入、时区、数据源 | 🟡 中 | 人工核实每个套利对的合约规格 |
| **监管风险**：跨平台自动化交易合规性 | 🟡 中 | 关注 Kalshi/Polymarket 各自的 TOS |
| **Hyperliquid 无原生预测市场** | 🔴 高 | 短期内 Hyperliquid 不是理想套利对手方 |

---

## 七、结论与建议

**对你最初的问题 "Hyperliquid 和 Polymarket 之间的二元市场对冲套利" 的判断：**

当前不可行作为纯结构性套利，因为 Hyperliquid 没有原生二元预测市场。但如果你的目的是利用 Hyperliquid 的 perps 来对冲 Polymarket 上加密价格类预测市场的方向性风险（delta-hedge），这在概念上是合理的——只是它不是"套利"，更接近"hedged directional bet"。

**更务实的跨平台套利路径**：
1. **Polymarket × Kalshi**（最成熟，已有开源参考）
2. **Polymarket 内部 Yes+No < $1 套利**（你已有架构）
3. **等待 HyperEVM 生态出现预测市场协议**（中长期）

---

## 附录：Hyperliquid API 快速参考

```python
# Python SDK: https://github.com/hyperliquid-dex/hyperliquid-python-sdk

from hyperliquid.info import Info
from hyperliquid.exchange import Exchange
from hyperliquid.utils import constants

info = Info(constants.MAINNET_API_URL)

# 获取所有市场
meta = info.meta()

# 获取 L2 订单簿
l2 = info.l2_snapshot("BTC")

# 下单
exchange = Exchange(wallet, constants.MAINNET_API_URL)
order_result = exchange.order("BTC", True, 0.01, 65000, {"limit": {"tif": "Gtc"}})
```

```java
// Java SDK (非官方，需自建):
// Hyperliquid 的 EIP-712 签名结构：
// Domain: { name: "Exchange", version: "1", chainId: 42161, verifyingContract: "0x..." }
// Types: Order { a: asset_index, b: is_buy, p: price, s: size, r: reduce_only, t: order_type, c: cloid }
```

---

<!-- kb-backlink -->
> 本文归属：[Polymarket 知识地图](../docs/MOC-Polymarket.md)
