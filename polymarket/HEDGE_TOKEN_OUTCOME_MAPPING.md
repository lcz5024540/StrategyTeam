# 对冲结算 Token–IndexSet–Outcome 映射层设计

> 目标：补齐「我手里的 ERC-1155 token 到底对应哪个 outcome，它是不是 Polymarket 判定的赢家」这条显式验证链路。结算门槛**不放宽**，继续要求 `meta.isClosed() && meta.isResolved()`。

---

## 1. 问题定位

当前 `HedgeSettlementMonitorJob.checkAndSettle` 拿到 `resolvedOutcome` 字符串（"Yes"/"No"）后直接调 `markSettling`，`HedgeClaimService` 再通过 `normalizeOutcomeSide` 把该字符串映射回 "YES"/"NO" 去找订单。整条链 **没有一次拿 winningTokenId 去和 `yes_token_id` / `no_token_id` 做等值比对**。这在下面几种场景会悄悄错：

1. Polymarket 把 `outcomes` 顺序列成 `["No","Yes"]`（非固定约定）
2. UMA 裁决后 Gamma 返回的 `resolvedOutcome` 大小写/空格异常（已用 `normalizeOutcomeLabel` 兜底，但没审计过所有路径）
3. 市场被 resolve 到一个既不是 "Yes" 也不是 "No" 的第三方标签（理论上 binary market 不会，但没断言保护）
4. 两边成交量不等（单边敞口），需精准区分赢方/亏方数量才能算 claim

## 2. 映射链条规范

```
market_id (我们业务里存的 marketId)
   ↓
conditionId  （Polymarket CTF 里的主键；实际上我们 buildPairs 时 marketId = conditionId）
   ↓
Gamma /markets?condition_ids=X → tokens[] { token_id, outcome, winner, price }
   ↓
{ yes_token_id, no_token_id } × outcomes  → 正反向映射
   ↓
winningTokenId == yes_token_id ? "YES" : "NO"
```

**显式不做的事**：
- 不直接访问 CTF 合约 `getPositionId(collectionId, collateralToken)` 来算 indexSet（1155 的 positionId），保持 Gamma-only
- 不直接查 UMA oracle，沿用你现有的 Gamma/Data API 链路

**indexSet** 我们**不存**。理由：
- Gamma tokens[] 的数组下标本身就能作为业务语义上的 index（token[0] ↔ indexSet=1, token[1] ↔ indexSet=2）
- 但"哪个 outcome 对应哪个下标"没有硬约定，真正可靠的是 `outcome` 字段本身
- 业务判赢只需要 `winner=true` 标志，引入 indexSet 反而增加一层可能出错的映射

## 3. 数据模型变更

### 3.1 `hedge_position` 新增两列

```sql
ALTER TABLE hedge_position
  ADD COLUMN winning_side       VARCHAR(8)  DEFAULT NULL AFTER final_outcome,
  ADD COLUMN winning_token_id   VARCHAR(80) DEFAULT NULL AFTER winning_side;

-- 方便按赢家方向过滤 Dashboard
CREATE INDEX idx_winning_side ON hedge_position(winning_side);
```

- `winning_side`：`YES` / `NO`。在 markSettling 时由 resolver 显式写入
- `winning_token_id`：审计字段，记录 Polymarket 判定为 winner 的那枚 1155 token id。未来出现 claim 不匹配时可以直接比对

### 3.2 `HedgePositionEntity` 新增字段

```java
/** 赢家方向（仅在 SETTLING/SETTLED 状态下有值），YES | NO */
private String winningSide;

/** 赢家 token id（来自 Gamma tokens[].winner=true 的那一枚），审计用 */
private String winningTokenId;
```

## 4. 新增服务：`HedgeMarketResolver`

承担"token ↔ outcome ↔ winner"的所有反查工作，让 `HedgeSettlementMonitorJob` / `HedgeClaimService` 职责更纯。

### 4.1 骨架

```java
package com.metaverse.coin.strategy.job.hedge.service;

import com.metaverse.coin.strategy.common.model.arbitrage.entity.HedgePositionEntity;
import com.metaverse.coin.strategy.job.smartwallet.service.PolymarketApiClient;
import com.metaverse.coin.strategy.job.smartwallet.service.PolymarketApiClient.MarketMetadata;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.Map;
import java.util.Optional;

/**
 * 对冲持仓结算映射解析器
 *
 * 唯一职责：把一个 HedgePositionEntity 映射到 Polymarket 的最终裁决结果，
 * 并返回确定的 winningSide / winningTokenId。
 *
 * 严格门槛：市场必须同时 closed=true 且 resolved 才返回结果，
 * 未到结算条件时返回 Optional.empty()。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class HedgeMarketResolver {

    private final PolymarketApiClient apiClient;

    /**
     * @param position SETTLING 或 OPEN 状态的对冲持仓
     * @return 如果市场已 resolved 且能确定赢家，返回 Resolution；否则 empty
     */
    public Optional<Resolution> resolvePosition(HedgePositionEntity position) {
        MarketMetadata meta;
        try {
            meta = apiClient.fetchMarketMetadata(position.getMarketId());
        } catch (IllegalStateException e) {
            log.warn("[HedgeMarketResolver] Market metadata not found: positionId={} marketId={} err={}",
                    position.getId(), position.getMarketId(), e.getMessage());
            return Optional.empty();
        }

        // 严格门槛：保持原有语义，不放宽
        if (!meta.isClosed() || !meta.isResolved()) {
            return Optional.empty();
        }

        String resolvedOutcome = meta.getResolvedOutcome();
        if (!StringUtils.hasText(resolvedOutcome)) {
            return Optional.empty();
        }

        // 反查：outcome -> tokenId
        Map<String, String> outcomeToToken = meta.getOutcomeTokenIds();
        if (outcomeToToken == null || outcomeToToken.isEmpty()) {
            log.warn("[HedgeMarketResolver] outcomeTokenIds empty: positionId={} marketId={}",
                    position.getId(), position.getMarketId());
            return Optional.empty();
        }

        String winningTokenId = outcomeToToken.get(resolvedOutcome);
        if (!StringUtils.hasText(winningTokenId)) {
            // normalizeOutcomeLabel 大小写兜底
            for (Map.Entry<String, String> e : outcomeToToken.entrySet()) {
                if (resolvedOutcome.equalsIgnoreCase(e.getKey())) {
                    winningTokenId = e.getValue();
                    break;
                }
            }
        }
        if (!StringUtils.hasText(winningTokenId)) {
            log.error("[HedgeMarketResolver] CRITICAL: resolvedOutcome '{}' has no matching tokenId in market meta (positionId={}, keys={})",
                    resolvedOutcome, position.getId(), outcomeToToken.keySet());
            return Optional.empty();
        }

        // 把 winningTokenId 匹配到我们持仓的 YES/NO
        String winningSide;
        if (winningTokenId.equals(position.getYesTokenId())) {
            winningSide = "YES";
        } else if (winningTokenId.equals(position.getNoTokenId())) {
            winningSide = "NO";
        } else {
            // 严重：市场赢家 token 和我们持仓的任何一边都不匹配
            // 这说明要么我们下单时 token 记错了，要么市场结构变了（比如被替换为 NegRisk 新 condition）
            log.error("[HedgeMarketResolver] CRITICAL: winningTokenId {} matches neither YES({}) nor NO({}) of positionId={} marketId={} outcome={}",
                    winningTokenId, position.getYesTokenId(), position.getNoTokenId(),
                    position.getId(), position.getMarketId(), resolvedOutcome);
            return Optional.empty();
        }

        return Optional.of(new Resolution(resolvedOutcome, winningSide, winningTokenId));
    }

    /** 结算解析结果 */
    public record Resolution(String outcome, String winningSide, String winningTokenId) {
    }
}
```

## 5. 既有代码改动点

### 5.1 `HedgePositionTracker.markSettling` 增加两个参数

```java
/** 标记持仓进入结算中状态 */
public void markSettling(Long positionId, String finalOutcome,
                          String winningSide, String winningTokenId) {
    positionMapper.update(null, new LambdaUpdateWrapper<HedgePositionEntity>()
            .eq(HedgePositionEntity::getId, positionId)
            .set(HedgePositionEntity::getStatus, "SETTLING")
            .set(HedgePositionEntity::getFinalOutcome, finalOutcome)
            .set(HedgePositionEntity::getWinningSide, winningSide)
            .set(HedgePositionEntity::getWinningTokenId, winningTokenId)
            .set(HedgePositionEntity::getSettlingAt, new Date())
            .set(HedgePositionEntity::getUpdatedAt, new Date()));
}

/** 旧签名保留为 deprecated（或删除所有调用点后移除） */
@Deprecated
public void markSettling(Long positionId, String finalOutcome) {
    markSettling(positionId, finalOutcome, null, null);
}
```

### 5.2 `HedgeSettlementMonitorJob.checkAndSettle` 重构

```java
private final HedgeMarketResolver marketResolver;  // 构造器注入

private boolean checkAndSettle(HedgePositionEntity position) {
    Optional<HedgeMarketResolver.Resolution> maybeRes = marketResolver.resolvePosition(position);
    if (maybeRes.isEmpty()) {
        // 市场还没完全 resolved，或赢家 token 匹配失败（已打日志）。继续等待。
        // 超时告警：市场已过期 24h 仍未结算
        if (position.getMarketEndAt() != null
                && position.getMarketEndAt() > 0
                && System.currentTimeMillis() > position.getMarketEndAt() + 24L * 60 * 60 * 1000) {
            log.warn("[HedgeSettlement] Position id={} market={} expired 24h+ ago but still unresolved",
                    position.getId(), position.getMarketId());
        }
        return false;
    }
    HedgeMarketResolver.Resolution res = maybeRes.get();

    log.info("[HedgeSettlement] Position resolved: id={} mode={} market={} winningSide={} winningToken={} outcome={}",
            position.getId(), position.getMode(), position.getMarketId(),
            res.winningSide(), res.winningTokenId(), res.outcome());

    if ("OPEN".equals(position.getStatus())) {
        positionTracker.markSettling(position.getId(),
                res.outcome(), res.winningSide(), res.winningTokenId());
    }

    if ("PAPER".equalsIgnoreCase(position.getMode())) {
        // PAPER 模式按显式赢方数量算 claim，而不是盲信 position.quantity
        BigDecimal claimed = computePaperClaim(position, res.winningSide());
        BigDecimal totalCost = position.getTotalCostUsdc() == null
                ? BigDecimal.ZERO : position.getTotalCostUsdc();
        BigDecimal fee = totalCost.multiply(properties.getEstimatedFeeRate())
                .setScale(4, RoundingMode.HALF_UP);
        positionTracker.markSettled(position.getId(), claimed, fee);
    }
    return true;
}

/** PAPER 模式 claim = 赢方订单的 filledQty × $1；兜底用 position.quantity */
private BigDecimal computePaperClaim(HedgePositionEntity position, String winningSide) {
    return positionTracker.getOrdersByPositionId(position.getId()).stream()
            .filter(o -> winningSide.equals(o.getSide()))
            .filter(o -> "FILLED".equals(o.getStatus()))
            .map(o -> o.getFilledQuantity() != null ? o.getFilledQuantity() : BigDecimal.ZERO)
            .findFirst()
            .orElseGet(() -> position.getQuantity() == null ? BigDecimal.ZERO : position.getQuantity())
            .setScale(4, RoundingMode.HALF_UP);
}
```

### 5.3 `HedgeClaimService.resolveClaimableQuantity` 改用 `winningSide`

```java
private BigDecimal resolveClaimableQuantity(HedgePositionEntity position) {
    String winSide = StringUtils.hasText(position.getWinningSide())
            ? position.getWinningSide()
            : normalizeOutcomeSide(position.getFinalOutcome());  // 老数据兜底
    if (!StringUtils.hasText(winSide)) {
        return position.getQuantity();
    }
    List<HedgeOrderEntity> orders = positionTracker.getOrdersByPositionId(position.getId());
    BigDecimal winFilledQty = orders.stream()
            .filter(o -> winSide.equals(o.getSide()))
            .filter(o -> "FILLED".equals(o.getStatus()))
            .map(o -> o.getFilledQuantity() != null ? o.getFilledQuantity() : BigDecimal.ZERO)
            .findFirst().orElse(BigDecimal.ZERO);
    return winFilledQty.compareTo(BigDecimal.ZERO) > 0 ? winFilledQty : position.getQuantity();
}
```

保留 `normalizeOutcomeSide` 作为老数据 fallback（针对还没 backfill `winning_side` 的历史记录）。

### 5.4 `HedgePositionView` / Dashboard 可选暴露

```java
// HedgePositionView
private String winningSide;

// HedgeController.toView 里加上
.winningSide(e.getWinningSide())
```

## 6. `PolymarketApiClient` 的 endAt 修复（Patch A）

文件：`coin-strategy-job/.../smartwallet/service/PolymarketApiClient.java:391`

```java
// 旧
.endAt(epochMillis(market, "endDateISO", "endDate", "gameStartTime", "eventStartDate"))
// 新
.endAt(epochMillis(market, "endDateISO", "endDate"))
```

**影响面评估**：
- Hedge 侧 `buildPairs` 直接拿来写 `position.market_end_at`，修复后 `market_end_at` 真正反映市场结束时间
- Smart-wallet 侧用了 `getEndAt()` 20+ 处，大多数是判断 `endAt <= now` 即 expired。之前 start 时间被当 end 用会**提前**触发 expired 逻辑 —— 修复后这些位置会更正确（不会再把还在跑的市场误判为过期）
- 如果某段代码依赖"endAt 总有值"，现在可能拿到 null（Gamma 极少数返回完全缺 end 字段的市场），需要检查 npe 保护（`SmartWalletAccountingService` 已有 `metadata.getEndAt() != null` 判空）

## 7. 回填已有数据（可选）

如果现在已经有卡在 OPEN/SETTLING 的仓位，可以用下面 SQL 一次性 backfill `winning_side`（需要有 `hedge_order` 的成交数据才能推断）：

```sql
-- winning_side 从 final_outcome 反推（仅 final_outcome 非空时）
UPDATE hedge_position
SET winning_side = CASE
        WHEN UPPER(TRIM(final_outcome)) LIKE 'YES%' THEN 'YES'
        WHEN UPPER(TRIM(final_outcome)) LIKE 'NO%'  THEN 'NO'
        ELSE NULL
    END
WHERE final_outcome IS NOT NULL
  AND winning_side IS NULL;
```

`winning_token_id` 无法从历史数据回填（老数据没有这个字段），保持 NULL 即可。

## 8. 实施顺序

1`HedgePositionEntity` 加两个字段
2新增 `HedgeMarketResolver`
3`HedgePositionTracker.markSettling` 增加重载签名
4改 `HedgeSettlementMonitorJob.checkAndSettle`
5改 `HedgeClaimService.resolveClaimableQuantity`
6改 `PolymarketApiClient.parseMarketMetadata` 第 391 行（endAt 严格化）
7重新编译 + 重启 Job

## 9. 验证

- 任一新 PAPER 仓结算后，`hedge_position` 应能查到 `winning_side` + `winning_token_id` 都非空
- 构造一条错位数据（`yes_token_id` 故意写错）在 staging 跑：resolver 应打 `CRITICAL` 日志并让仓位留在 OPEN 等待人工介入，而不是乱 claim
- SQL 验证 `winning_token_id` 精确等于 Gamma 返回的 winner token，跟 `yes_token_id`/`no_token_id` 有且仅有一个相等
