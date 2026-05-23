---
title: "`market_end_at` 数据链路诊断 + 修复方案"
type: troubleshooting
status: active
tags: [polymarket, troubleshooting]
created: 2026-05-18
updated: 2026-05-18
---

# `market_end_at` 数据链路诊断 + 修复方案

**触发问题**：`smart_wallet_market_info` 70,176 行中 53,614 行（76%）`market_end_at IS NULL`，2,098 行（3%）有未来值，14,464 行（21%）已过期。

**业务后果**：风控 Gate 6 拒绝率 47%（`G6_MARKET_EXPIRED` + `G6_MARKET_EXPIRED_AFTER_FORCE_REFRESH`），跟单信号大面积被拒。

---

## 一、完整链路（按时间顺序）

```
[1] WS 收到 trade_event
        │
        ▼
[2] SmartWalletFollowTaskService.recordTradeEvent → ensureMarketInfoForEvent
        │
        ▼
[3] resolveMarketEndAt(marketId, questionHint)   ← coin-strategy-job/.../SmartWalletFollowTaskService.java:2004
        │ try {
        │   metadata = marketMetadataService.getByConditionId(marketId, refresh=false, questionHint)
        │   return metadata == null ? null : metadata.getEndAt();
        │ } catch (Exception e) { return null; }
        ▼
[4] PolymarketMarketMetadataService.getByConditionId(refresh=false)
        │ → 走 cache / persisted / local
        │ → 仅当 (!refresh && hotPathExternalRefreshEnabled()) 才命中 Polymarket Gamma API
        ▼
[5] PolymarketApiClient.fetchMarketMetadata(conditionId)
        │ → endAt = epochMillis(market, "endDateISO", "endDate", "gameStartTime", "eventStartDate")
        │ → 多字段降级解析；如果四个字段都没有，endAt = null
        ▼
[6] SmartWalletMarketInfoService.getOrCreate(marketId, outcome, ..., marketEndAt)
        │ → 关键代码 (line 230)：
        │   if (marketEndAt != null && (existing.getMarketEndAt() == null || existing.getMarketEndAt() <= 0)) {
        │       existing.setMarketEndAt(marketEndAt);
        │       updated = true;
        │   }
        │ ⚠️ 仅在 existing 是 NULL/0 时更新 → 永不刷新过期值
        ▼
[7] 落库 smart_wallet_market_info.market_end_at
```

---

## 二、3 个 Bug 叠加的根因

### Bug A · `getOrCreate` 只在首次创建时写 endAt，后续永不刷新（line 230）

```java
// 现状（SmartWalletMarketInfoService.java line 220-237）
if (existing != null) {
    boolean updated = false;
    if (tokenId != null && existing.getTokenId() == null) { ... }
    if (indexSet != null && existing.getIndexSet() == null) { ... }
    
    // 👇 BUG：只在 existing.endAt == null 时才更新
    if (marketEndAt != null && (existing.getMarketEndAt() == null || existing.getMarketEndAt() <= 0)) {
        existing.setMarketEndAt(marketEndAt);
        updated = true;
    }
    ...
}
```

**后果**：
- 首次创建时 Polymarket 返回 null → DB 写 null
- 24 小时后 Polymarket 已更新了 endAt → 我们的逻辑不再尝试覆盖
- 即使 Polymarket 修正了 endAt，本地仍是错误的过期值

### Bug B · `resolveMarketEndAt` 用 `refresh=false`，永远从缓存读

```java
// SmartWalletFollowTaskService.java line 2004
private Long resolveMarketEndAt(String marketId, String questionHint) {
    PolymarketApiClient.MarketMetadata metadata = 
        marketMetadataService.getByConditionId(marketId, false, questionHint);  // 👈 refresh=false
    return metadata == null ? null : metadata.getEndAt();
}
```

**后果**：如果第一次拉 metadata 时 endAt 为 null（市场刚创建，Polymarket 还没设 endDate），后续无论刷新多少次都是 cache 命中、不重拉。

### Bug C · 既有的回填任务 `backfillMarketEndAt` 已经全部 deprecated 成 no-op

```java
// SmartWalletAccountingService.java line 4915-4983
private int backfillPositionMarketEndAt(int limit, boolean refreshCache) {
    log.debug("backfillPositionMarketEndAt is deprecated after market_info migration. Skipping.");
    return 0;
}
private int backfillOrderMarketEndAt(...) { 
    log.debug("... is deprecated after market_info migration. Skipping.");
    return 0;
}
private int backfillTaskMarketEndAt(...) { 
    log.debug("... is deprecated after market_info migration. Skipping.");
    return 0;
}
```

控制器 `SmartWalletAccountingController.SMART_WALLET_ACCOUNTING_BACKFILL_MARKET_END_AT` 路由到这些 no-op 方法——**触发它什么都不做**，只是日志一行 debug。这是一个"管线断在中间但 endpoint 还在"的典型脏代码。

**注释解释**：`marketEndAt is now on market_info table — this backfill is no longer needed since the migration already populated market_info from existing data.`

迁移当时把 `smart_wallet_position` 等表上的 `market_end_at` 列搬到了 `smart_wallet_market_info`，但**没有补建一个等价的 market_info 端的刷新机制**。这就是 76% NULL 的来源。

---

## 三、修复方案（按 ROI 排序）

### 🔴 Fix 1 · 最小代码改动（5 行，治本一半）

**修改 `SmartWalletMarketInfoService.getOrCreate` 第 230 行**：让 endAt 总是被覆盖（只要 incoming 是合法非零值）。

```java
// before
if (marketEndAt != null && (existing.getMarketEndAt() == null || existing.getMarketEndAt() <= 0)) {
    existing.setMarketEndAt(marketEndAt);
    updated = true;
}

// after — 接受最新 endAt 覆盖陈旧值
if (marketEndAt != null && marketEndAt > 0 
        && !marketEndAt.equals(existing.getMarketEndAt())) {
    existing.setMarketEndAt(marketEndAt);
    updated = true;
}
```

**配合修改 `SmartWalletFollowTaskService.resolveMarketEndAt`**：第一次拿到的 metadata endAt 为空时，强制刷新一次。

```java
// before
private Long resolveMarketEndAt(String marketId, String questionHint) {
    try {
        PolymarketApiClient.MarketMetadata metadata = 
            marketMetadataService.getByConditionId(marketId, false, questionHint);
        return metadata == null ? null : metadata.getEndAt();
    } catch (Exception e) { ... return null; }
}

// after — 缓存为空时强制刷新一次
private Long resolveMarketEndAt(String marketId, String questionHint) {
    try {
        PolymarketApiClient.MarketMetadata metadata = 
            marketMetadataService.getByConditionId(marketId, false, questionHint);
        if (metadata == null || metadata.getEndAt() == null || metadata.getEndAt() <= 0) {
            // 缓存里没有 endAt → 强制从 Polymarket Gamma 重拉一次
            metadata = marketMetadataService.getByConditionId(marketId, true, questionHint);
        }
        return metadata == null ? null : metadata.getEndAt();
    } catch (Exception e) {
        log.debug("...");
        return null;
    }
}
```

**预计效果**：新增 trade_event 命中的市场，endAt 即时刷新；存量错误 endAt 在被再次 access 时也会被覆盖。

### 🟡 Fix 2 · 新增定时刷新 Job（替代 deprecated 的 backfill）

新增一个 Spring `@Scheduled` 任务，每 10 分钟扫一批 market_info 行：

**目标行**：
- `market_lifecycle = 'ACTIVE'` AND (`market_end_at IS NULL` OR `market_end_at < UNIX_TIMESTAMP()*1000`)
- 限制单批 200 行，避免打爆 Polymarket Gamma API

**伪代码**：
```java
@Scheduled(fixedDelayString = "${smart-wallet.market-info-refresh.interval-ms:600000}")
public void refreshStaleMarketEndAt() {
    if (!enabled) return;
    long now = System.currentTimeMillis();
    List<SmartWalletMarketInfoEntity> stale = marketInfoMapper.selectList(
        new LambdaQueryWrapper<SmartWalletMarketInfoEntity>()
            .eq(SmartWalletMarketInfoEntity::getMarketLifecycle, "ACTIVE")
            .and(w -> w.isNull(...endAt).or().le(...endAt, now))
            .last("limit 200")
    );
    for (var info : stale) {
        try {
            var md = marketMetadataService.getByConditionId(info.getMarketId(), true, info.getQuestion());
            if (md != null && md.getEndAt() != null && md.getEndAt() > 0) {
                info.setMarketEndAt(md.getEndAt());
                marketInfoMapper.updateById(info);
            }
        } catch (Exception e) { ... }
    }
}
```

**预计效果**：存量 70k 行里 lifecycle=ACTIVE 的 15.7k 行会被持续刷新，半天到一天恢复正常。

### 🟢 Fix 3 · 修复已死的 `BACKFILL_MARKET_END_AT` 触发器（运营备份）

把 3 个 deprecated 方法改回真正干活的实现，目标对象从 position/order/task 改成 `smart_wallet_market_info` 表本身。

```java
private int backfillTaskMarketEndAt(int limit, boolean refreshCache) {
    // 改为：扫 market_info 表
    List<SmartWalletMarketInfoEntity> targets = marketInfoMapper.selectList(
        new LambdaQueryWrapper<SmartWalletMarketInfoEntity>()
            .eq(SmartWalletMarketInfoEntity::getMarketLifecycle, "ACTIVE")
            .and(w -> w.isNull(...endAt).or().eq(...endAt, 0L))
            .last("limit " + limit)
    );
    int updated = 0;
    for (var info : targets) {
        var md = marketMetadataService.getByConditionId(info.getMarketId(), refreshCache, info.getQuestion());
        if (md != null && md.getEndAt() != null && md.getEndAt() > 0) {
            info.setMarketEndAt(md.getEndAt());
            marketInfoMapper.updateById(info);
            updated++;
        }
    }
    return updated;
}
```

这样 web 触发 `SMART_WALLET_ACCOUNTING_BACKFILL_MARKET_END_AT` 一键回填 N 条，运营可控。

---

## 四、临时操作建议（不改代码也能做的）

### A. 一次性 SQL 修复（仅清掉已确定结束的市场）

```sql
-- 1) 把"已 close 且 endAt NULL"的标 lifecycle=RESOLVED，避免再被当成 ACTIVE
UPDATE smart_wallet_market_info
SET market_lifecycle = 'RESOLVED'
WHERE closed = 1 AND market_lifecycle = 'ACTIVE'
  AND (market_end_at IS NULL OR market_end_at <= 0);
-- 预计影响 ~50k 行 (closed=54427 中 lifecycle=ACTIVE 那部分)
```

### B. 短期收敛风控（让 G6 拒绝率降下来）

```yaml
# /opt/coinstrategy/smart-wallet/config/application-test.yml 增量
smart-wallet:
  signal-risk:
    # G6 数据缺失时，是否回退到"宽容"判定（建议 test PAPER 开启验证）
    # 注意：这个开关需要先在代码中加，这里仅作示意
    treat-null-end-at-as-active: true
```

但代码里目前没有这个开关。**改代码或加这个开关是同一个工作量**——既然要动，就把 Fix 1 一起做了。

---

## 五、行动清单（建议顺序）

| # | 动作 | 类型 | 工作量 |
|---|------|------|--------|
| 1 | 立即跑临时 SQL（把 closed=1 但 lifecycle=ACTIVE 的 ~50k 行标 RESOLVED） | DB | 1 分钟 |
| 2 | 改 `SmartWalletMarketInfoService.getOrCreate` line 230（Fix 1A） | 1 行代码 | 10 分钟 |
| 3 | 改 `SmartWalletFollowTaskService.resolveMarketEndAt`（Fix 1B） | 5 行代码 | 15 分钟 |
| 4 | 新增 `MarketInfoEndAtRefreshJob` @Scheduled（Fix 2） | 30 行代码 | 1 小时 |
| 5 | 修复 `backfillMarketEndAt` 死代码（Fix 3） | 改 3 个方法 | 30 分钟 |
| 6 | 编译 + 打包 + 部署到 test | CI | 20 分钟 |
| 7 | 验证：6 小时后看 `SUM(market_end_at > UNIX_TIMESTAMP()*1000)` 是否从 2098 上升 | 观察 | — |

**总工作量约半天**。Fix 1 是最低成本最高 ROI 的改动。

---

## 六、关键代码位置（备查）

- `coin-strategy-common/.../SmartWalletMarketInfoService.java` line 220-253（`getOrCreate`）
- `coin-strategy-job/.../SmartWalletFollowTaskService.java` line 1942-1957（`ensureMarketInfoForEvent`），line 2004-2013（`resolveMarketEndAt`）
- `coin-strategy-job/.../PolymarketMarketMetadataService.java` line 41-105（`getByConditionId` 缓存语义）
- `coin-strategy-job/.../PolymarketApiClient.java` line 984（`endAt` 字段解析降级链：endDateISO → endDate → gameStartTime → eventStartDate）
- `coin-strategy-job/.../SmartWalletAccountingService.java` line 4915-4983（**已 deprecated 的 backfill no-op 方法**）
- `coin-strategy-web/.../SmartWalletAccountingController.java`（暴露的 backfill HTTP endpoint，路由到上面已 dead 的方法）

---

<!-- kb-backlink -->
> 本文归属：[Polymarket 知识地图](../docs/MOC-Polymarket.md) ｜ [知识库首页](../docs/知识库首页.md)
