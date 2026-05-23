---
title: "Polymarket 市场持仓集中度风控计划"
type: plan
status: active
tags: [polymarket, plan]
created: 2026-04-13
updated: 2026-04-25
---

# Polymarket 市场持仓集中度风控计划

## 背景与问题

跟单策略目前的风控链聚焦在：钱包评分、共识强度、EV 估算、滑点预测。
**缺失的维度**：某市场的 YES/NO token 被少数几个巨鲸高度集中持有时，巨鲸可以通过
"自买拉价 → 引诱跟单者入场 → 高位出货"实现控盘，导致我们策略系统性亏损。

### 典型控盘场景

| 场景 | 描述 | 风险 |
|------|------|------|
| **场景 A：鲸鱼自信号** | 信号来源钱包本身就是该市场前3持仓者之一 | 可能是出货前制造热度 |
| **场景 B：外部共谋** | 多个真实信号钱包，但都只是追入了被控盘市场 | 跟单时已处于高位 |
| **场景 C：渐进控仓** | 开仓后巨鲸持续买入，形成浮盈幻觉但难以退出 | 流动性被锁死 |

---

## 核心指标定义

### 1. Top-N 集中度 (CR_N)
```
CR_N = (前N名持仓量之和) / (所有观测持仓量之和)
```
- `CR_1 > 0.35`：单一鲸鱼控盘风险高
- `CR_3 > 0.60`：前三鲸鱼联合控盘风险高
- `CR_5 > 0.75`：市场流动性被少数人把持

### 2. 赫芬达尔-赫希曼指数 (HHI)
```
HHI = Σ(每个持仓者市场份额²) × 10000
```
- HHI < 1500：竞争性市场（安全）
- 1500 ≤ HHI < 2500：中度集中（谨慎）
- HHI ≥ 2500：高度集中（拒绝或缩仓）

### 3. 信号源是否为鲸鱼 (isWhaleSignalSource)
```
触发信号的钱包 ∈ 当前市场 Top-5 持仓者 → true
```
- 鲸鱼自己触发信号 → 出货风险极高

---

## 实施计划（优先级排序）

---

### P0：核心检测门控（必须实现，阻断高风险信号）

#### P0-1：PolymarketApiClient 新增 `fetchMarketTopPositions()`
**文件**：`PolymarketApiClient.java`

调用 Polymarket Data API `/v1/market-positions?market={conditionId}` 获取市场持仓分布。

```
GET {dataApiBaseUrl}/v1/market-positions?market={conditionId}&status=OPEN&sortBy=TOKENS&sortDirection=DESC&limit=50
```

返回模型 `MarketPositionEntry`：
```java
@Data @Builder
public static class MarketPositionEntry {
    private String walletAddress;  // user
    private String outcome;        // "YES" / "NO"
    private BigDecimal size;       // token 数量
    private BigDecimal avgPrice;   // 平均成本
    private BigDecimal initialValue; // 初始 USDC 成本
}
```

**注意**：Polymarket 当前接口里 `/positions` 要求必须传 `user`；市场维度持仓分布需改用 `/v1/market-positions`。

---

#### P0-2：新建 `MarketConcentrationService`
**文件**：`service/MarketConcentrationService.java`

职责：
1. 调用 `fetchMarketTopPositions()` 获取持仓分布
2. 计算 CR_1 / CR_3 / CR_5 / HHI
3. 结果缓存 60s（每市场独立 key，复用 ConcurrentHashMap 模式）
4. 暴露 `analyze(conditionId, outcome)` → `ConcentrationSnapshot`

```java
public record ConcentrationSnapshot(
    String conditionId,
    String outcome,           // "YES" / "NO" / "ALL"（合并双向）
    int observedHolders,      // 观测到的持仓人数
    BigDecimal top1Pct,       // CR_1
    BigDecimal top3Pct,       // CR_3
    BigDecimal top5Pct,       // CR_5
    int hhi,                  // HHI 整数值
    boolean isHighlyConcentrated, // 是否触发阈值
    List<String> topHolderAddresses, // 前5持仓者地址（用于鲸鱼身份检测）
    long capturedAt           // 数据采集时间戳
) {}
```

缓存 key：`conditionId + ":" + outcome`，TTL 60s。

---

#### P0-3：`SmartWalletSignalRiskService` 接入集中度门控
**文件**：`SmartWalletSignalRiskService.java`

在共识门控之后、EV门控之前，新增集中度检查块：

```java
// 集中度门控（防控盘）
if (risk.isEnableConcentrationGate()) {
    ConcentrationSnapshot snap = concentrationService.analyze(
            event.getMarketId(), event.getOutcome());
    
    // Gate 1: 市场集中度过高 → 直接拒绝
    if (snap.isHighlyConcentrated()) {
        accepted = false;
        reasons.add("[CONCENTRATION] Market is highly concentrated. " +
            "CR1=" + snap.top1Pct() + " CR3=" + snap.top3Pct() +
            " HHI=" + snap.hhi());
    }
    
    // Gate 2: 信号来源钱包本身是鲸鱼 → 出货嫌疑
    if (risk.isBlockWhaleSignalSource() &&
            snap.topHolderAddresses().contains(event.getWalletAddress())) {
        accepted = false;
        reasons.add("[WHALE_SIGNAL] Signal source wallet is a top-5 holder in this market. " +
            "Possible pump-and-dump.");
    }
}
```

---

#### P0-4：`SmartWalletProperties.SignalRisk` 新增集中度配置
**文件**：`SmartWalletProperties.java`

```java
// ── 集中度门控 ──────────────────────────────────────────────────
/** 是否开启持仓集中度门控 */
private boolean enableConcentrationGate = false;

/** CR_1 上限：单一持仓者占比超过此值则拒绝信号（默认 40%） */
private BigDecimal maxTop1ConcentrationPct = new BigDecimal("0.40");

/** CR_3 上限：前三持仓者合计占比超过此值则拒绝信号（默认 65%） */
private BigDecimal maxTop3ConcentrationPct = new BigDecimal("0.65");

/** HHI 上限：超过则判定为高度集中（默认 2500） */
private int maxHhi = 2500;

/** 是否拦截"信号源钱包本身是鲸鱼"的信号 */
private boolean blockWhaleSignalSource = true;

/** 集中度数据缓存有效期（ms） */
private int concentrationCacheTtlMs = 60000;

/** 集中度分析拉取的最大持仓人数（Polymarket limit 上限 100） */
private int concentrationTopN = 50;
```

---

### P1：精细化风险响应（降低误杀，差异化处理）

#### P1-1：集中度加权仓位缩减
**文件**：`SmartWalletSignalRiskService.java`

当集中度"达到警戒但未到拒绝阈值"时，不是直接拒绝，而是对 recommendedStake 打折：

```
concentrationPenalty = max(0, CR_3 - safeThreshold) / (blockThreshold - safeThreshold)
adjustedStake = recommendedStake × (1 - concentrationPenalty × maxPenaltyRate)
```

配置项（新增到 SignalRisk）：
```yaml
concentration-stake-penalty-max-rate: 0.50   # 最多缩减 50%
concentration-stake-safe-top3-pct: 0.40      # CR_3 低于此值不惩罚
```

---

#### P1-2：鲸鱼信号降级而非硬拒绝（可配置）
**文件**：`SmartWalletProperties.SignalRisk`

新增 `whaleSignalSourceMode`：
- `BLOCK`：直接拒绝（默认）
- `DOWNSIZE`：仓位缩至 20%
- `WARN_ONLY`：记录日志但不影响决策

---

### P2：监控与可观测性

#### P2-1：Dashboard API 端点
**文件**：`SmartWalletController.java`

```
GET /api/smart-wallet/concentration/{conditionId}?outcome=YES
```

返回 `ConcentrationSnapshot` JSON，可在 Swagger UI 手动查询任意市场集中度。

#### P2-2：开仓后持续监控（后台扫描 Job）
**文件**：新建 `MarketConcentrationMonitorJob.java`

`@Scheduled(fixedDelayString = "...")` 定期扫描所有 OPEN 状态持仓：
- 若持仓对应市场的集中度在开仓后急剧上升（CR_3 上涨 > 20pp）→ 告警
- 触发条件：`currentCR3 - openCR3 > 0.20`（需要在开仓时记录快照值）
- 告警方式：`log.warn("[CONCENTRATION_ALERT]")` + 记录到 `smart_wallet_position` 的 `riskNote` 字段

---

## 实施顺序

```
P0-4（Config）→ P0-1（API）→ P0-2（Service）→ P0-3（Risk Gate）
→ P1-1（Penalty）→ P1-2（Mode）
→ P2-1（API）→ P2-2（Monitor Job）
```

---

## 配置示例（application.yml）

```yaml
smart-wallet:
  signal-risk:
    # 集中度门控（默认关闭，验证 API 稳定后再开启）
    enable-concentration-gate: false
    max-top1-concentration-pct: 0.40
    max-top3-concentration-pct: 0.65
    max-hhi: 2500
    block-whale-signal-source: true
    concentration-cache-ttl-ms: 60000
    concentration-top-n: 50
    concentration-stake-penalty-max-rate: 0.50
    concentration-stake-safe-top3-pct: 0.40
    whale-signal-source-mode: BLOCK
```

---

## 风险与权衡

| 风险 | 缓解措施 |
|------|----------|
| Polymarket `/positions` 无用户参数时是否返回全量数据？ | 默认 `enableConcentrationGate=false`，先验证 API 响应格式再开启 |
| 集中度快照过期导致误判 | 60s TTL + `capturedAt` 透出给 API 调用方 |
| 正常大市场也可能 CR_3 较高（机构参与） | HHI 双重验证；可按市场 volume 动态调整阈值 |
| 额外 API 调用增加延迟 | 缓存 60s；异常时降级跳过检查（fail-open） |

---

<!-- kb-backlink -->
> 本文归属：[Polymarket 知识地图](../docs/MOC-Polymarket.md) ｜ [知识库首页](../docs/知识库首页.md)
