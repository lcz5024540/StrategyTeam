# HTX 做市 · 跟价重挂（方案 A）实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 增强现有做市策略——买腿贴 `bestBid`、δ 收窄到 0.3%、买腿价格漂移超阈值即撤旧单在新 `bestBid` 重挂——以提高真实成交频率，同时守住不裸卖/始终 maker/不亏损。

**Architecture:** 纯定价新增两个函数（`sellFromBuy`、`shouldReprice`）到 `MarketMakerMath`；`MarketMakerService.openNewCellIfEligible` 改用 `buyPrice=bestBid`、`sellPrice=sellFromBuy(...)`；`advanceRestingBuy` 在「仍挂着」分支加跟价重挂（撤单→查竞态→重下→`markMmReprice` 原地更新同 pairId）；配置加 `reprice-threshold-pct` 并收窄 δ/buy-ttl；启动校验 `δ≥fee`。全部改动 TDD，先测后码。

**Tech Stack:** Java 17、Spring Boot 2.7.18（`javax.annotation.PostConstruct`）、MyBatis-Plus、JUnit 5、Mockito、BigDecimal。

**关键环境（执行者必读）：**
- `export JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-17.jdk/Contents/Home`
- 从项目根 `/Users/lichangzheng.ray/Ray/ray-agent/docs/projects/CoinStrategy` 运行 `mvn`（**不用** `./mvnw`；`.mvn/settings.xml` 相对 cwd）。
- 单测命令模板（排除 Binance 调试类）：
  `mvn -q -pl coin-strategy-htx -am test -Dtest='<类#方法>' -Dtest='!BinanceFuturesMarketDataTest,!BinanceMarketDataDebug,!BinanceFuturesDebug,!BinanceSpotDebug' -DfailIfNoTests=false`
- 纯 common 模块的测试用 `-pl coin-strategy-common/common-client -am`。
- git 仓库根即项目根，当前分支 `support_htx`。

**不变量（任何改动不得破坏）：** 先买后卖、无预置库存/无裸卖；买≤bestBid、卖≥bestAsk（始终 maker）；`(1−f)²·sell ≥ buy`；kill-switch 只停开新单；单笔/当日名义额闸门不变。

---

## File Structure

| 文件 | 责任 | 改动 |
|---|---|---|
| `coin-strategy-htx/.../strategy/MarketMakerMath.java` | 纯定价/闸门函数 | 新增 `sellFromBuy`、`shouldReprice`、常量 `TWO` |
| `coin-strategy-htx/.../config/HtxMarketMakerProperties.java` | 做市配置绑定 | 新增 `repriceThresholdPct`；改默认 `spreadPct`/`buyTtlMs` |
| `coin-strategy-htx/src/main/resources/application.yml` | 权威配置值 | `spread-pct`/`buy-ttl-ms` 改值 + 新键 |
| `coin-strategy-htx/.../persistence/HtxTradeRecorder.java` | 持久层写入 | 新增 `markMmReprice` |
| `coin-strategy-htx/.../strategy/MarketMakerService.java` | 开仓/状态机 | 改开仓定价 + `advanceRestingBuy` 跟价重挂 + `@PostConstruct` 校验 |

对应测试文件（均已存在，追加用例）：`MarketMakerMathTest`、`HtxMarketMakerPropertiesTest`、`HtxTradeRecorderMmTest`、`MarketMakerServiceOpenTest`、`MarketMakerServiceAdvanceTest`。

---

## Task 1: MarketMakerMath 新增 sellFromBuy / shouldReprice

**Files:**
- Modify: `coin-strategy-htx/src/main/java/com/metaverse/coin/strategy/htx/strategy/MarketMakerMath.java`
- Test: `coin-strategy-htx/src/test/java/com/metaverse/coin/strategy/htx/strategy/MarketMakerMathTest.java`

- [ ] **Step 1: 写失败测试** —— 在 `MarketMakerMathTest`（第 72 行 `}` 之前，最后一个方法后）追加：

```java
    @Test void sellFromBuy_is_buy_times_one_plus_two_spread_scale2() {
        // 60000 * (1 + 2*0.003) = 60000 * 1.006 = 60360.00
        assertEquals(0, new BigDecimal("60360.00")
                .compareTo(MarketMakerMath.sellFromBuy(new BigDecimal("60000.00"), new BigDecimal("0.003"))));
    }

    @Test void sellFromBuy_no_loss_holds_at_default_spread() {
        BigDecimal buy = new BigDecimal("60000.00");
        BigDecimal sell = MarketMakerMath.sellFromBuy(buy, new BigDecimal("0.003"));
        assertTrue(MarketMakerMath.isNoLoss(buy, sell, new BigDecimal("0.002")));
    }

    @Test void sellFromBuy_no_loss_breaks_when_spread_below_fee_floor() {
        BigDecimal buy = new BigDecimal("60000.00");
        BigDecimal sell = MarketMakerMath.sellFromBuy(buy, new BigDecimal("0.001")); // 2δ=0.002 < 2f 磨损
        assertFalse(MarketMakerMath.isNoLoss(buy, sell, new BigDecimal("0.002")));
    }

    @Test void shouldReprice_true_when_drift_at_or_above_threshold() {
        // |60000-59940|/60000 = 0.001 == 阈值 → true（含等号）
        assertTrue(MarketMakerMath.shouldReprice(
                new BigDecimal("60000"), new BigDecimal("59940"), new BigDecimal("0.001")));
        // 漂移更大也 true
        assertTrue(MarketMakerMath.shouldReprice(
                new BigDecimal("60000"), new BigDecimal("59700"), new BigDecimal("0.001")));
    }

    @Test void shouldReprice_false_when_drift_below_threshold() {
        // |60000-59950|/60000 = 0.000833 < 0.001 → false
        assertFalse(MarketMakerMath.shouldReprice(
                new BigDecimal("60000"), new BigDecimal("59950"), new BigDecimal("0.001")));
    }

    @Test void shouldReprice_true_when_bestbid_drops_below_current_buy() {
        // 反向漂移（bestBid 低于挂单价）也应触发：|59000-60000|/59000 大
        assertTrue(MarketMakerMath.shouldReprice(
                new BigDecimal("59000"), new BigDecimal("60000"), new BigDecimal("0.001")));
    }
```

- [ ] **Step 2: 跑测试确认失败**

Run: `mvn -q -pl coin-strategy-htx -am test -Dtest='MarketMakerMathTest' -DfailIfNoTests=false`
Expected: 编译失败 —— `cannot find symbol: method sellFromBuy` / `shouldReprice`。

- [ ] **Step 3: 实现** —— 编辑 `MarketMakerMath.java`。

在第 20 行 `private static final BigDecimal ONE = BigDecimal.ONE;` 之后加一行常量：

```java
    private static final BigDecimal TWO = BigDecimal.valueOf(2);
```

在 `sellPrice` 方法（第 30 行 `}` 之后）追加两个方法：

```java
    /**
     * 从买价推卖价：sellPrice = buyPrice·(1 + 2·δ)，含入到 PRICE_SCALE（HALF_UP）。
     * 买腿贴 bestBid 后，卖腿相对买价上抬 2δ 以维持总利差并满足不亏损。
     */
    public static BigDecimal sellFromBuy(BigDecimal buyPrice, BigDecimal spreadPct) {
        return buyPrice.multiply(ONE.add(TWO.multiply(spreadPct)))
                .setScale(PRICE_SCALE, RoundingMode.HALF_UP);
    }

    /**
     * 跟价重挂闸门：买价相对最优买价漂移 |bestBid − currentBuy| / bestBid ≥ thresholdPct 即需重挂。
     */
    public static boolean shouldReprice(BigDecimal bestBid, BigDecimal currentBuy, BigDecimal thresholdPct) {
        BigDecimal rel = bestBid.subtract(currentBuy).abs()
                .divide(bestBid, 10, RoundingMode.HALF_UP);
        return rel.compareTo(thresholdPct) >= 0;
    }
```

- [ ] **Step 4: 跑测试确认通过**

Run: `mvn -q -pl coin-strategy-htx -am test -Dtest='MarketMakerMathTest' -DfailIfNoTests=false`
Expected: PASS（含新增 6 个及原有全部）。

- [ ] **Step 5: 提交**

```bash
git add coin-strategy-htx/src/main/java/com/metaverse/coin/strategy/htx/strategy/MarketMakerMath.java \
        coin-strategy-htx/src/test/java/com/metaverse/coin/strategy/htx/strategy/MarketMakerMathTest.java
git commit -m "feat(hermes-bridge): MarketMakerMath.sellFromBuy + shouldReprice（跟价重挂纯函数）"
```

---

## Task 2: 配置新增 reprice-threshold-pct 并收窄默认 δ / buy-ttl

**Files:**
- Modify: `coin-strategy-htx/src/main/java/com/metaverse/coin/strategy/htx/config/HtxMarketMakerProperties.java`
- Test: `coin-strategy-htx/src/test/java/com/metaverse/coin/strategy/htx/config/HtxMarketMakerPropertiesTest.java`

- [ ] **Step 1: 改测试预期（先让它失败）** —— 编辑 `HtxMarketMakerPropertiesTest.java`。

把 `defaults_are_conservative()` 中这两行的期望值改掉，并加两行断言：

将第 27 行
```java
        assertEquals(0, new BigDecimal("0.005").compareTo(p.getSpreadPct()));
```
改为
```java
        assertEquals(0, new BigDecimal("0.003").compareTo(p.getSpreadPct()));
```

将第 32 行
```java
        assertEquals(3_600_000L, p.getBuyTtlMs());
```
改为
```java
        assertEquals(900_000L, p.getBuyTtlMs());
        assertEquals(0, new BigDecimal("0.001").compareTo(p.getRepriceThresholdPct()));
```

- [ ] **Step 2: 跑测试确认失败**

Run: `mvn -q -pl coin-strategy-htx -am test -Dtest='HtxMarketMakerPropertiesTest' -DfailIfNoTests=false`
Expected: 编译失败（`getRepriceThresholdPct` 不存在）或断言失败（默认值不匹配）。

- [ ] **Step 3: 实现** —— 编辑 `HtxMarketMakerProperties.java`。

将第 28 行
```java
    private BigDecimal spreadPct = new BigDecimal("0.005");
```
改为
```java
    private BigDecimal spreadPct = new BigDecimal("0.003");
```

将第 43 行
```java
    private long buyTtlMs = 3_600_000L;
```
改为
```java
    private long buyTtlMs = 900_000L;
```

在第 46 行 `private long sellTtlMs = 0L;` 之后追加：

```java

    /** 买价漂移超此相对比例（|bestBid−buyPrice|/bestBid）即撤旧买单、在新 bestBid 重挂（跟价）。 */
    private BigDecimal repriceThresholdPct = new BigDecimal("0.001");
```

- [ ] **Step 4: 跑测试确认通过**

Run: `mvn -q -pl coin-strategy-htx -am test -Dtest='HtxMarketMakerPropertiesTest' -DfailIfNoTests=false`
Expected: PASS。

- [ ] **Step 5: 提交**

```bash
git add coin-strategy-htx/src/main/java/com/metaverse/coin/strategy/htx/config/HtxMarketMakerProperties.java \
        coin-strategy-htx/src/test/java/com/metaverse/coin/strategy/htx/config/HtxMarketMakerPropertiesTest.java
git commit -m "feat(hermes-bridge): 做市配置加 reprice-threshold-pct，δ→0.3% / buy-ttl→15min"
```

---

## Task 3: application.yml 权威值更新

**Files:**
- Modify: `coin-strategy-htx/src/main/resources/application.yml:55-67`

- [ ] **Step 1: 编辑 yml** —— 将 `htx.market-maker` 段中三处改动 / 新增。

将
```yaml
    spread-pct: ${HTX_MM_SPREAD_PCT:0.005}              # δ=0.5%
```
改为
```yaml
    spread-pct: ${HTX_MM_SPREAD_PCT:0.003}              # δ=0.3%（买腿贴 bestBid，sell=buy·(1+2δ)）
```

将
```yaml
    buy-ttl-ms: ${HTX_MM_BUY_TTL_MS:3600000}           # 买单 1h 未成交则撤
```
改为
```yaml
    buy-ttl-ms: ${HTX_MM_BUY_TTL_MS:900000}            # 买单 15min 兜底 TTL（跟价为主）
    reprice-threshold-pct: ${HTX_MM_REPRICE_THRESHOLD_PCT:0.001}  # 买价漂移≥0.1%即撤旧单在新bestBid重挂
```

- [ ] **Step 2: 编译校验（yml 无独立单测，靠上下文加载）**

Run: `mvn -q -pl coin-strategy-htx -am test-compile -DfailIfNoTests=false`
Expected: BUILD SUCCESS。

- [ ] **Step 3: 提交**

```bash
git add coin-strategy-htx/src/main/resources/application.yml
git commit -m "feat(hermes-bridge): application.yml 做市 δ→0.3% / buy-ttl→15min / +reprice-threshold"
```

---

## Task 4: HtxTradeRecorder.markMmReprice（原地更新买单）

**Files:**
- Modify: `coin-strategy-htx/src/main/java/com/metaverse/coin/strategy/htx/persistence/HtxTradeRecorder.java`
- Test: `coin-strategy-htx/src/test/java/com/metaverse/coin/strategy/htx/persistence/HtxTradeRecorderMmTest.java`

- [ ] **Step 1: 写失败测试** —— 在 `HtxTradeRecorderMmTest`（第 107 行 `}` 结束的最后一个方法后、类右括号前）追加：

```java
    @Test void markMmReprice_updates_buy_order_price_and_resets_created_at_keeps_status_unset() {
        recorder.markMmReprice(100L, 888L, new BigDecimal("60000.00"), new BigDecimal("60360.00"));
        ArgumentCaptor<HtxLockedPairEntity> cap = ArgumentCaptor.forClass(HtxLockedPairEntity.class);
        verify(pairMapper).updateById(cap.capture());
        HtxLockedPairEntity e = cap.getValue();
        assertEquals(100L, e.getId());
        assertEquals(888L, e.getBuyOrderId());
        assertEquals(0, new BigDecimal("60000.00").compareTo(e.getBuyPrice()));
        assertEquals(0, new BigDecimal("60360.00").compareTo(e.getSellPrice()));
        assertNotNull(e.getCreatedAt());                 // 重置 TTL
        assertNull(e.getStatus());                       // 状态不改（updateById 只更非空字段，仍 MM_RESTING_BUY）
    }
```

- [ ] **Step 2: 跑测试确认失败**

Run: `mvn -q -pl coin-strategy-htx -am test -Dtest='HtxTradeRecorderMmTest' -DfailIfNoTests=false`
Expected: 编译失败 —— `cannot find symbol: method markMmReprice`。

- [ ] **Step 3: 实现** —— 编辑 `HtxTradeRecorder.java`，在 `markMmCancelledBuy` 方法（第 262 行 `}` 之后）追加：

```java

    /**
     * 跟价重挂：撤旧买单后原地换新买单（同 pairId，状态仍 MM_RESTING_BUY），
     * 更新 buyOrderId/buyPrice/sellPrice 并重置 createdAt 以重启 TTL（best-effort）。
     */
    public void markMmReprice(long pairId, Long newBuyOrderId, BigDecimal newBuyPrice, BigDecimal newSellPrice) {
        try {
            HtxLockedPairEntity e = new HtxLockedPairEntity();
            e.setId(pairId);
            e.setBuyOrderId(newBuyOrderId);
            e.setBuyPrice(newBuyPrice);
            e.setSellPrice(newSellPrice);
            e.setCreatedAt(LocalDateTime.now()); // 重置 TTL 起点
            e.setUpdatedAt(LocalDateTime.now());
            pairMapper.updateById(e);
        } catch (Exception ex) {
            log.error("markMmReprice failed pairId={}", pairId, ex);
        }
    }
```

（`LocalDateTime` 已在本文件 import，无需新增。）

- [ ] **Step 4: 跑测试确认通过**

Run: `mvn -q -pl coin-strategy-htx -am test -Dtest='HtxTradeRecorderMmTest' -DfailIfNoTests=false`
Expected: PASS（新增 1 个 + 原有全部）。

- [ ] **Step 5: 提交**

```bash
git add coin-strategy-htx/src/main/java/com/metaverse/coin/strategy/htx/persistence/HtxTradeRecorder.java \
        coin-strategy-htx/src/test/java/com/metaverse/coin/strategy/htx/persistence/HtxTradeRecorderMmTest.java
git commit -m "feat(hermes-bridge): HtxTradeRecorder.markMmReprice（原地换买单+重置TTL）"
```

---

## Task 5: MarketMakerService —— 贴价开仓 + 买腿跟价重挂 + 启动校验

**Files:**
- Modify: `coin-strategy-htx/src/main/java/com/metaverse/coin/strategy/htx/strategy/MarketMakerService.java`
- Test: `coin-strategy-htx/src/test/java/com/metaverse/coin/strategy/htx/strategy/MarketMakerServiceOpenTest.java`
- Test: `coin-strategy-htx/src/test/java/com/metaverse/coin/strategy/htx/strategy/MarketMakerServiceAdvanceTest.java`

### 5A：开仓定价改为贴 bestBid

- [ ] **Step 1: 修正受影响的开仓测试（先让它反映新定价）** —— 编辑 `MarketMakerServiceOpenTest.java`。

`min_gap_blocks_when_candidate_too_close_to_existing`（第 118–126 行）现假设候选买价≈59700；新定价下候选=bestBid=59990.00。把已挂买价改到贴近 59990 才能触发 min-gap。将第 122 行
```java
        existing.setBuyPrice(new BigDecimal("59700.10"));
```
改为
```java
        existing.setBuyPrice(new BigDecimal("59990.10")); // 贴近新候选 bestBid=59990.00，触发 min-gap
```

同时把该测试内注释（第 120 行）
```java
        // 候选 buyPrice≈59700；放一个极近的已挂 buy 使 farEnough=false
```
改为
```java
        // 候选 buyPrice=bestBid=59990.00；放一个极近的已挂 buy 使 farEnough=false
```

- [ ] **Step 2: 跑该测试确认失败**

Run: `mvn -q -pl coin-strategy-htx -am test -Dtest='MarketMakerServiceOpenTest#min_gap_blocks_when_candidate_too_close_to_existing' -DfailIfNoTests=false`
Expected: FAIL —— 现有代码候选买价≈59700，与 59990.10 相距 >0.3%，farEnough=true 未拦截，返回值非 -1。

- [ ] **Step 3: 实现贴价定价** —— 编辑 `MarketMakerService.java` 的 `openNewCellIfEligible`。

将第 109–111 行
```java
        BigDecimal p0 = bestBid.add(bestAsk).divide(TWO, 8, RoundingMode.HALF_UP);
        BigDecimal buyPrice = MarketMakerMath.buyPrice(p0, mmProps.getSpreadPct());
        BigDecimal sellPrice = MarketMakerMath.sellPrice(p0, mmProps.getSpreadPct());
```
替换为
```java
        BigDecimal buyPrice = bestBid.setScale(MarketMakerMath.PRICE_SCALE, RoundingMode.HALF_UP);
        BigDecimal sellPrice = MarketMakerMath.sellFromBuy(buyPrice, mmProps.getSpreadPct());
```

（`bestAsk` 的非空/正值校验（第 105–108 行）保留——depthGuard 仍用 asks；`TWO` 常量在第 44 行，见 Step 4 处理。）

- [ ] **Step 4: 移除不再使用的 TWO 常量** —— 删除 `MarketMakerService.java` 第 44 行：
```java
    private static final BigDecimal TWO = BigDecimal.valueOf(2);
```
（`p0` 移除后 `TWO` 无引用；`DEPTH_LEVELS` 常量保留。）

- [ ] **Step 5: 跑开仓测试确认通过**

Run: `mvn -q -pl coin-strategy-htx -am test -Dtest='MarketMakerServiceOpenTest' -DfailIfNoTests=false`
Expected: PASS（全部 12 个）。关键复核：`happy_path`（bid 59990→buy 59990.00、sell 60349.94、qty 0.0003）、`no_loss_gate`（buy 100.00/sell 100.20 → isNoLoss false 拦截）、`single/daily notional` 仍拦截。

- [ ] **Step 6: 提交**

```bash
git add coin-strategy-htx/src/main/java/com/metaverse/coin/strategy/htx/strategy/MarketMakerService.java \
        coin-strategy-htx/src/test/java/com/metaverse/coin/strategy/htx/strategy/MarketMakerServiceOpenTest.java
git commit -m "feat(hermes-bridge): 做市开仓改贴 bestBid，卖价用 sellFromBuy"
```

### 5B：买腿跟价重挂

- [ ] **Step 7: 写失败测试** —— 编辑 `MarketMakerServiceAdvanceTest.java`。

先在 `setup()` 里补 marketData mock。将第 29–38 行的 `setup()` 改为：

```java
    HtxApiService api;
    HtxSpotService spot;
    com.metaverse.coin.strategy.common.client.htx.rest.HtxMarketDataService md;
    HtxTradeProperties tradeProps;
    HtxMarketMakerProperties mmProps;
    HtxTradeRecorder recorder;
    MarketMakerService svc;

    @BeforeEach void setup() {
        api = mock(HtxApiService.class);
        spot = mock(HtxSpotService.class);
        md = mock(com.metaverse.coin.strategy.common.client.htx.rest.HtxMarketDataService.class);
        when(api.getSpotService()).thenReturn(spot);
        when(api.getMarketDataService()).thenReturn(md);
        tradeProps = new HtxTradeProperties();
        tradeProps.getApi().setSpotAccountId("acc-1");
        mmProps = new HtxMarketMakerProperties();
        recorder = mock(HtxTradeRecorder.class);
        svc = new MarketMakerService(api, mock(HtxDepthGuard.class), tradeProps, mmProps, recorder);
    }
```

> 说明：`md` 默认未 stub `depth(...)` → 返回 null → 现有 TTL/成交类用例的重挂检查被跳过，行为不变。仅下列新用例显式 stub `md.depth`。

在类末尾（最后一个方法 `resting_sell_external_cancel_records_exposure_and_done` 之后、类右括号前）追加一个 depth 构造辅助与三个重挂用例：

```java
    private HtxRestResponse<com.metaverse.coin.strategy.common.client.htx.model.HtxDepth> depthWithBid(String bid) {
        com.metaverse.coin.strategy.common.client.htx.model.HtxDepth d =
                new com.metaverse.coin.strategy.common.client.htx.model.HtxDepth();
        d.setBids(java.util.List.of(new com.metaverse.coin.strategy.common.client.htx.model.HtxPriceLevel(
                new BigDecimal(bid), new BigDecimal("10"))));
        d.setAsks(java.util.List.of(new com.metaverse.coin.strategy.common.client.htx.model.HtxPriceLevel(
                new BigDecimal(bid).add(new BigDecimal("20")), new BigDecimal("10"))));
        HtxRestResponse<com.metaverse.coin.strategy.common.client.htx.model.HtxDepth> r = new HtxRestResponse<>();
        r.setStatus("ok");
        r.setTick(d);
        return r;
    }

    @Test void resting_buy_drift_over_threshold_cancels_and_reprices_in_place() {
        mmProps.setBuyTtlMs(3_600_000L);
        mmProps.setRepriceThresholdPct(new BigDecimal("0.001"));
        mmProps.setSpreadPct(new BigDecimal("0.003"));
        // cell 买价 59700，bestBid 现 60000 → 漂移 0.5% ≥ 0.1% → 重挂
        when(spot.queryOrder(555L)).thenReturn(orderOk("submitted", "0", null, null))
                .thenReturn(orderOk("canceled", "0", null, null)); // 撤单后复查：无成交
        when(spot.cancelOrder(555L)).thenReturn(placeOk("555"));
        when(md.depth(eq("btcusdt"), anyInt())).thenReturn(depthWithBid("60000"));
        when(spot.placeOrder(eq("acc-1"), eq("btcusdt"), eq("buy-limit"), anyString(), eq("60000.00"), anyString()))
                .thenReturn(placeOk("999"));

        svc.advanceCell(buyCell(555L, LocalDateTime.now()));

        verify(spot).cancelOrder(555L);
        verify(spot).placeOrder(eq("acc-1"), eq("btcusdt"), eq("buy-limit"), anyString(), eq("60000.00"), anyString());
        // sellFromBuy(60000.00, 0.003) = 60360.00
        verify(recorder).markMmReprice(eq(100L), eq(999L), eq(new BigDecimal("60000.00")), eq(new BigDecimal("60360.00")));
        verify(recorder, never()).markMmCancelledBuy(anyLong());
    }

    @Test void resting_buy_drift_within_threshold_is_noop() {
        mmProps.setBuyTtlMs(3_600_000L);
        mmProps.setRepriceThresholdPct(new BigDecimal("0.001"));
        // cell 买价 59700，bestBid 59750 → 漂移 0.0837% < 0.1% → 不动
        when(spot.queryOrder(555L)).thenReturn(orderOk("submitted", "0", null, null));
        when(md.depth(eq("btcusdt"), anyInt())).thenReturn(depthWithBid("59750"));

        svc.advanceCell(buyCell(555L, LocalDateTime.now()));

        verify(spot, never()).cancelOrder(anyLong());
        verify(recorder, never()).markMmReprice(anyLong(), anyLong(), any(), any());
        verify(spot, never()).placeOrder(anyString(), anyString(), anyString(), anyString(), anyString(), anyString());
    }

    @Test void resting_buy_reprice_cancel_race_filled_opens_sell_instead() {
        mmProps.setBuyTtlMs(3_600_000L);
        mmProps.setRepriceThresholdPct(new BigDecimal("0.001"));
        // 触发重挂，但撤单复查发现已成交 → 转开卖腿，不重挂
        when(spot.queryOrder(555L)).thenReturn(orderOk("submitted", "0", null, null))
                .thenReturn(orderOk("canceled", "0.0003", "0.0000006", null)); // 撤单竞态：已成交
        when(spot.cancelOrder(555L)).thenReturn(placeOk("555"));
        when(md.depth(eq("btcusdt"), anyInt())).thenReturn(depthWithBid("60000"));
        when(spot.placeOrder(anyString(), anyString(), eq("sell-limit"), anyString(), anyString(), anyString()))
                .thenReturn(placeOk("777"));

        svc.advanceCell(buyCell(555L, LocalDateTime.now()));

        verify(spot).placeOrder(anyString(), anyString(), eq("sell-limit"), anyString(), anyString(), anyString());
        verify(recorder).markMmSellResting(eq(100L), eq(777L), any());
        verify(recorder, never()).markMmReprice(anyLong(), anyLong(), any(), any());
        verify(spot, never()).placeOrder(anyString(), anyString(), eq("buy-limit"), anyString(), anyString(), anyString());
    }
```

- [ ] **Step 8: 跑测试确认失败**

Run: `mvn -q -pl coin-strategy-htx -am test -Dtest='MarketMakerServiceAdvanceTest' -DfailIfNoTests=false`
Expected: 新增 3 个 FAIL（现无重挂逻辑：不会 cancel/place buy、不调用 markMmReprice）；原有用例仍 PASS。

- [ ] **Step 9: 实现跟价重挂** —— 编辑 `MarketMakerService.java`。

需要 `HtxDepth` import：确认文件顶部已有 `import com.metaverse.coin.strategy.common.client.htx.model.HtxDepth;`（第 4 行已存在）。

在 `advanceRestingBuy` 的「仍挂着」分支——即第 206 行注释
```java
        // 仍挂着（submitted/partial-filled）→ TTL 撤单
        if (isBuyExpired(cell)) {
```
之前——插入跟价重挂检查：

```java
        // 仍挂着（submitted/partial-filled）：先看是否需跟价重挂，再看 TTL
        BigDecimal bestBid = currentBestBid(cell.getSymbol());
        if (bestBid != null
                && MarketMakerMath.shouldReprice(bestBid, cell.getBuyPrice(), mmProps.getRepriceThresholdPct())) {
            repriceBuy(cell, bestBid);
            return;
        }
```

在 `advanceRestingBuy` 方法结束（第 223 行 `}`）之后、`openSellLeg` 之前，新增两个私有方法：

```java

    /** 取当前最优买价；深度不可用/异常返回 null（本 tick 不重挂）。 */
    private BigDecimal currentBestBid(String symbol) {
        HtxRestResponse<HtxDepth> depthResp = htxApiService.getMarketDataService().depth(symbol, DEPTH_LEVELS);
        if (depthResp == null || !depthResp.ok() || depthResp.payload() == null) {
            return null;
        }
        if (isEmpty(depthResp.payload().getBids())) {
            return null;
        }
        BigDecimal bid = depthResp.payload().getBids().get(0).getPrice();
        return (bid == null || bid.signum() <= 0) ? null : bid;
    }

    /**
     * 跟价重挂：撤旧买单→复查竞态（撤单期间成交则转卖腿）→在新 bestBid 重下买单→原地更新 cell。
     * 重挂失败（新单被拒）关闭 cell（此时无库存、无敞口）。
     */
    private void repriceBuy(MarketMakerCell cell, BigDecimal newBidRaw) {
        Long buyId = cell.getBuyOrderId();
        htxApiService.getSpotService().cancelOrder(buyId);
        HtxRestResponse<HtxOrder> post = htxApiService.getSpotService().queryOrder(buyId);
        BigDecimal postFilled = BigDecimal.ZERO;
        BigDecimal postFee = null;
        if (post != null && post.ok() && post.getData() != null) {
            postFilled = nz(post.getData().getFilledAmount());
            postFee = post.getData().getFilledFees();
        }
        if (postFilled.signum() > 0) {
            openSellLeg(cell, postFilled, postFee); // 撤单竞态成交 → 转卖腿，不再重挂
            return;
        }
        BigDecimal newBuy = newBidRaw.setScale(MarketMakerMath.PRICE_SCALE, RoundingMode.HALF_UP);
        BigDecimal newSell = MarketMakerMath.sellFromBuy(newBuy, mmProps.getSpreadPct());
        String buyClientId = cell.getClientId() + "-BUY-R" + System.currentTimeMillis();
        HtxRestResponse<String> resp = htxApiService.getSpotService().placeOrder(
                tradeProps.getApi().getSpotAccountId(), cell.getSymbol(), "buy-limit",
                cell.getQty().toPlainString(), newBuy.toPlainString(), buyClientId);
        Long newBuyId = parseOrderId(resp);
        if (newBuyId == null) {
            recorder.markMmCancelledBuy(cell.getPairId());
            log.warn("MM reprice buy rejected pairId={} newBuy={} err={}",
                    cell.getPairId(), newBuy, resp == null ? "null" : resp.getErrMsg());
            return;
        }
        recorder.markMmReprice(cell.getPairId(), newBuyId, newBuy, newSell);
        log.info("MM buy repriced pairId={} oldBuy={} newBuy={} newSell={} newBuyId={}",
                cell.getPairId(), cell.getBuyPrice(), newBuy, newSell, newBuyId);
    }
```

- [ ] **Step 10: 跑测试确认通过**

Run: `mvn -q -pl coin-strategy-htx -am test -Dtest='MarketMakerServiceAdvanceTest' -DfailIfNoTests=false`
Expected: PASS（原有 + 新增 3 个）。

- [ ] **Step 11: 提交**

```bash
git add coin-strategy-htx/src/main/java/com/metaverse/coin/strategy/htx/strategy/MarketMakerService.java \
        coin-strategy-htx/src/test/java/com/metaverse/coin/strategy/htx/strategy/MarketMakerServiceAdvanceTest.java
git commit -m "feat(hermes-bridge): 买腿跟价重挂（漂移超阈值撤旧单在新bestBid重挂，竞态成交转卖腿）"
```

### 5C：启动校验 δ ≥ fee

- [ ] **Step 12: 写失败测试** —— 在 `MarketMakerServiceOpenTest.java` 类末尾（最后一个方法后、类右括号前）追加：

```java
    @Test void validateConfig_throws_when_spread_below_fee() {
        mmProps.setSpreadPct(new BigDecimal("0.001"));   // δ<f=0.002 → 成交必亏
        mmProps.setFeeRatePerSide(new BigDecimal("0.002"));
        assertThrows(IllegalStateException.class, () -> svc.validateConfig());
    }

    @Test void validateConfig_ok_at_default_spread() {
        mmProps.setSpreadPct(new BigDecimal("0.003"));
        mmProps.setFeeRatePerSide(new BigDecimal("0.002"));
        assertDoesNotThrow(() -> svc.validateConfig());  // δ≥f → 放行
    }
```

- [ ] **Step 13: 跑测试确认失败**

Run: `mvn -q -pl coin-strategy-htx -am test -Dtest='MarketMakerServiceOpenTest#validateConfig_throws_when_spread_below_fee+MarketMakerServiceOpenTest#validateConfig_ok_at_default_spread' -DfailIfNoTests=false`
Expected: 编译失败 —— `cannot find symbol: method validateConfig`。

- [ ] **Step 14: 实现启动校验** —— 编辑 `MarketMakerService.java`。

在文件顶部 import 区加：
```java
import javax.annotation.PostConstruct;
```

在类字段声明之后（`DEPTH_LEVELS` 常量后、`runOnce()` 之前）新增：

```java
    /** 启动校验：δ < 单边费率则成交必亏，拒绝启动。见 spec §1 不亏下限。 */
    @PostConstruct
    void validateConfig() {
        if (mmProps.getSpreadPct().compareTo(mmProps.getFeeRatePerSide()) < 0) {
            throw new IllegalStateException("MM spread-pct (" + mmProps.getSpreadPct()
                    + ") must be >= fee-rate-per-side (" + mmProps.getFeeRatePerSide()
                    + "): filling would lose money");
        }
    }
```

- [ ] **Step 15: 跑测试确认通过**

Run: `mvn -q -pl coin-strategy-htx -am test -Dtest='MarketMakerServiceOpenTest' -DfailIfNoTests=false`
Expected: PASS（全部，含新增 2 个）。

- [ ] **Step 16: 提交**

```bash
git add coin-strategy-htx/src/main/java/com/metaverse/coin/strategy/htx/strategy/MarketMakerService.java \
        coin-strategy-htx/src/test/java/com/metaverse/coin/strategy/htx/strategy/MarketMakerServiceOpenTest.java
git commit -m "feat(hermes-bridge): 做市启动校验 δ≥fee（成交必亏则拒绝启动）"
```

---

## Task 6: 模块级全量回归

**Files:** 无（仅验证）

- [ ] **Step 1: 跑 htx 模块全部单测**

Run:
```bash
mvn -q -pl coin-strategy-htx -am test \
  -Dtest='!BinanceFuturesMarketDataTest,!BinanceMarketDataDebug,!BinanceFuturesDebug,!BinanceSpotDebug' \
  -DfailIfNoTests=false
```
Expected: BUILD SUCCESS，`MarketMakerMathTest`/`HtxMarketMakerPropertiesTest`/`HtxTradeRecorderMmTest`/`MarketMakerServiceOpenTest`/`MarketMakerServiceAdvanceTest`/`MarketMakerServiceRunTest`/`HtxMarketMakerSchedulerTest`/`HtxMarketMakerIntegrationTest`/`MarketMakerCellTest` 全绿。

- [ ] **Step 2: 跑 common depth 解析回归**

Run: `mvn -q -pl coin-strategy-common/common-client -am test -Dtest='HtxDepthParseTest' -DfailIfNoTests=false`
Expected: PASS（贴价定价依赖 depth 解析正确）。

- [ ] **Step 3: 完成分支** —— 调用 superpowers:finishing-a-development-branch，按其菜单决定合并/PR/保留。

---

## Self-Review

**1. Spec 覆盖：**
- §1 定价（buyPrice=bestBid、sellPrice=buyPrice·(1+2δ)、sellFromBuy、shouldReprice、启动校验 δ≥fee）→ Task 1 + Task 5A + Task 5C。✓
- §2 买腿跟价重挂（shouldReprice→cancel→竞态查→重下→markMmReprice、原地同 pairId、重置 createdAt、阈值 0.001）→ Task 5B + Task 4。✓
- §3 卖腿不变 → 无改动（未触碰 `advanceRestingSell`/`openSellLeg` 逻辑）。✓
- §4 配置（spread 0.003、reprice-threshold 0.001、buy-ttl 900000）→ Task 2 + Task 3。✓
- §5 持久层 markMmReprice → Task 4。✓
- §6 测试（Math/Advance/Recorder/回归）→ Task 1/4/5/6。✓
- §7 不变量回归 → Task 6。✓

**2. 占位符扫描：** 无 TBD/TODO；每个改码步骤均给出完整代码与精确行号锚点。✓

**3. 类型/签名一致性：**
- `sellFromBuy(BigDecimal, BigDecimal)`、`shouldReprice(BigDecimal, BigDecimal, BigDecimal)` —— Task 1 定义，Task 5 调用一致。✓
- `markMmReprice(long, Long, BigDecimal, BigDecimal)` —— Task 4 定义，Task 5B 调用参数序一致（pairId, newBuyId, newBuy, newSell）。✓
- `getRepriceThresholdPct()` —— Task 2 定义（Lombok `@Data` 生成），Task 5B 调用一致。✓
- `validateConfig()` 包级可见 —— Task 5C 定义，Step 12 测试直呼一致。✓
- 数值自洽：sellFromBuy(60000,0.003)=60360.00；shouldReprice 阈值边界（59940=0.001 含等号 true、59950 false）；isNoLoss 在 δ=0.003 通过 / δ=0.001 拒绝。✓

**一致性说明（重挂失败关闭 cell）：** `repriceBuy` 新单被拒时 `markMmCancelledBuy`——此刻旧单已撤、无成交、无库存、无敞口，关闭 cell 安全，符合 §7「无裸卖」。
