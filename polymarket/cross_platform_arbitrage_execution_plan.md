---
title: "跨平台预测市场套利 · 机会验证优先实施路径"
type: plan
status: active
tags: [cross-platform, arbitrage, execution-plan, polymarket, sxbet]
created: 2026-06-24
updated: 2026-06-24
---

# 跨平台预测市场套利 · 机会验证优先实施路径

> **核心原则**：先赚南后驱马。不预设系统架构，先用最小代码量验证"钱在不在那里"，
> 有数据支撑后再投入工程。每一步的产出都是下一步的 Go/No-Go 门。

---

## 全局路线图

```
Phase 0 ─── 数据采集验证（3天）
   │         "两个平台上是否真的有相同事件？报价差多少？"
   │
   ├── GO: 存在 ≥5% spread 且频率 ≥ 每日3次 ───┐
   │                                              ▼
Phase 1 ─── 手动套利验证（1周）
   │         "用真实小额资金走通双腿下单 → 等结算 → 确认利润到账"
   │
   ├── GO: 单次 round-trip 实际净利 > 0 ───────┐
   │                                              ▼
Phase 2 ─── 半自动化（2周）
   │         "监控 + 告警 + 一键执行"
   │
   ├── GO: 2周正收益、无结算事故 ────────────────┐
   │                                              ▼
Phase 3 ─── 全自动化（3-4周）
             "检测 → 风控 → 双腿执行 → 结算 · 全闭环"
```

---

## Phase 0：数据采集验证（3天）

### 目标
用脚本同时拉取 Polymarket 和候选平台的活跃市场，人工 + 脚本配合找出**重叠事件**，记录实时报价差。

### 产出物
一张 CSV/JSON 表：`event_name | poly_yes | poly_no | counter_yes | counter_no | spread | timestamp`

### 0.1 Polymarket 侧（已有能力）

你已经有 `py_clob_client` 和 Gamma API 的完整接入。直接复用：

```python
# fetch_polymarket_markets.py
# 用 Gamma API 拉取所有活跃市场及实时价格

import httpx

GAMMA_API = "https://gamma-api.polymarket.com"

def fetch_active_markets():
    """拉取所有活跃且有流动性的市场"""
    resp = httpx.get(f"{GAMMA_API}/markets", params={
        "closed": "false",
        "active": "true",
        "limit": 500,
    })
    markets = resp.json()
    # 过滤有足够流动性的
    return [m for m in markets if float(m.get("volume", 0)) > 10000]

def fetch_orderbook(token_id: str):
    """拉取某个 token 的订单簿"""
    resp = httpx.get(f"https://clob.polymarket.com/book", params={
        "token_id": token_id,
    })
    return resp.json()
```

### 0.2 SX Bet 侧（新建，第一优先）

SX Bet 有公开 REST API，无需认证即可读取市场数据：

```python
# fetch_sxbet_markets.py

import httpx

SX_API = "https://api.sx.bet"

def fetch_active_markets():
    """拉取 SX Bet 所有活跃市场"""
    resp = httpx.get(f"{SX_API}/markets/active")
    return resp.json()

def fetch_market_odds(market_hash: str):
    """获取特定市场的最优报价"""
    resp = httpx.get(f"{SX_API}/orders/active", params={
        "marketHash": market_hash,
    })
    orders = resp.json()
    # 解析出 best bid/ask for both sides
    return parse_best_quotes(orders)
```

### 0.3 Overtime Markets 侧（第二优先）

Overtime 的市场数据通过 Subgraph 查询：

```python
# fetch_overtime_markets.py

import httpx

OVERTIME_SUBGRAPH = "https://api.thegraph.com/subgraphs/name/thales-markets/thales-markets-optimism"

def fetch_crypto_binary_markets():
    """查询加密价格类二元市场"""
    query = '''
    {
      sportMarkets(
        where: { isOpen: true, isPaused: false }
        orderBy: maturityDate
        first: 100
      ) {
        id
        gameId
        homeTeam
        awayTeam
        homeOdds
        awayOdds
        maturityDate
        tags
      }
    }
    '''
    resp = httpx.post(OVERTIME_SUBGRAPH, json={"query": query})
    return resp.json()["data"]["sportMarkets"]
```

### 0.4 事件匹配（人工 + 脚本辅助）

Phase 0 不需要完美的 NLP 匹配。用关键词 + 人工确认：

```python
# match_events.py

def find_overlapping_events(poly_markets, sx_markets):
    """
    粗粒度关键词匹配，Phase 0 用人工确认
    重点关注：
    1. 体育：队名 + 日期
    2. 政治：候选人名 + 事件类型
    3. 加密：币种 + 价格阈值 + 日期
    """
    candidates = []
    for pm in poly_markets:
        pm_keywords = extract_keywords(pm["question"])
        for sx in sx_markets:
            sx_keywords = extract_keywords(sx["title"])
            overlap = pm_keywords & sx_keywords
            if len(overlap) >= 2:  # 至少2个关键词匹配
                candidates.append({
                    "poly": pm,
                    "sx": sx,
                    "keywords": overlap,
                    "needs_manual_confirm": True,
                })
    return candidates

def extract_keywords(text: str) -> set:
    """提取事件关键词：队名、人名、日期、数字"""
    # 简单实现：分词 + 过滤停用词
    import re
    words = re.findall(r'\b[A-Z][a-z]+\b|\b\d+\b', text)
    return set(w.lower() for w in words if len(w) > 2)
```

### 0.5 Spread 记录器

```python
# record_spreads.py
# 每 30 秒运行一次，记录所有匹配对的 spread

import csv
import time
from datetime import datetime

def record_spread(match, poly_book, counter_book, output_file="spreads.csv"):
    """
    核心套利计算：
    spread_1 = 1.0 - (poly_best_ask_yes + counter_best_ask_no)
    spread_2 = 1.0 - (poly_best_ask_no + counter_best_ask_yes)
    best_spread = max(spread_1, spread_2)
    """
    poly_ask_yes = poly_book["best_ask_yes"]
    poly_ask_no = poly_book["best_ask_no"]
    counter_ask_yes = counter_book["best_ask_yes"]
    counter_ask_no = counter_book["best_ask_no"]

    spread_1 = 1.0 - (poly_ask_yes + counter_ask_no)
    spread_2 = 1.0 - (poly_ask_no + counter_ask_yes)
    best_spread = max(spread_1, spread_2)
    direction = "POLY_YES+COUNTER_NO" if spread_1 > spread_2 else "POLY_NO+COUNTER_YES"

    row = {
        "timestamp": datetime.utcnow().isoformat(),
        "event": match["poly"]["question"][:80],
        "poly_yes": poly_ask_yes,
        "poly_no": poly_ask_no,
        "counter_yes": counter_ask_yes,
        "counter_no": counter_ask_no,
        "spread": round(best_spread, 4),
        "direction": direction,
        "counter_platform": match.get("platform", "sxbet"),
        "poly_depth_yes": poly_book.get("depth_yes", 0),
        "counter_depth": counter_book.get("depth", 0),
    }

    with open(output_file, "a", newline="") as f:
        writer = csv.DictWriter(f, fieldnames=row.keys())
        if f.tell() == 0:
            writer.writeheader()
        writer.writerow(row)

    return row
```

### Phase 0 的 Go/No-Go 判据

| 指标 | Go 条件 | No-Go 则 |
|------|---------|----------|
| 重叠事件数量 | ≥ 10 个可匹配对 | 换平台或放弃 |
| 最大 Spread | ≥ 5% (扣手续费前) | 利润空间不够 |
| Spread > 3% 的频率 | ≥ 每日 3 次 | 机会太稀疏 |
| 双边深度 | 单边 ≥ $500 | 无法执行有效规模 |
| 结算定义一致性 | ≥ 80% 的匹配对定义完全一致 | 结算风险太高 |

---

## Phase 1：手动套利验证（1周）

### 前提
Phase 0 数据已确认存在可套利机会。

### 目标
用 **真实小额资金**（$50-100/对）手动执行 3-5 次完整套利循环：下单 → 持有 → 结算 → 确认利润到账。

### 1.1 资金准备

```
预算分配（$500 起步）：
├── Polymarket: $300 USDC (Polygon)
│   └── 已有钱包，已有 API creds
├── SX Bet: $200 USDC (SX Network)
│   └── 新建钱包，桥接 USDC
└── 预留 gas：各链 $5-10 的原生代币
```

**操作步骤**：
1. SX Network 钱包创建（MetaMask 添加网络）
2. USDC 从 Polygon → SX Network（通过 SX Bridge）
3. 在 SX Bet 上手动下一单，确认流程通顺

### 1.2 手动执行流程

每次套利严格按以下步骤：

```
Step 1: 从 Phase 0 的 spread 监控中选择一个 spread > 5% 的事件对
Step 2: 人工确认两边的结算定义完全一致
        - 事件描述是否相同？
        - 结算时间是否相同？
        - 结算源/Oracle 是否相同？
        - 有无"边缘情况"定义差异？
Step 3: 计算下单参数
        - 每侧下单金额 = min($50, 单边深度的 10%)
        - 预估手续费（Polymarket taker ~2%, SX Bet taker ~2%）
        - 预估 net spread = gross spread - 双边手续费
        - 确认 net spread > 0，否则放弃
Step 4: 执行 Leg 1（流动性较差的一侧）
        - 记录：成交价、成交量、实际滑点
Step 5: 执行 Leg 2（流动性较好的一侧，通常 Polymarket）
        - 记录：成交价、成交量、实际滑点
Step 6: 确认双腿均已成交
        - 如果 Leg 2 未成交：评估是否取消 Leg 1 或接受敞口
Step 7: 等待事件结算
Step 8: 领取/结算
        - Polymarket: claimWinnings (已有自动化)
        - SX Bet: 手动或脚本 claim
Step 9: 记录结果
        - 投入总成本、回收金额、净利润、周期天数、年化收益率
```

### 1.3 记录模板

```markdown
## 套利执行记录 #001

**事件**: "Lakers win NBA Finals 2026"
**Polymarket condition**: 0x123...
**SX Bet market**: 0xabc...

**执行时间**: 2026-06-25 14:30 UTC
**结算时间**: 2026-06-28 (比赛结束后)

### 下单
| 腿 | 平台 | 方向 | 价格 | 数量 | 成本 | 实际滑点 |
|-----|------|------|------|------|------|---------|
| Leg 1 | SX Bet | YES | $0.52 | 100 | $52 | 0.3% |
| Leg 2 | Polymarket | NO | $0.44 | 100 | $44 | 0.1% |
| **合计** | | | | | **$96** | |

### 结算
- 事件结果: Lakers WIN
- Polymarket NO → 归零
- SX Bet YES → $1.00 × 100 = **$100**

### 收益
- 毛回收: $100
- 总成本: $96
- 手续费: ~$1.92 (poly) + ~$1.04 (sx) = $2.96
- **净利润: $100 - $96 - $2.96 = +$1.04**
- **净收益率: 1.08%**
- **年化 (3天周期): ~131%**

### 经验教训
- SX Bet 深度不够，100 份已经吃到第二档
- 结算比预期快 2 小时
```

### Phase 1 的 Go/No-Go 判据

| 指标 | Go 条件 | No-Go 则 |
|------|---------|----------|
| 完成的 round-trip 数 | ≥ 3 次 | 需要更多数据 |
| 净利润率 (扣所有费用后) | > 0 (每次) | 重新计算手续费模型 |
| 结算分歧 | 0 次 | 如果发生，立即停止 + 根因分析 |
| Leg 2 执行失败率 | < 20% | 需要优化执行时序 |
| 平均 round-trip 周期 | < 7 天 | 资金效率太低 |

---

## Phase 2：半自动化（2周）

### 前提
Phase 1 手动验证了 3+ 次正收益 round-trip。

### 目标
自动化**检测 + 告警**，人工确认后**一键执行**。

### 2.1 组件拆解

```
                    ┌─────────────────────────────────────┐
                    │   Spread Monitor (自动, 30s/次)     │
                    │   - 拉取双平台报价                   │
                    │   - 计算 spread                      │
                    │   - 深度检查                         │
                    └───────────────┬─────────────────────┘
                                    │ spread > threshold
                                    ▼
                    ┌─────────────────────────────────────┐
                    │   Alert Service (自动)              │
                    │   - Telegram/Bark 推送              │
                    │   - 展示：事件、spread、深度、方向   │
                    └───────────────┬─────────────────────┘
                                    │ 人工确认
                                    ▼
                    ┌─────────────────────────────────────┐
                    │   One-Click Executor (半自动)       │
                    │   - 人工确认后触发                   │
                    │   - 自动执行双腿下单                 │
                    │   - 自动监控成交状态                 │
                    └───────────────┬─────────────────────┘
                                    │ 双腿成交
                                    ▼
                    ┌─────────────────────────────────────┐
                    │   Position Tracker (自动)           │
                    │   - 跟踪持仓状态                    │
                    │   - 监控结算事件                    │
                    │   - 自动 claim (复用已有能力)       │
                    └─────────────────────────────────────┘
```

### 2.2 实现优先级

**先做什么**（复用已有代码）：
1. Polymarket 报价获取 — 已有 `py_clob_client`
2. Polymarket 下单 — 已有 `polymarket_clob_client.py`
3. Polymarket claim — 已有 `SmartWalletClaimService`

**需要新写**：
1. SX Bet 报价获取（REST GET，无签名）
2. SX Bet 下单（EIP-712 签名，复用 Poly 框架）
3. Spread 监控 + Alert 推送
4. 一键执行编排（先 Leg1 确认 → 再 Leg2）

### 2.3 关键代码：一键执行器

```python
# one_click_executor.py

class CrossPlatformExecutor:
    def __init__(self, poly_client, sx_client):
        self.poly = poly_client
        self.sx = sx_client

    def execute_arbitrage(self, opportunity):
        """
        opportunity = {
            "poly_token_id": "...",
            "poly_side": "NO",       # 买 Polymarket 的 NO
            "poly_price": 0.44,
            "poly_size": 100,
            "sx_market_hash": "...",
            "sx_side": "YES",        # 买 SX Bet 的 YES
            "sx_price": 0.52,
            "sx_size": 100,
        }
        """
        # === Leg 1: 流动性较差的一侧（通常 SX Bet）===
        print(f"[Leg 1] Placing SX Bet order: {opportunity['sx_side']} @ {opportunity['sx_price']}")
        leg1_result = self.sx.place_order(
            market_hash=opportunity["sx_market_hash"],
            side=opportunity["sx_side"],
            price=opportunity["sx_price"],
            size=opportunity["sx_size"],
            order_type="GTC",  # 或 FOK 如果要立即成交
        )

        if not leg1_result["success"]:
            return {"success": False, "reason": "Leg 1 failed", "details": leg1_result}

        # 等待 Leg 1 成交确认（最多等 30 秒）
        leg1_fill = self.sx.wait_for_fill(leg1_result["order_id"], timeout=30)
        if not leg1_fill["filled"]:
            # Leg 1 未成交，取消并退出
            self.sx.cancel_order(leg1_result["order_id"])
            return {"success": False, "reason": "Leg 1 not filled within timeout"}

        # === Leg 2: Polymarket（流动性通常更好）===
        print(f"[Leg 2] Placing Polymarket order: {opportunity['poly_side']} @ {opportunity['poly_price']}")
        leg2_result = self.poly.place_order(
            token_id=opportunity["poly_token_id"],
            side=opportunity["poly_side"],
            price=opportunity["poly_price"],
            size=opportunity["poly_size"],
        )

        if not leg2_result["success"]:
            # 严重问题：Leg 1 已成交但 Leg 2 失败
            # → 告警 + 人工介入
            self.alert_leg2_failure(opportunity, leg1_fill, leg2_result)
            return {"success": False, "reason": "Leg 2 failed - MANUAL INTERVENTION NEEDED"}

        return {
            "success": True,
            "leg1": leg1_fill,
            "leg2": leg2_result,
            "expected_profit": 1.0 - opportunity["poly_price"] - opportunity["sx_price"],
        }
```

### 2.4 告警模板

```
🔔 套利机会检测

事件: Lakers win NBA Finals
Spread: 5.2% (扣费后预估净利: 2.1%)
方向: 买 SX YES@$0.52 + 买 Poly NO@$0.43

深度: SX YES $2,340 | Poly NO $5,100
建议规模: $200/对 (预估利润 $4.20)

⏰ 有效期估计: ~5 分钟（spread 收窄中）

[确认执行] [忽略] [查看详情]
```

### Phase 2 的 Go/No-Go 判据

| 指标 | Go 条件 | No-Go 则 |
|------|---------|----------|
| 2周累计净利润 | > $0 | 策略需要调整 |
| 告警 → 执行成功率 | > 70% | 执行逻辑需优化 |
| Leg 2 失败导致的单边敞口 | 0 次 | 需要加强执行保障 |
| 结算分歧 | 0 次 | 停止并排查 |
| 每日平均机会数 | ≥ 1 | 不够的话扩展平台 |

---

## Phase 3：全自动化（3-4周）

### 前提
Phase 2 运行 2 周，累计正收益，无结算事故。

### 目标
完全自动闭环：检测 → 风控 → 执行 → 持仓 → 结算。

### 3.1 集成到 CoinStrategy 主系统

```
coin-strategy-job/
└── src/main/java/.../job/crossplatform/
    ├── CrossPlatformArbitrageScheduler.java     # 主调度（每 30s）
    ├── MarketMatchingService.java               # 事件匹配
    ├── SpreadMonitorService.java                # 报价 + spread 计算
    ├── CrossPlatformRiskService.java            # 风控（7层复用 + 跨平台特有）
    ├── CrossPlatformExecutionService.java       # 双腿执行协调
    ├── CrossPlatformPositionService.java        # 跨平台持仓跟踪
    └── CrossPlatformSettlementService.java      # 双平台结算同步

coin-strategy-common/common-client/
└── sxbet/
    ├── SxBetApiService.java                     # HTTP 客户端
    ├── SxBetSignatureService.java               # EIP-712 签名
    ├── SxBetOrderService.java                   # 下单/取消/查询
    ├── SxBetMarketDataService.java              # 市场数据
    └── model/                                   # DTO
```

### 3.2 风控层（复用 + 扩展）

在现有的 7 层风控基础上，新增跨平台特有的风控 Gate：

```java
/**
 * 跨平台套利风控 — 在现有 SmartWalletSignalRiskService 基础上扩展
 */
public class CrossPlatformRiskService {

    // 复用已有能力
    private final SmartWalletSignalRiskService baseRiskService;

    public RiskResult evaluate(CrossPlatformOpportunity opp) {
        List<String> rejectReasons = new ArrayList<>();

        // Gate 1: Spread 有效性（扣费后仍为正）
        BigDecimal netSpread = opp.getGrossSpread()
            .subtract(estimateTotalFees(opp));
        if (netSpread.compareTo(MIN_NET_SPREAD) <= 0) {
            rejectReasons.add("Net spread too thin: " + netSpread);
        }

        // Gate 2: 双边深度检查
        if (opp.getWeakerSideDepth().compareTo(MIN_DEPTH) < 0) {
            rejectReasons.add("Insufficient depth on weaker side");
        }

        // Gate 3: 结算定义一致性（必须为 EXACT 或 HIGH）
        if (opp.getMatchConfidence().ordinal() > MatchConfidence.HIGH.ordinal()) {
            rejectReasons.add("Settlement definition mismatch risk too high");
        }

        // Gate 4: 单平台资金集中度
        BigDecimal platformExposure = getExposureOnPlatform(opp.getCounterPlatform());
        if (platformExposure.compareTo(MAX_SINGLE_PLATFORM_EXPOSURE) > 0) {
            rejectReasons.add("Too much capital on " + opp.getCounterPlatform());
        }

        // Gate 5: 事件到期时间（太远的锁定成本太高）
        long daysToSettlement = opp.getEstimatedDaysToSettlement();
        if (daysToSettlement > MAX_DAYS_TO_SETTLEMENT) {
            rejectReasons.add("Settlement too far: " + daysToSettlement + " days");
        }

        // Gate 6: 同一事件已有持仓（避免重复建仓）
        if (hasExistingPosition(opp.getEventId())) {
            rejectReasons.add("Already holding position on this event");
        }

        // Gate 7: 全局仓位数量限制
        if (getOpenCrossPlatformPositions() >= MAX_CONCURRENT_POSITIONS) {
            rejectReasons.add("Max concurrent positions reached");
        }

        return rejectReasons.isEmpty()
            ? RiskResult.pass()
            : RiskResult.reject(rejectReasons.get(0), rejectReasons);
    }
}
```

### 3.3 配置参数

```yaml
cross-platform-arbitrage:
  enabled: true
  platforms:
    polymarket:
      weight: 0.6              # 资金分配权重
      max-exposure: 5000       # 单平台最大敞口
    sxbet:
      weight: 0.3
      max-exposure: 3000
    overtime:
      weight: 0.1
      max-exposure: 1000

  detection:
    scan-interval-ms: 30000    # 扫描频率
    min-net-spread: 0.02       # 最小净 spread (2%)
    min-depth-usd: 500         # 最小深度
    max-days-to-settlement: 30 # 最长结算等待

  execution:
    max-position-size: 500     # 单笔最大
    max-concurrent-positions: 15
    leg1-timeout-seconds: 30   # Leg 1 等待成交超时
    leg2-max-slippage: 0.03    # Leg 2 可接受滑点

  risk:
    max-single-platform-exposure: 5000
    daily-loss-limit: 100      # 日亏损熔断
    settlement-divergence-action: "ALERT_AND_PAUSE"
```

---

## 平台接入优先级与工作量估算

| 平台 | 接入难度 | 预计工作量 | 依赖 Phase 0 数据 |
|------|---------|-----------|------------------|
| **SX Bet** | 低（EIP-712 复用） | 3-5 天 | 需确认重叠事件数 |
| **Overtime** | 中（智能合约交互） | 5-7 天 | 需确认加密事件深度 |
| **Azuro** | 中（Subgraph + 合约） | 5-7 天 | 需确认体育事件匹配 |

---

## 快速启动 Checklist

### 今天就能做的事（Phase 0 Day 1）

- [ ] **SX Bet 账号创建**：MetaMask 添加 SX Network，连接 sx.bet，浏览市场
- [ ] **手动比对 5 个事件**：打开 Polymarket 和 SX Bet，找体育/政治事件对比价格
- [ ] **写 Phase 0 脚本**：复制上面的 `fetch_sxbet_markets.py`，先跑通 API 调用
- [ ] **Polymarket 活跃市场导出**：用你现有的 Gamma API 客户端导出当前 top 100 市场

### Day 2-3

- [ ] **自动化匹配**：跑 `match_events.py`，找出所有重叠对
- [ ] **Spread 记录**：跑 `record_spreads.py` 48 小时
- [ ] **Phase 0 数据分析**：统计 spread 分布、频率、深度

### Go/No-Go 决策点（Day 3 结束）

基于数据做判断：
- 如果 SX Bet 重叠事件 < 5 且 spread < 3% → 转向 Overtime 或 Azuro
- 如果 SX Bet 重叠事件 ≥ 10 且 spread ≥ 5% → 进入 Phase 1
- 如果所有平台都不满足条件 → 暂缓该方向，等市场成熟

---

## 关键假设与风险

| 假设 | 风险 | 缓解 |
|------|------|------|
| SX Bet API 稳定可用 | API 变更或限频 | 提前测试、加重试 |
| 两平台结算结果一致 | Oracle 分歧 | Phase 1 手动验证 |
| Spread 持续存在 | 被其他套利者消除 | 速度优势 + 多平台 |
| 资金可顺利桥接 | 桥故障/延迟 | 预存资金 + 多路桥 |
| 手续费模型准确 | 隐藏费用 | Phase 1 实际测量 |

---

## 长期演进（Phase 3 之后）

1. **增加平台**：Azuro（体育量大）→ 更多套利对
2. **做市策略**：不止被动套利，主动在价差市场挂单
3. **资金效率优化**：跨链自动 rebalance
4. **机器学习**：预测 spread 出现时机，抢在收窄前入场
5. **集成到 CoinStrategy 统一看板**：与跟单系统共享风控 + 报告

---

<!-- kb-backlink -->
> 本文归属：[Polymarket 知识地图](../docs/MOC-Polymarket.md)
