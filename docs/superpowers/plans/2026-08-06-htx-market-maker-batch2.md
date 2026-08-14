# HTX 做市 第二批（MarketMakerService）实现计划

> **For agentic workers:** 用 inline TDD 逐任务实现。步骤用 `- [ ]` 勾选跟踪。

**Goal:** 在已就绪的数据/纯逻辑/持久化层之上，实现做市服务 `MarketMakerService`：开仓决策（多闸门 fail-closed）+ 买→挂卖→完成状态机 + 重启恢复。

**Architecture:** 单个 Spring `@Service`，注入 `HtxApiService`（下单/查单/撤单 + 深度）、`HtxDepthGuard`、`HtxTradeProperties`（复用风控/账户配置）、`HtxMarketMakerProperties`（做市配置）、`HtxTradeRecorder`（DB 唯一入口）。非阻塞：`runOnce()` 每 tick 先推进所有 open cell，再按白名单尝试各开一个新 cell。买腿写前 fail-closed（`createMmCell` 抛异常即不发单）；买腿成交后卖腿失败 → 记 `htx_exposure` 交对账器市价平，关闭 cell 防重复挂卖。

**Tech Stack:** Java 17, Spring Boot 2.7.18, MyBatis-Plus, JUnit5 + Mockito。

**运行前置（每次跑测试）:**
```
export JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-17.jdk/Contents/Home
# 从项目根运行；-am 强制会连带构建 common-client/common-model
mvn -pl coin-strategy-htx test -Dtest='!BinanceFuturesMarketDataTest,!BinanceMarketDataDebug,!BinanceFuturesDebug,!BinanceSpotDebug' -DfailIfNoTests=false -q
```

---

## File Structure

- Create: `coin-strategy-htx/src/main/java/com/metaverse/coin/strategy/htx/strategy/MarketMakerService.java` — 做市服务（决策 + 状态机 + 恢复）。
- Test: `coin-strategy-htx/src/test/java/com/metaverse/coin/strategy/htx/strategy/MarketMakerServiceOpenTest.java` — 开仓闸门 + happy path。
- Test: `coin-strategy-htx/src/test/java/com/metaverse/coin/strategy/htx/strategy/MarketMakerServiceAdvanceTest.java` — 状态机推进。
- Test: `coin-strategy-htx/src/test/java/com/metaverse/coin/strategy/htx/strategy/MarketMakerServiceRunTest.java` — runOnce/recover 编排。

已就绪依赖（第一批，勿改）：`MarketMakerMath`（buyPrice/sellPrice/qtyFor/isNoLoss/farEnough，PRICE_SCALE=2/QTY_SCALE=4）、`MarketMakerCell.fromEntity`、`HtxTradeRecorder` 的 MM 方法（createMmCell/markMmBuySent/markMmSellResting/markMmDone/markMmCancelledBuy/findOpenMmCells/findAllOpenMmCells/countOpenMmCells + recordExposure/sumTodayLiveQuoteNotional）、`HtxStatus.MM_*`、`HtxFeeMath.sellableBase`、`HtxDepthGuard.check`。

关键外部签名：
- `htxApiService.getSpotService().placeOrder(accountId,symbol,type,amount,price,clientOrderId) → HtxRestResponse<String>`（data=订单id字符串，`Long.parseLong`）。
- `...queryOrder(long id) → HtxRestResponse<HtxOrder>`；`...cancelOrder(long id) → HtxRestResponse<String>`。
- `htxApiService.getMarketDataService().depth(symbol,int) → HtxRestResponse<HtxDepth>`；depth∈{1,5,10,20}；`payload()`=data或tick。
- `HtxDepth`: `getBids()`/`getAsks()`（best-first），元素 `HtxPriceLevel(price,amount)`。
- `HtxOrder`: state∈submitted/partial-filled/filled/partial-canceled/canceled/rejected；`getFilledAmount/getFilledFees/getAmount`。
- `HtxRestResponse.ok()`/`getData()`/`payload()`/`getErrMsg()`。
- `tradeProps.getApi().getSpotAccountId()`；`.getRisk().getMaxSingleQuoteNotional()`(50)/`.getMaxDailyQuoteNotional()`(100000)；`.getDepthGuard().getSafetyRatio()`(3.0)/`.getTopN()`(10)。

---

## Task D1: MarketMakerService 开仓决策 openNewCellIfEligible

**Files:**
- Create: `.../strategy/MarketMakerService.java`
- Test: `.../strategy/MarketMakerServiceOpenTest.java`

闸门顺序（任一不过返回 -1，不开仓）：白名单 → K 闸（`countOpenMmCells < maxConcurrentCellsPerSymbol`）→ 取深度/中间价 P0 →（buyPrice/sellPrice/qty）→ 不亏闸 → min-gap 去重 → 单笔名义闸 → 每日名义闸（读失败 fail-closed 跳过）→ 深度闸。全过 → `createMmCell`（fail-closed 写前）→ `placeOrder buy-limit` →（拒单 `markMmCancelledBuy`；成功 `markMmBuySent`）。

- [ ] **Step 1: 写实现 MarketMakerService.java（含 D2/D3 骨架方法签名，D1 只填 open 逻辑与私有 helper）**

```java
package com.metaverse.coin.strategy.htx.strategy;

import com.metaverse.coin.strategy.common.client.htx.HtxApiService;
import com.metaverse.coin.strategy.common.client.htx.model.HtxDepth;
import com.metaverse.coin.strategy.common.client.htx.model.HtxOrder;
import com.metaverse.coin.strategy.common.client.htx.model.HtxRestResponse;
import com.metaverse.coin.strategy.htx.config.HtxMarketMakerProperties;
import com.metaverse.coin.strategy.htx.persistence.HtxStatus;
import com.metaverse.coin.strategy.htx.persistence.HtxTradeRecorder;
import com.metaverse.coin.strategy.htx.persistence.entity.HtxLockedPairEntity;
import com.metaverse.coin.strategy.htx.service.HtxDepthGuard;
import com.metaverse.coin.strategy.htx.service.HtxFeeMath;
import com.metaverse.coin.strategy.htx.service.HtxTradeProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 做市服务：开仓决策 + 买→挂卖→完成状态机 + 重启恢复。见 spec 2026-08-06-htx-market-maker-design.md §3/§4/§6。
 * 非阻塞：runOnce 每 tick 先推进 open cell，再按白名单各尝试开一个新 cell。
 * 买腿写前 fail-closed（createMmCell 抛异常即未发单）；买腿成交后卖腿失败 → 记 exposure 交对账器平，关闭 cell 防重复挂卖。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MarketMakerService {

    private final HtxApiService htxApiService;
    private final HtxDepthGuard depthGuard;
    private final HtxTradeProperties tradeProps;
    private final HtxMarketMakerProperties mmProps;
    private final HtxTradeRecorder recorder;

    private static final int DEPTH_LEVELS = 5;
    private static final BigDecimal TWO = BigDecimal.valueOf(2);

    // ---- 编排（D3） ----

    /** 主循环一次：先推进所有 open cell，再（若未急停）按白名单各尝试开一个新 cell。 */
    public void runOnce() {
        for (MarketMakerCell cell : loadOpenCells()) {
            try {
                advanceCell(cell);
            } catch (RuntimeException ex) {
                log.error("MM advanceCell failed pairId={}", cell.getPairId(), ex);
            }
        }
        if (!mmProps.isEnabled()) {
            return; // 急停：只停开新，不停推进
        }
        for (String symbol : mmProps.getSymbols()) {
            try {
                openNewCellIfEligible(symbol);
            } catch (RuntimeException ex) {
                log.error("MM openNewCell failed symbol={}", symbol, ex);
            }
        }
    }

    /** 重启恢复：库里所有 open cell 拉回并对齐一次（幂等）。 */
    public void recoverOpenCells() {
        for (MarketMakerCell cell : loadOpenCells()) {
            try {
                advanceCell(cell);
            } catch (RuntimeException ex) {
                log.error("MM recover advanceCell failed pairId={}", cell.getPairId(), ex);
            }
        }
    }

    private List<MarketMakerCell> loadOpenCells() {
        return recorder.findAllOpenMmCells().stream()
                .map(MarketMakerCell::fromEntity)
                .collect(Collectors.toList());
    }

    // ---- 开仓（D1） ----

    /** 通过所有闸门则开一个新 cell 并发买单，返回 pairId；任一闸门不过返回 -1。 */
    public long openNewCellIfEligible(String symbol) {
        if (!mmProps.getSymbols().contains(symbol)) {
            return -1;
        }
        if (recorder.countOpenMmCells(symbol) >= mmProps.getMaxConcurrentCellsPerSymbol()) {
            return -1;
        }
        HtxRestResponse<HtxDepth> depthResp = htxApiService.getMarketDataService().depth(symbol, DEPTH_LEVELS);
        if (depthResp == null || !depthResp.ok() || depthResp.payload() == null) {
            return -1;
        }
        HtxDepth depth = depthResp.payload();
        if (isEmpty(depth.getBids()) || isEmpty(depth.getAsks())) {
            return -1;
        }
        BigDecimal bestBid = depth.getBids().get(0).getPrice();
        BigDecimal bestAsk = depth.getAsks().get(0).getPrice();
        if (bestBid == null || bestAsk == null || bestBid.signum() <= 0 || bestAsk.signum() <= 0) {
            return -1;
        }
        BigDecimal p0 = bestBid.add(bestAsk).divide(TWO, 8, RoundingMode.HALF_UP);
        BigDecimal buyPrice = MarketMakerMath.buyPrice(p0, mmProps.getSpreadPct());
        BigDecimal sellPrice = MarketMakerMath.sellPrice(p0, mmProps.getSpreadPct());
        BigDecimal qty = MarketMakerMath.qtyFor(mmProps.getPerCellQuoteNotional(), buyPrice);
        if (qty.signum() <= 0) {
            return -1;
        }
        if (!MarketMakerMath.isNoLoss(buyPrice, sellPrice, mmProps.getFeeRatePerSide())) {
            log.warn("MM no-loss gate blocked symbol={} buy={} sell={}", symbol, buyPrice, sellPrice);
            return -1;
        }
        List<BigDecimal> existingBuys = recorder.findOpenMmCells(symbol).stream()
                .map(HtxLockedPairEntity::getBuyPrice)
                .collect(Collectors.toList());
        if (!MarketMakerMath.farEnough(buyPrice, existingBuys, mmProps.getMinGapPct())) {
            return -1;
        }
        BigDecimal buyNotional = qty.multiply(buyPrice);
        BigDecimal sellNotional = qty.multiply(sellPrice);
        BigDecimal maxSingle = tradeProps.getRisk().getMaxSingleQuoteNotional();
        if (maxSingle != null && maxSingle.signum() > 0
                && buyNotional.max(sellNotional).compareTo(maxSingle) > 0) {
            log.warn("MM single-notional gate blocked symbol={} notional={}", symbol, buyNotional.max(sellNotional));
            return -1;
        }
        BigDecimal maxDaily = tradeProps.getRisk().getMaxDailyQuoteNotional();
        if (maxDaily != null && maxDaily.signum() > 0) {
            BigDecimal usedToday;
            try {
                usedToday = recorder.sumTodayLiveQuoteNotional();
            } catch (RuntimeException ex) {
                log.error("MM daily-cap read failed; fail-closed skip open symbol={}", symbol, ex);
                return -1;
            }
            if (usedToday.add(buyNotional).compareTo(maxDaily) > 0) {
                log.warn("MM daily-cap gate blocked symbol={} used={} add={} max={}", symbol, usedToday, buyNotional, maxDaily);
                return -1;
            }
        }
        if (!depthGuard.check(depth, qty, tradeProps.getDepthGuard().getSafetyRatio(),
                tradeProps.getDepthGuard().getTopN()).allOk()) {
            return -1;
        }

        String base = "mm-" + symbol + "-" + System.currentTimeMillis();
        long pairId = recorder.createMmCell(symbol, qty, buyPrice, sellPrice, base); // fail-closed
        String buyClientId = base + "-BUY";
        HtxRestResponse<String> resp = htxApiService.getSpotService().placeOrder(
                tradeProps.getApi().getSpotAccountId(), symbol, "buy-limit",
                qty.toPlainString(), buyPrice.toPlainString(), buyClientId);
        Long buyId = parseOrderId(resp);
        if (buyId == null) {
            recorder.markMmCancelledBuy(pairId);
            log.warn("MM buy rejected symbol={} err={}", symbol, resp == null ? "null" : resp.getErrMsg());
            return -1;
        }
        recorder.markMmBuySent(pairId, buyId);
        log.info("MM opened pairId={} symbol={} buyId={} buy={} sell={} qty={}",
                pairId, symbol, buyId, buyPrice, sellPrice, qty);
        return pairId;
    }

    // ---- 状态机（D2） ----

    /** 按 cell 当前状态推进一步。 */
    public void advanceCell(MarketMakerCell cell) {
        if (HtxStatus.MM_RESTING_BUY.equals(cell.getState())) {
            advanceRestingBuy(cell);
        } else if (HtxStatus.MM_RESTING_SELL.equals(cell.getState())) {
            advanceRestingSell(cell);
        }
    }

    private void advanceRestingBuy(MarketMakerCell cell) {
        Long buyId = cell.getBuyOrderId();
        if (buyId == null) {
            return; // 崩溃在 createMmCell 与 markMmBuySent 之间；留待人工/后续对齐
        }
        HtxRestResponse<HtxOrder> resp = htxApiService.getSpotService().queryOrder(buyId);
        if (resp == null || !resp.ok() || resp.getData() == null) {
            return; // 瞬时故障，下 tick 重试
        }
        HtxOrder o = resp.getData();
        String state = o.getState();
        BigDecimal filled = nz(o.getFilledAmount());
        if ("filled".equalsIgnoreCase(state)) {
            openSellLeg(cell, filled, o.getFilledFees());
            return;
        }
        if (isTerminal(state)) { // 外部撤/拒
            if (filled.signum() > 0) {
                openSellLeg(cell, filled, o.getFilledFees());
            } else {
                recorder.markMmCancelledBuy(cell.getPairId());
            }
            return;
        }
        // 仍挂着（submitted/partial-filled）→ TTL 撤单
        if (isBuyExpired(cell)) {
            htxApiService.getSpotService().cancelOrder(buyId);
            HtxRestResponse<HtxOrder> post = htxApiService.getSpotService().queryOrder(buyId);
            BigDecimal postFilled = BigDecimal.ZERO;
            BigDecimal postFee = null;
            if (post != null && post.ok() && post.getData() != null) {
                postFilled = nz(post.getData().getFilledAmount());
                postFee = post.getData().getFilledFees();
            }
            if (postFilled.signum() > 0) {
                openSellLeg(cell, postFilled, postFee); // 撤单竞态：有成交则转挂卖
            } else {
                recorder.markMmCancelledBuy(cell.getPairId());
                log.info("MM buy expired & cancelled pairId={} buyId={}", cell.getPairId(), buyId);
            }
        }
    }

    private void openSellLeg(MarketMakerCell cell, BigDecimal filledBase, BigDecimal buyFeeBase) {
        BigDecimal sellable = HtxFeeMath.sellableBase(filledBase, buyFeeBase)
                .setScale(MarketMakerMath.QTY_SCALE, RoundingMode.HALF_DOWN);
        if (sellable.signum() <= 0) {
            recorder.markMmCancelledBuy(cell.getPairId());
            return;
        }
        String sellClientId = cell.getClientId() + "-SELL";
        HtxRestResponse<String> resp = htxApiService.getSpotService().placeOrder(
                tradeProps.getApi().getSpotAccountId(), cell.getSymbol(), "sell-limit",
                sellable.toPlainString(), cell.getSellPrice().toPlainString(), sellClientId);
        Long sellId = parseOrderId(resp);
        if (sellId == null) {
            recorder.recordExposure(cell.getPairId(), cell.getSymbol(), "base-long", sellable,
                    "mm-sell-rejected:" + (resp == null ? "null" : resp.getErrMsg()));
            recorder.markMmCancelledBuy(cell.getPairId());
            log.error("MM sell rejected after buy fill pairId={} sellable={}", cell.getPairId(), sellable);
            return;
        }
        recorder.markMmSellResting(cell.getPairId(), sellId, filledBase);
        log.info("MM sell resting pairId={} sellId={} sellable={}", cell.getPairId(), sellId, sellable);
    }

    private void advanceRestingSell(MarketMakerCell cell) {
        Long sellId = cell.getSellOrderId();
        if (sellId == null) {
            return;
        }
        HtxRestResponse<HtxOrder> resp = htxApiService.getSpotService().queryOrder(sellId);
        if (resp == null || !resp.ok() || resp.getData() == null) {
            return;
        }
        HtxOrder o = resp.getData();
        String state = o.getState();
        BigDecimal filled = nz(o.getFilledAmount());
        if ("filled".equalsIgnoreCase(state)) {
            recorder.markMmDone(cell.getPairId(), filled);
            log.info("MM done pairId={} sellId={} sellFilled={}", cell.getPairId(), sellId, filled);
            return;
        }
        if (isTerminal(state)) { // 卖单被外部撤/拒，仍持 base → 记敞口，关闭 cell
            BigDecimal unsold = nz(o.getAmount()).subtract(filled);
            if (unsold.signum() > 0) {
                recorder.recordExposure(cell.getPairId(), cell.getSymbol(), "base-long", unsold,
                        "mm-sell-terminal:" + state);
            }
            recorder.markMmDone(cell.getPairId(), filled);
        }
        // 仍挂着：卖单 TTL 默认 0 = 不撤，挂到成交
    }

    // ---- helpers ----

    private boolean isBuyExpired(MarketMakerCell cell) {
        if (mmProps.getBuyTtlMs() <= 0 || cell.getCreatedAt() == null) {
            return false;
        }
        return Duration.between(cell.getCreatedAt(), LocalDateTime.now()).toMillis() > mmProps.getBuyTtlMs();
    }

    private static boolean isTerminal(String state) {
        return "canceled".equalsIgnoreCase(state)
                || "partial-canceled".equalsIgnoreCase(state)
                || "rejected".equalsIgnoreCase(state);
    }

    private static Long parseOrderId(HtxRestResponse<String> resp) {
        if (resp == null || !resp.ok() || resp.getData() == null || resp.getData().isEmpty()) {
            return null;
        }
        try {
            return Long.parseLong(resp.getData().trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    private static boolean isEmpty(List<?> l) {
        return l == null || l.isEmpty();
    }
}
```

- [ ] **Step 2: 写 MarketMakerServiceOpenTest.java**

```java
package com.metaverse.coin.strategy.htx.strategy;

import com.metaverse.coin.strategy.common.client.htx.HtxApiService;
import com.metaverse.coin.strategy.common.client.htx.model.*;
import com.metaverse.coin.strategy.common.client.htx.rest.HtxMarketDataService;
import com.metaverse.coin.strategy.common.client.htx.rest.HtxSpotService;
import com.metaverse.coin.strategy.htx.config.HtxMarketMakerProperties;
import com.metaverse.coin.strategy.htx.persistence.HtxTradeRecorder;
import com.metaverse.coin.strategy.htx.persistence.HtxPersistenceException;
import com.metaverse.coin.strategy.htx.service.HtxDepthGuard;
import com.metaverse.coin.strategy.htx.service.HtxTradeProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class MarketMakerServiceOpenTest {

    HtxApiService api;
    HtxSpotService spot;
    HtxMarketDataService md;
    HtxDepthGuard depthGuard;
    HtxTradeProperties tradeProps;
    HtxMarketMakerProperties mmProps;
    HtxTradeRecorder recorder;
    MarketMakerService svc;

    @BeforeEach void setup() {
        api = mock(HtxApiService.class);
        spot = mock(HtxSpotService.class);
        md = mock(HtxMarketDataService.class);
        when(api.getSpotService()).thenReturn(spot);
        when(api.getMarketDataService()).thenReturn(md);
        depthGuard = mock(HtxDepthGuard.class);
        tradeProps = new HtxTradeProperties();
        tradeProps.getApi().setSpotAccountId("acc-1");
        mmProps = new HtxMarketMakerProperties();
        mmProps.setEnabled(true);
        recorder = mock(HtxTradeRecorder.class);
        svc = new MarketMakerService(api, depthGuard, tradeProps, mmProps, recorder);
        // 默认：K 未满、无已挂 buy、当日名义 0、深度闸放行
        when(recorder.countOpenMmCells(anyString())).thenReturn(0);
        when(recorder.findOpenMmCells(anyString())).thenReturn(List.of());
        when(recorder.sumTodayLiveQuoteNotional()).thenReturn(BigDecimal.ZERO);
        when(depthGuard.check(any(), any(), any(), anyInt()))
                .thenReturn(new HtxDepthGuard.Verdict(true, true, new BigDecimal("999"), new BigDecimal("999"), BigDecimal.ONE));
    }

    private HtxDepth depth(String bid, String ask) {
        HtxDepth d = new HtxDepth();
        d.setSymbol("btcusdt");
        d.setBids(List.of(new HtxPriceLevel(new BigDecimal(bid), new BigDecimal("10"))));
        d.setAsks(List.of(new HtxPriceLevel(new BigDecimal(ask), new BigDecimal("10"))));
        return d;
    }

    private HtxRestResponse<HtxDepth> depthOk(HtxDepth d) {
        HtxRestResponse<HtxDepth> r = new HtxRestResponse<>();
        r.setStatus("ok");
        r.setTick(d);
        return r;
    }

    private HtxRestResponse<String> placeOk(String id) {
        HtxRestResponse<String> r = new HtxRestResponse<>();
        r.setStatus("ok");
        r.setData(id);
        return r;
    }

    private HtxRestResponse<String> placeErr(String msg) {
        HtxRestResponse<String> r = new HtxRestResponse<>();
        r.setStatus("error");
        r.setErrMsg(msg);
        return r;
    }

    @Test void happy_path_creates_cell_places_buy_and_marks_sent() {
        when(md.depth(eq("btcusdt"), anyInt())).thenReturn(depthOk(depth("59990", "60010")));
        when(recorder.createMmCell(eq("btcusdt"), any(), any(), any(), anyString())).thenReturn(100L);
        when(spot.placeOrder(eq("acc-1"), eq("btcusdt"), eq("buy-limit"), anyString(), anyString(), anyString()))
                .thenReturn(placeOk("555"));

        long id = svc.openNewCellIfEligible("btcusdt");

        assertEquals(100L, id);
        verify(recorder).createMmCell(eq("btcusdt"), any(), any(), any(), anyString());
        verify(spot).placeOrder(eq("acc-1"), eq("btcusdt"), eq("buy-limit"), anyString(), anyString(), anyString());
        verify(recorder).markMmBuySent(100L, 555L);
    }

    @Test void non_whitelist_symbol_is_rejected_without_side_effects() {
        long id = svc.openNewCellIfEligible("dogeusdt");
        assertEquals(-1L, id);
        verifyNoInteractions(md);
        verify(recorder, never()).createMmCell(anyString(), any(), any(), any(), anyString());
        verify(spot, never()).placeOrder(anyString(), anyString(), anyString(), anyString(), anyString(), anyString());
    }

    @Test void k_cap_blocks_open() {
        mmProps.setMaxConcurrentCellsPerSymbol(3);
        when(recorder.countOpenMmCells("btcusdt")).thenReturn(3);
        assertEquals(-1L, svc.openNewCellIfEligible("btcusdt"));
        verify(spot, never()).placeOrder(anyString(), anyString(), anyString(), anyString(), anyString(), anyString());
    }

    @Test void no_loss_gate_blocks_when_spread_too_small_vs_fee() {
        mmProps.setSpreadPct(new BigDecimal("0.001")); // (1-0.002)^2*1.001 < 0.999 → 亏
        when(md.depth(eq("btcusdt"), anyInt())).thenReturn(depthOk(depth("100", "100")));
        assertEquals(-1L, svc.openNewCellIfEligible("btcusdt"));
        verify(recorder, never()).createMmCell(anyString(), any(), any(), any(), anyString());
    }

    @Test void min_gap_blocks_when_candidate_too_close_to_existing() {
        when(md.depth(eq("btcusdt"), anyInt())).thenReturn(depthOk(depth("59990", "60010")));
        // 候选 buyPrice≈59700；放一个极近的已挂 buy 使 farEnough=false
        var existing = new com.metaverse.coin.strategy.htx.persistence.entity.HtxLockedPairEntity();
        existing.setBuyPrice(new BigDecimal("59700.10"));
        when(recorder.findOpenMmCells("btcusdt")).thenReturn(List.of(existing));
        assertEquals(-1L, svc.openNewCellIfEligible("btcusdt"));
        verify(recorder, never()).createMmCell(anyString(), any(), any(), any(), anyString());
    }

    @Test void single_notional_gate_blocks() {
        tradeProps.getRisk().setMaxSingleQuoteNotional(new BigDecimal("5")); // perCell=20 > 5
        when(md.depth(eq("btcusdt"), anyInt())).thenReturn(depthOk(depth("59990", "60010")));
        assertEquals(-1L, svc.openNewCellIfEligible("btcusdt"));
        verify(recorder, never()).createMmCell(anyString(), any(), any(), any(), anyString());
    }

    @Test void daily_cap_gate_blocks() {
        tradeProps.getRisk().setMaxDailyQuoteNotional(new BigDecimal("10")); // used0 + ~20 > 10
        when(md.depth(eq("btcusdt"), anyInt())).thenReturn(depthOk(depth("59990", "60010")));
        assertEquals(-1L, svc.openNewCellIfEligible("btcusdt"));
        verify(recorder, never()).createMmCell(anyString(), any(), any(), any(), anyString());
    }

    @Test void daily_cap_read_failure_is_fail_closed() {
        when(md.depth(eq("btcusdt"), anyInt())).thenReturn(depthOk(depth("59990", "60010")));
        when(recorder.sumTodayLiveQuoteNotional()).thenThrow(new HtxPersistenceException("db down", null));
        assertEquals(-1L, svc.openNewCellIfEligible("btcusdt"));
        verify(recorder, never()).createMmCell(anyString(), any(), any(), any(), anyString());
    }

    @Test void depth_guard_blocks_open() {
        when(md.depth(eq("btcusdt"), anyInt())).thenReturn(depthOk(depth("59990", "60010")));
        when(depthGuard.check(any(), any(), any(), anyInt()))
                .thenReturn(new HtxDepthGuard.Verdict(false, false, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.TEN));
        assertEquals(-1L, svc.openNewCellIfEligible("btcusdt"));
        verify(recorder, never()).createMmCell(anyString(), any(), any(), any(), anyString());
    }

    @Test void buy_rejected_marks_cancelled_buy() {
        when(md.depth(eq("btcusdt"), anyInt())).thenReturn(depthOk(depth("59990", "60010")));
        when(recorder.createMmCell(eq("btcusdt"), any(), any(), any(), anyString())).thenReturn(100L);
        when(spot.placeOrder(anyString(), anyString(), anyString(), anyString(), anyString(), anyString()))
                .thenReturn(placeErr("insufficient-balance"));
        assertEquals(-1L, svc.openNewCellIfEligible("btcusdt"));
        verify(recorder).markMmCancelledBuy(100L);
        verify(recorder, never()).markMmBuySent(anyLong(), anyLong());
    }

    @Test void createMmCell_failure_is_fail_closed_no_order_sent() {
        when(md.depth(eq("btcusdt"), anyInt())).thenReturn(depthOk(depth("59990", "60010")));
        when(recorder.createMmCell(eq("btcusdt"), any(), any(), any(), anyString()))
                .thenThrow(new HtxPersistenceException("db down", null));
        assertThrows(HtxPersistenceException.class, () -> svc.openNewCellIfEligible("btcusdt"));
        verify(spot, never()).placeOrder(anyString(), anyString(), anyString(), anyString(), anyString(), anyString());
    }
}
```

- [ ] **Step 3: 跑测试（应全绿）**

Run: `export JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-17.jdk/Contents/Home && mvn -pl coin-strategy-htx test -Dtest='MarketMakerServiceOpenTest' -DfailIfNoTests=false -q`
Expected: PASS（11 tests）

- [ ] **Step 4: 提交**

```bash
git add coin-strategy-htx/src/main/java/com/metaverse/coin/strategy/htx/strategy/MarketMakerService.java \
        coin-strategy-htx/src/test/java/com/metaverse/coin/strategy/htx/strategy/MarketMakerServiceOpenTest.java
git commit -m "feat(htx-mm): MarketMakerService 开仓多闸门决策（fail-closed）"
```

---

## Task D2: 状态机推进 advanceCell

实现已在 D1 的 Step 1 里写好（advanceRestingBuy/openSellLeg/advanceRestingSell）。本任务只补测试。

**Files:**
- Test: `.../strategy/MarketMakerServiceAdvanceTest.java`

- [ ] **Step 1: 写 MarketMakerServiceAdvanceTest.java**

```java
package com.metaverse.coin.strategy.htx.strategy;

import com.metaverse.coin.strategy.common.client.htx.HtxApiService;
import com.metaverse.coin.strategy.common.client.htx.model.HtxOrder;
import com.metaverse.coin.strategy.common.client.htx.model.HtxRestResponse;
import com.metaverse.coin.strategy.common.client.htx.rest.HtxSpotService;
import com.metaverse.coin.strategy.htx.config.HtxMarketMakerProperties;
import com.metaverse.coin.strategy.htx.persistence.HtxStatus;
import com.metaverse.coin.strategy.htx.persistence.HtxTradeRecorder;
import com.metaverse.coin.strategy.htx.service.HtxDepthGuard;
import com.metaverse.coin.strategy.htx.service.HtxTradeProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.mockito.Mockito.*;

class MarketMakerServiceAdvanceTest {

    HtxApiService api;
    HtxSpotService spot;
    HtxTradeProperties tradeProps;
    HtxMarketMakerProperties mmProps;
    HtxTradeRecorder recorder;
    MarketMakerService svc;

    @BeforeEach void setup() {
        api = mock(HtxApiService.class);
        spot = mock(HtxSpotService.class);
        when(api.getSpotService()).thenReturn(spot);
        tradeProps = new HtxTradeProperties();
        tradeProps.getApi().setSpotAccountId("acc-1");
        mmProps = new HtxMarketMakerProperties();
        recorder = mock(HtxTradeRecorder.class);
        svc = new MarketMakerService(api, mock(HtxDepthGuard.class), tradeProps, mmProps, recorder);
    }

    private MarketMakerCell buyCell(Long buyId, LocalDateTime created) {
        return MarketMakerCell.builder()
                .pairId(100L).clientId("mm-btcusdt-1").symbol("btcusdt")
                .buyPrice(new BigDecimal("59700.00")).sellPrice(new BigDecimal("60300.00"))
                .qty(new BigDecimal("0.0003")).state(HtxStatus.MM_RESTING_BUY)
                .buyOrderId(buyId).createdAt(created).build();
    }

    private MarketMakerCell sellCell(Long sellId) {
        return MarketMakerCell.builder()
                .pairId(100L).clientId("mm-btcusdt-1").symbol("btcusdt")
                .buyPrice(new BigDecimal("59700.00")).sellPrice(new BigDecimal("60300.00"))
                .qty(new BigDecimal("0.0003")).state(HtxStatus.MM_RESTING_SELL)
                .buyOrderId(555L).sellOrderId(sellId).createdAt(LocalDateTime.now()).build();
    }

    private HtxRestResponse<HtxOrder> orderOk(String state, String filled, String fees, String amount) {
        HtxOrder o = new HtxOrder();
        o.setState(state);
        if (filled != null) o.setFilledAmount(new BigDecimal(filled));
        if (fees != null) o.setFilledFees(new BigDecimal(fees));
        if (amount != null) o.setAmount(new BigDecimal(amount));
        HtxRestResponse<HtxOrder> r = new HtxRestResponse<>();
        r.setStatus("ok");
        r.setData(o);
        return r;
    }

    private HtxRestResponse<String> placeOk(String id) {
        HtxRestResponse<String> r = new HtxRestResponse<>();
        r.setStatus("ok");
        r.setData(id);
        return r;
    }

    @Test void resting_buy_filled_places_sell_and_marks_resting() {
        when(spot.queryOrder(555L)).thenReturn(orderOk("filled", "0.0003", "0.0000006", null));
        when(spot.placeOrder(eq("acc-1"), eq("btcusdt"), eq("sell-limit"), anyString(), eq("60300.00"), eq("mm-btcusdt-1-SELL")))
                .thenReturn(placeOk("777"));
        svc.advanceCell(buyCell(555L, LocalDateTime.now()));
        verify(spot).placeOrder(eq("acc-1"), eq("btcusdt"), eq("sell-limit"), anyString(), eq("60300.00"), eq("mm-btcusdt-1-SELL"));
        verify(recorder).markMmSellResting(eq(100L), eq(777L), any());
    }

    @Test void resting_buy_still_open_under_ttl_is_noop() {
        mmProps.setBuyTtlMs(3_600_000L);
        when(spot.queryOrder(555L)).thenReturn(orderOk("submitted", "0", null, null));
        svc.advanceCell(buyCell(555L, LocalDateTime.now()));
        verify(spot, never()).cancelOrder(anyLong());
        verify(recorder, never()).markMmCancelledBuy(anyLong());
        verify(spot, never()).placeOrder(anyString(), anyString(), anyString(), anyString(), anyString(), anyString());
    }

    @Test void resting_buy_over_ttl_cancels_and_marks_cancelled_buy() {
        mmProps.setBuyTtlMs(1L);
        when(spot.queryOrder(555L)).thenReturn(orderOk("submitted", "0", null, null))
                .thenReturn(orderOk("canceled", "0", null, null)); // post-cancel query
        when(spot.cancelOrder(555L)).thenReturn(placeOk("555"));
        svc.advanceCell(buyCell(555L, LocalDateTime.now().minusHours(2)));
        verify(spot).cancelOrder(555L);
        verify(recorder).markMmCancelledBuy(100L);
    }

    @Test void resting_buy_over_ttl_cancel_race_filled_opens_sell() {
        mmProps.setBuyTtlMs(1L);
        when(spot.queryOrder(555L)).thenReturn(orderOk("submitted", "0", null, null))
                .thenReturn(orderOk("canceled", "0.0003", "0.0000006", null)); // 撤单竞态：已成交
        when(spot.cancelOrder(555L)).thenReturn(placeOk("555"));
        when(spot.placeOrder(anyString(), anyString(), eq("sell-limit"), anyString(), anyString(), anyString()))
                .thenReturn(placeOk("777"));
        svc.advanceCell(buyCell(555L, LocalDateTime.now().minusHours(2)));
        verify(spot).placeOrder(anyString(), anyString(), eq("sell-limit"), anyString(), anyString(), anyString());
        verify(recorder).markMmSellResting(eq(100L), eq(777L), any());
        verify(recorder, never()).markMmCancelledBuy(anyLong());
    }

    @Test void resting_buy_query_not_ok_is_noop() {
        HtxRestResponse<HtxOrder> bad = new HtxRestResponse<>();
        bad.setStatus("error");
        when(spot.queryOrder(555L)).thenReturn(bad);
        svc.advanceCell(buyCell(555L, LocalDateTime.now()));
        verify(recorder, never()).markMmCancelledBuy(anyLong());
        verify(spot, never()).cancelOrder(anyLong());
        verify(spot, never()).placeOrder(anyString(), anyString(), anyString(), anyString(), anyString(), anyString());
    }

    @Test void sell_rejected_after_buy_fill_records_exposure_and_closes_cell() {
        when(spot.queryOrder(555L)).thenReturn(orderOk("filled", "0.0003", "0.0000006", null));
        HtxRestResponse<String> err = new HtxRestResponse<>();
        err.setStatus("error");
        err.setErrMsg("sell-rejected");
        when(spot.placeOrder(anyString(), anyString(), eq("sell-limit"), anyString(), anyString(), anyString()))
                .thenReturn(err);
        svc.advanceCell(buyCell(555L, LocalDateTime.now()));
        verify(recorder).recordExposure(eq(100L), eq("btcusdt"), eq("base-long"), any(), anyString());
        verify(recorder).markMmCancelledBuy(100L);
        verify(recorder, never()).markMmSellResting(anyLong(), anyLong(), any());
    }

    @Test void resting_sell_filled_marks_done() {
        when(spot.queryOrder(777L)).thenReturn(orderOk("filled", "0.0003", null, "0.0003"));
        svc.advanceCell(sellCell(777L));
        verify(recorder).markMmDone(eq(100L), any());
    }

    @Test void resting_sell_still_open_is_noop() {
        when(spot.queryOrder(777L)).thenReturn(orderOk("submitted", "0", null, "0.0003"));
        svc.advanceCell(sellCell(777L));
        verify(recorder, never()).markMmDone(anyLong(), any());
        verify(recorder, never()).recordExposure(anyLong(), anyString(), anyString(), any(), anyString());
    }

    @Test void resting_sell_external_cancel_records_exposure_and_done() {
        when(spot.queryOrder(777L)).thenReturn(orderOk("canceled", "0.0001", null, "0.0003"));
        svc.advanceCell(sellCell(777L));
        verify(recorder).recordExposure(eq(100L), eq("btcusdt"), eq("base-long"), any(), anyString());
        verify(recorder).markMmDone(eq(100L), any());
    }
}
```

- [ ] **Step 2: 跑测试**

Run: `export JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-17.jdk/Contents/Home && mvn -pl coin-strategy-htx test -Dtest='MarketMakerServiceAdvanceTest' -DfailIfNoTests=false -q`
Expected: PASS（9 tests）

- [ ] **Step 3: 提交**

```bash
git add coin-strategy-htx/src/test/java/com/metaverse/coin/strategy/htx/strategy/MarketMakerServiceAdvanceTest.java
git commit -m "test(htx-mm): MarketMakerService 状态机推进（买成交→挂卖→完成 + TTL/敞口）"
```

---

## Task D3: 编排 runOnce + recoverOpenCells

实现已在 D1 Step 1（runOnce/recoverOpenCells/loadOpenCells）。本任务补测试。

**Files:**
- Test: `.../strategy/MarketMakerServiceRunTest.java`

- [ ] **Step 1: 写 MarketMakerServiceRunTest.java**

```java
package com.metaverse.coin.strategy.htx.strategy;

import com.metaverse.coin.strategy.common.client.htx.HtxApiService;
import com.metaverse.coin.strategy.common.client.htx.model.*;
import com.metaverse.coin.strategy.common.client.htx.rest.HtxMarketDataService;
import com.metaverse.coin.strategy.common.client.htx.rest.HtxSpotService;
import com.metaverse.coin.strategy.htx.config.HtxMarketMakerProperties;
import com.metaverse.coin.strategy.htx.persistence.HtxStatus;
import com.metaverse.coin.strategy.htx.persistence.HtxTradeRecorder;
import com.metaverse.coin.strategy.htx.persistence.entity.HtxLockedPairEntity;
import com.metaverse.coin.strategy.htx.service.HtxDepthGuard;
import com.metaverse.coin.strategy.htx.service.HtxTradeProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.Mockito.*;

class MarketMakerServiceRunTest {

    HtxApiService api;
    HtxSpotService spot;
    HtxMarketDataService md;
    HtxDepthGuard depthGuard;
    HtxTradeProperties tradeProps;
    HtxMarketMakerProperties mmProps;
    HtxTradeRecorder recorder;
    MarketMakerService svc;

    @BeforeEach void setup() {
        api = mock(HtxApiService.class);
        spot = mock(HtxSpotService.class);
        md = mock(HtxMarketDataService.class);
        when(api.getSpotService()).thenReturn(spot);
        when(api.getMarketDataService()).thenReturn(md);
        depthGuard = mock(HtxDepthGuard.class);
        when(depthGuard.check(any(), any(), any(), anyInt()))
                .thenReturn(new HtxDepthGuard.Verdict(true, true, new BigDecimal("999"), new BigDecimal("999"), BigDecimal.ONE));
        tradeProps = new HtxTradeProperties();
        tradeProps.getApi().setSpotAccountId("acc-1");
        mmProps = new HtxMarketMakerProperties();
        mmProps.setSymbols(List.of("btcusdt"));
        recorder = mock(HtxTradeRecorder.class);
        when(recorder.countOpenMmCells(anyString())).thenReturn(0);
        when(recorder.findOpenMmCells(anyString())).thenReturn(List.of());
        when(recorder.sumTodayLiveQuoteNotional()).thenReturn(BigDecimal.ZERO);
        svc = new MarketMakerService(api, depthGuard, tradeProps, mmProps, recorder);
    }

    private HtxLockedPairEntity restingSellEntity() {
        HtxLockedPairEntity e = new HtxLockedPairEntity();
        e.setId(100L);
        e.setClientOrderId("mm-btcusdt-1");
        e.setSymbol("btcusdt");
        e.setBuyPrice(new BigDecimal("59700.00"));
        e.setSellPrice(new BigDecimal("60300.00"));
        e.setReqQuantity(new BigDecimal("0.0003"));
        e.setStatus(HtxStatus.MM_RESTING_SELL);
        e.setSellOrderId(777L);
        e.setCreatedAt(LocalDateTime.now());
        return e;
    }

    private HtxRestResponse<HtxDepth> depthOk() {
        HtxDepth d = new HtxDepth();
        d.setSymbol("btcusdt");
        d.setBids(List.of(new HtxPriceLevel(new BigDecimal("59990"), new BigDecimal("10"))));
        d.setAsks(List.of(new HtxPriceLevel(new BigDecimal("60010"), new BigDecimal("10"))));
        HtxRestResponse<HtxDepth> r = new HtxRestResponse<>();
        r.setStatus("ok");
        r.setTick(d);
        return r;
    }

    private HtxRestResponse<HtxOrder> sellFilled() {
        HtxOrder o = new HtxOrder();
        o.setState("filled");
        o.setFilledAmount(new BigDecimal("0.0003"));
        o.setAmount(new BigDecimal("0.0003"));
        HtxRestResponse<HtxOrder> r = new HtxRestResponse<>();
        r.setStatus("ok");
        r.setData(o);
        return r;
    }

    private HtxRestResponse<String> placeOk(String id) {
        HtxRestResponse<String> r = new HtxRestResponse<>();
        r.setStatus("ok");
        r.setData(id);
        return r;
    }

    @Test void runOnce_advances_open_cells_then_opens_new() {
        when(recorder.findAllOpenMmCells()).thenReturn(List.of(restingSellEntity()));
        when(spot.queryOrder(777L)).thenReturn(sellFilled());
        when(md.depth(eq("btcusdt"), anyInt())).thenReturn(depthOk());
        when(recorder.createMmCell(anyString(), any(), any(), any(), anyString())).thenReturn(101L);
        when(spot.placeOrder(anyString(), anyString(), eq("buy-limit"), anyString(), anyString(), anyString()))
                .thenReturn(placeOk("888"));
        mmProps.setEnabled(true);

        svc.runOnce();

        verify(recorder).markMmDone(eq(100L), any());           // 推进已有卖单
        verify(recorder).createMmCell(anyString(), any(), any(), any(), anyString()); // 再开新
        verify(recorder).markMmBuySent(101L, 888L);
    }

    @Test void runOnce_when_disabled_advances_but_does_not_open() {
        when(recorder.findAllOpenMmCells()).thenReturn(List.of(restingSellEntity()));
        when(spot.queryOrder(777L)).thenReturn(sellFilled());
        mmProps.setEnabled(false);

        svc.runOnce();

        verify(recorder).markMmDone(eq(100L), any());           // 仍推进
        verify(recorder, never()).createMmCell(anyString(), any(), any(), any(), anyString()); // 不开新
        verify(md, never()).depth(anyString(), anyInt());
    }

    @Test void recoverOpenCells_advances_all() {
        when(recorder.findAllOpenMmCells()).thenReturn(List.of(restingSellEntity()));
        when(spot.queryOrder(777L)).thenReturn(sellFilled());

        svc.recoverOpenCells();

        verify(recorder).markMmDone(eq(100L), any());
        verify(recorder, never()).createMmCell(anyString(), any(), any(), any(), anyString());
    }
}
```

- [ ] **Step 2: 跑全 htx 模块测试（回归）**

Run: `export JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-17.jdk/Contents/Home && mvn -pl coin-strategy-htx test -Dtest='!BinanceFuturesMarketDataTest,!BinanceMarketDataDebug,!BinanceFuturesDebug,!BinanceSpotDebug' -DfailIfNoTests=false -q`
Expected: PASS，0 failures（原 63 + 新增 ~23）

- [ ] **Step 3: 提交**

```bash
git add coin-strategy-htx/src/test/java/com/metaverse/coin/strategy/htx/strategy/MarketMakerServiceRunTest.java
git commit -m "test(htx-mm): MarketMakerService runOnce/recover 编排（急停只停开新）"
```

---

## 自查

- **spec 覆盖:** §3 状态机（RESTING_BUY→RESTING_SELL→DONE，超 TTL→CANCELLED_BUY）✓ D2；§4 循环（先推进后开新）✓ D3；§6 恢复（findAllOpenMmCells+对齐）✓ D3；开仓闸门（白名单/K/不亏/min-gap/单笔/每日/深度）✓ D1。
- **占位符:** 无 TBD；所有测试与实现均含完整代码。
- **类型一致:** `openNewCellIfEligible`/`advanceCell`/`runOnce`/`recoverOpenCells` 全程一致；`markMmSellResting(pairId,sellId,buyFilled)`、`markMmDone(pairId,sellFilled)`、`recordExposure(pairId,symbol,side,amount,detail)` 与第一批 Recorder 签名一致。
- **fail-closed:** createMmCell 抛异常在 placeOrder 之前 → 不发单（D1 test 验证）；每日名义读失败跳过（D1 test 验证）。
- **敞口安全:** 卖腿失败/外部撤 → recordExposure + 关闭 cell，防重复挂卖 → 交对账器市价平（不受 MM 豁免影响，因显式写 htx_exposure）。

## 延后（批 3）

- `HtxMarketMakerScheduler`（@Scheduled 调 runOnce；启动 recoverOpenCells）+ application.yml `htx.market-maker.*` + @SpringBootTest 集成 + 对账器 MM 豁免回归测试 + 生产 `ALTER TABLE htx_locked_pair ADD COLUMN strategy VARCHAR(16) NULL;`。
- 卖单 TTL 主动撤（默认 0=不撤，本期不实现）。
