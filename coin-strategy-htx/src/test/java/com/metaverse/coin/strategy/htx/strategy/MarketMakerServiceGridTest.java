package com.metaverse.coin.strategy.htx.strategy;

import com.metaverse.coin.strategy.common.client.htx.HtxApiService;
import com.metaverse.coin.strategy.common.client.htx.model.*;
import com.metaverse.coin.strategy.common.client.htx.rest.HtxMarketDataService;
import com.metaverse.coin.strategy.common.client.htx.rest.HtxAccountService;
import com.metaverse.coin.strategy.common.client.htx.rest.HtxSpotService;
import com.metaverse.coin.strategy.htx.config.HtxMarketMakerProperties;
import com.metaverse.coin.strategy.htx.persistence.HtxStatus;
import com.metaverse.coin.strategy.htx.persistence.HtxTradeRecorder;
import com.metaverse.coin.strategy.htx.persistence.HtxPersistenceException;
import com.metaverse.coin.strategy.htx.persistence.entity.HtxLockedPairEntity;
import com.metaverse.coin.strategy.htx.service.HtxDepthGuard;
import com.metaverse.coin.strategy.htx.service.HtxTradeProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.*;

/**
 * 网格化刷量（grid volume churn）服务层测试。见 spec 2026-08-18-htx-spot-grid-volume-churn-design.md §6。
 * 锁定：等比全梯补齐（D1/D2）、总敞口+地板双闸（D3）、当日净亏损/连亏熔断（D4）。
 * 复用 MarketMakerServiceOpenTest 的 Mockito 装置风格；legacy 卖价路径（sellFollow=false）。
 */
class MarketMakerServiceGridTest {

    HtxApiService api;
    HtxSpotService spot;
    HtxMarketDataService md;
    HtxAccountService account;
    HtxDepthGuard depthGuard;
    HtxTradeProperties tradeProps;
    HtxMarketMakerProperties mmProps;
    HtxTradeRecorder recorder;
    MarketMakerService svc;
    HtxGridWindowStore windowStore;

    @BeforeEach void setup() {
        api = mock(HtxApiService.class);
        spot = mock(HtxSpotService.class);
        md = mock(HtxMarketDataService.class);
        account = mock(HtxAccountService.class);
        when(api.getSpotService()).thenReturn(spot);
        when(api.getMarketDataService()).thenReturn(md);
        when(api.getAccountService()).thenReturn(account);
        depthGuard = mock(HtxDepthGuard.class);
        tradeProps = new HtxTradeProperties();
        tradeProps.getApi().setSpotAccountId("acc-1");
        tradeProps.getTrade().setEnabled(true);
        mmProps = new HtxMarketMakerProperties();
        mmProps.setSymbols(List.of("btcusdt", "xautusdt"));
        mmProps.setEnabled(true);
        mmProps.setSellFollowEnabled(false);
        mmProps.setGridModeEnabled(true);
        mmProps.setGridStepPct(new BigDecimal("0.003"));
        mmProps.setGridLevels(5);
        mmProps.setMaxConcurrentCellsPerSymbol(3);
        mmProps.setPerCellQuoteNotional(new BigDecimal("20"));
        mmProps.setMaxTotalExposureQuoteNotional(new BigDecimal("100"));
        mmProps.setDailyNetLossBudget(new BigDecimal("5"));
        mmProps.setMaxConsecutiveLossCycles(3);
        recorder = mock(HtxTradeRecorder.class);
        windowStore = new HtxGridWindowStore(null);
        svc = new MarketMakerService(api, depthGuard, tradeProps, mmProps, recorder, windowStore);
        // 默认：K 未满、无已挂 cell、当日名义 0、当日净额 0、余额充足、深度闸放行
        when(recorder.countOpenMmBuyCells(anyString())).thenReturn(0);
        when(recorder.findOpenMmCells(anyString())).thenReturn(List.of());
        when(recorder.sumTodayMmRiskQuoteNotional()).thenReturn(BigDecimal.ZERO);
        when(recorder.sumTodayMmRealizedNetQuote()).thenReturn(BigDecimal.ZERO);
        when(account.getAccountBalance("acc-1")).thenReturn(balanceOk("100000"));
        when(depthGuard.check(any(), any(), any(), anyInt()))
                .thenReturn(new HtxDepthGuard.Verdict(true, true, new BigDecimal("999"), new BigDecimal("999"), BigDecimal.ONE));
        when(recorder.createMmCell(anyString(), any(), any(), any(), anyString())).thenReturn(100L);
        when(spot.placeOrder(anyString(), anyString(), eq("buy-limit-maker"), anyString(), anyString(), anyString()))
                .thenReturn(placeOk("555"));
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

    private HtxRestResponse<HtxAccount> balanceOk(String availableUsdt) {
        return balanceOk(availableUsdt, "0");
    }

    private HtxRestResponse<HtxAccount> balanceOk(String availableUsdt, String baseInventory) {
        HtxBalance balance = new HtxBalance();
        balance.setCurrency("usdt");
        balance.setType("trade");
        balance.setBalance(new BigDecimal(availableUsdt));
        HtxBalance base = new HtxBalance();
        base.setCurrency("btc");
        base.setType("trade");
        base.setBalance(new BigDecimal(baseInventory));
        HtxAccount data = new HtxAccount();
        data.setList(List.of(balance, base));
        HtxRestResponse<HtxAccount> response = new HtxRestResponse<>();
        response.setStatus("ok");
        response.setData(data);
        return response;
    }

    private HtxLockedPairEntity restingBuy(String buyPrice, String qty) {
        HtxLockedPairEntity e = new HtxLockedPairEntity();
        e.setBuyPrice(new BigDecimal(buyPrice));
        e.setReqQuantity(new BigDecimal(qty));
        e.setStatus(HtxStatus.MM_RESTING_BUY);
        return e;
    }

    private HtxLockedPairEntity restingBuy(long id, long orderId, String buyPrice, String qty) {
        HtxLockedPairEntity entity = restingBuy(buyPrice, qty);
        entity.setId(id);
        entity.setSymbol("btcusdt");
        entity.setClientOrderId("mm-btcusdt-" + id);
        entity.setBuyOrderId(orderId);
        return entity;
    }

    private MarketMakerCell restingSellCell(String buy, String sell) {
        return MarketMakerCell.builder()
                .pairId(100L)
                .clientId("mm-btcusdt-100")
                .symbol("btcusdt")
                .buyPrice(new BigDecimal(buy))
                .sellPrice(new BigDecimal(sell))
                .qty(new BigDecimal("0.0003"))
                .state(HtxStatus.MM_RESTING_SELL)
                .buyOrderId(555L)
                .sellOrderId(777L)
                .buyFilledAmount(new BigDecimal("0.0003"))
                .totalFeeBase(BigDecimal.ZERO)
                .createdAt(LocalDateTime.now())
                .build();
    }

    private MarketMakerCell retryableSellCellWithoutOrder(String buy, String sell) {
        return MarketMakerCell.builder()
                .pairId(101L)
                .clientId("mm-btcusdt-101")
                .symbol("btcusdt")
                .buyPrice(new BigDecimal(buy))
                .sellPrice(new BigDecimal(sell))
                .qty(new BigDecimal("0.0003"))
                .state(HtxStatus.MM_SELL_RETRYABLE)
                .buyOrderId(556L)
                .buyFilledAmount(new BigDecimal("0.0003"))
                .sellFilledAmount(BigDecimal.ZERO)
                .totalFeeBase(BigDecimal.ZERO)
                .createdAt(LocalDateTime.now())
                .build();
    }

    private HtxLockedPairEntity restingSell(long id, String price) {
        HtxLockedPairEntity entity = new HtxLockedPairEntity();
        entity.setId(id);
        entity.setSymbol("btcusdt");
        entity.setSellPrice(new BigDecimal(price));
        entity.setStatus(HtxStatus.MM_RESTING_SELL);
        return entity;
    }

    @Test void empty_inventory_fills_ladder_up_to_concurrency_budget() {
        // bestBid=100, step=0.003, levels=5 → [100.00, 99.70, 99.40, 99.10, 98.81]
        // maxConcurrent=3, open=0 → budget=3 → 只补最上三档
        when(md.depth(eq("btcusdt"), anyInt())).thenReturn(depthOk(depth("100", "100.05")));

        svc.fillGridLadder("btcusdt");

        verify(spot).placeOrder(eq("acc-1"), eq("btcusdt"), eq("buy-limit-maker"), anyString(), eq("100.00"), anyString());
        verify(spot).placeOrder(eq("acc-1"), eq("btcusdt"), eq("buy-limit-maker"), anyString(), eq("99.70"), anyString());
        verify(spot).placeOrder(eq("acc-1"), eq("btcusdt"), eq("buy-limit-maker"), anyString(), eq("99.40"), anyString());
        verify(spot, never()).placeOrder(anyString(), anyString(), anyString(), anyString(), eq("98.81"), anyString());
        verify(spot, never()).placeOrder(anyString(), anyString(), anyString(), anyString(), eq("98.51"), anyString());
        verify(spot, times(3)).placeOrder(anyString(), anyString(), eq("buy-limit-maker"), anyString(), anyString(), anyString());
    }

    @Test void partial_ladder_only_opens_missing_levels() {
        // 已挂两档（100.00 / 99.70），open=2 → budget=1 → 仅补下一缺口 99.40
        when(md.depth(eq("btcusdt"), anyInt())).thenReturn(depthOk(depth("100", "100.05")));
        when(recorder.countOpenMmBuyCells("btcusdt")).thenReturn(2);
        when(recorder.findOpenMmCells("btcusdt")).thenReturn(List.of(
                restingBuy("100.00", "0.2000"), restingBuy("99.70", "0.2006")));

        svc.fillGridLadder("btcusdt");

        // budget=1：只开一档，且是首个与已挂档拉开 min-gap 的价位（99.40）
        verify(spot, times(1)).placeOrder(anyString(), anyString(), eq("buy-limit-maker"), anyString(), anyString(), anyString());
        verify(spot).placeOrder(eq("acc-1"), eq("btcusdt"), eq("buy-limit-maker"), anyString(), eq("99.40"), anyString());
    }

    @Test void circuit_breaker_daily_net_loss_blocks_all_opens() {
        // 当日净亏损 -10 > 预算 5 → 熔断，全 tick 零开
        when(md.depth(eq("btcusdt"), anyInt())).thenReturn(depthOk(depth("100", "100.05")));
        when(recorder.sumTodayMmRealizedNetQuote()).thenReturn(new BigDecimal("-10"));

        svc.fillGridLadder("btcusdt");

        verify(spot, never()).placeOrder(anyString(), anyString(), anyString(), anyString(), anyString(), anyString());
        verify(recorder, never()).createMmCell(anyString(), any(), any(), any(), anyString());
    }

    @Test void circuit_breaker_daily_net_loss_read_failure_is_fail_closed() {
        when(md.depth(eq("btcusdt"), anyInt())).thenReturn(depthOk(depth("100", "100.05")));
        when(recorder.sumTodayMmRealizedNetQuote()).thenThrow(new HtxPersistenceException("db down", null));

        svc.fillGridLadder("btcusdt");

        verify(recorder, never()).createMmCell(anyString(), any(), any(), any(), anyString());
    }

    @Test void total_exposure_cap_stops_deeper_levels() {
        // maxTotalExposure=45, perCell=20：第1格(0→20)、第2格(20→40) 过；第3格(40→60>45) 停
        mmProps.setMaxTotalExposureQuoteNotional(new BigDecimal("45"));
        when(md.depth(eq("btcusdt"), anyInt())).thenReturn(depthOk(depth("100", "100.05")));

        svc.fillGridLadder("btcusdt");

        verify(spot, times(2)).placeOrder(anyString(), anyString(), eq("buy-limit-maker"), anyString(), anyString(), anyString());
        verify(spot).placeOrder(eq("acc-1"), eq("btcusdt"), eq("buy-limit-maker"), anyString(), eq("100.00"), anyString());
        verify(spot).placeOrder(eq("acc-1"), eq("btcusdt"), eq("buy-limit-maker"), anyString(), eq("99.70"), anyString());
        verify(spot, never()).placeOrder(anyString(), anyString(), anyString(), anyString(), eq("99.40"), anyString());
    }

    @Test void recent_zero_fill_cancelled_buy_cools_same_grid_level() {
        when(md.depth(eq("btcusdt"), anyInt())).thenReturn(depthOk(depth("100", "100.05")));
        when(recorder.hasRecentZeroFillCancelledMmBuy(eq("btcusdt"), eq(new BigDecimal("100.00")),
                eq(mmProps.getMinGapPct()), any())).thenReturn(true);

        svc.fillGridLadder("btcusdt");

        verify(recorder, never()).createMmCell(eq("btcusdt"), any(), eq(new BigDecimal("100.00")), any(), anyString());
        verify(spot, never()).placeOrder(eq("acc-1"), eq("btcusdt"), eq("buy-limit-maker"),
                anyString(), eq("100.00"), anyString());
        verify(spot).placeOrder(eq("acc-1"), eq("btcusdt"), eq("buy-limit-maker"),
                anyString(), eq("99.70"), anyString());
    }

    @Test void buy_retry_cooldown_read_failure_blocks_new_buy_fail_closed() {
        when(md.depth(eq("btcusdt"), anyInt())).thenReturn(depthOk(depth("100", "100.05")));
        when(recorder.hasRecentZeroFillCancelledMmBuy(anyString(), any(), any(), any()))
                .thenThrow(new HtxPersistenceException("db down", null));

        svc.fillGridLadder("btcusdt");

        verify(recorder, never()).createMmCell(anyString(), any(), any(), any(), anyString());
        verify(spot, never()).placeOrder(anyString(), anyString(), anyString(), anyString(), anyString(), anyString());
    }

    @Test void absolute_price_floor_skips_below_floor_levels() {
        // 地板 99.5：ladder [100.00,99.70,99.40,...] 中 <99.5 的全部不开，前两档合格
        mmProps.setGridFloorPrice(new BigDecimal("99.5"));
        when(md.depth(eq("btcusdt"), anyInt())).thenReturn(depthOk(depth("100", "100.05")));

        svc.fillGridLadder("btcusdt");

        verify(spot, times(2)).placeOrder(anyString(), anyString(), eq("buy-limit-maker"), anyString(), anyString(), anyString());
        verify(spot).placeOrder(eq("acc-1"), eq("btcusdt"), eq("buy-limit-maker"), anyString(), eq("100.00"), anyString());
        verify(spot).placeOrder(eq("acc-1"), eq("btcusdt"), eq("buy-limit-maker"), anyString(), eq("99.70"), anyString());
    }

    @Test void quoteReserveRiskPausesGridWithoutOpeningNewBuy() {
        mmProps.setMinQuoteReserve(new BigDecimal("90"));
        when(md.depth(eq("btcusdt"), anyInt())).thenReturn(depthOk(depth("100", "100.05")));
        when(account.getAccountBalance("acc-1")).thenReturn(balanceOk("100"));

        svc.fillGridLadder("btcusdt");

        assertEquals(MarketMakerRiskCoordinator.PAUSED_RISK, windowStore.find("btcusdt").getState());
        verify(recorder, never()).createMmCell(anyString(), any(), any(), any(), anyString());
        verify(spot, never()).placeOrder(anyString(), anyString(), anyString(), anyString(), anyString(), anyString());
    }

    @Test void baseInventoryCapRiskPausesGridWithoutOpeningNewBuy() {
        mmProps.setMaxBaseInventory(new BigDecimal("0.10"));
        when(md.depth(eq("btcusdt"), anyInt())).thenReturn(depthOk(depth("100", "100.05")));
        when(account.getAccountBalance("acc-1")).thenReturn(balanceOk("100000", "0.09"));

        svc.fillGridLadder("btcusdt");

        assertEquals(MarketMakerRiskCoordinator.PAUSED_RISK, windowStore.find("btcusdt").getState());
        verify(recorder, never()).createMmCell(anyString(), any(), any(), any(), anyString());
        verify(spot, never()).placeOrder(anyString(), anyString(), anyString(), anyString(), anyString(), anyString());
    }

    @Test void balanceReadFailureEntersRecoveringWithoutOpeningNewBuy() {
        mmProps.setMinQuoteReserve(BigDecimal.ONE);
        HtxRestResponse<HtxAccount> failed = new HtxRestResponse<>();
        failed.setStatus("error");
        failed.setErrCode("timeout");
        when(account.getAccountBalance("acc-1")).thenReturn(failed);
        when(md.depth(eq("btcusdt"), anyInt())).thenReturn(depthOk(depth("100", "100.05")));

        svc.fillGridLadder("btcusdt");

        assertEquals(MarketMakerRiskCoordinator.RECOVERING, windowStore.find("btcusdt").getState());
        verify(recorder, never()).createMmCell(anyString(), any(), any(), any(), anyString());
        verify(spot, never()).placeOrder(anyString(), anyString(), anyString(), anyString(), anyString(), anyString());
    }

    @Test void confirmedLowerBreachMovesWindowDownWithoutStopping() {
        mmProps.setGridMigrationConfirmations(1);
        mmProps.setGridMigrationCooldownMs(0);
        when(md.depth(eq("btcusdt"), anyInt()))
                .thenReturn(depthOk(depth("100", "100.05")))
                .thenReturn(depthOk(depth("95", "95.05")));

        svc.fillGridLadder("btcusdt");
        long firstEpoch = windowStore.find("btcusdt").getEpoch();
        svc.fillGridLadder("btcusdt");

        assertTrue(windowStore.find("btcusdt").getEpoch() > firstEpoch);
        assertEquals(MovingGridEngine.MIGRATING_DOWN, windowStore.find("btcusdt").getState());
        BigDecimal lowerTrigger = windowStore.find("btcusdt").getLowerPrice()
                .multiply(BigDecimal.ONE.subtract(mmProps.getGridMigrationHysteresisPct()));
        assertTrue(lowerTrigger.compareTo(new BigDecimal("95")) <= 0);
    }

    @Test void migrationCancelsOnlyOutOfWindowBuyAndPreservesSellInventory() {
        mmProps.setGridMigrationConfirmations(1);
        mmProps.setGridMigrationCooldownMs(0);
        MovingGridEngine engine = new MovingGridEngine();
        windowStore.save(engine.initial("btcusdt", new BigDecimal("100"),
                mmProps.getGridStepPct(), mmProps.getGridLevels(), LocalDateTime.now()));
        HtxLockedPairEntity oldBuy = restingBuy(21L, 501L, "90.00", "0.2");
        HtxLockedPairEntity sellInventory = restingBuy(22L, 502L, "90.00", "0.2");
        sellInventory.setStatus(HtxStatus.MM_RESTING_SELL);
        sellInventory.setSellOrderId(601L);
        when(recorder.findOpenMmCells("btcusdt")).thenReturn(List.of(oldBuy, sellInventory));
        when(md.depth(eq("btcusdt"), anyInt())).thenReturn(depthOk(depth("110", "110.05")));
        when(spot.cancelOrder(501L)).thenReturn(placeOk("501"));
        when(spot.queryOrder(501L)).thenReturn(order("canceled", "0"));

        svc.fillGridLadder("btcusdt");

        verify(spot).cancelOrder(501L);
        verify(spot, never()).cancelOrder(601L);
        verify(recorder).markMmCancelledBuy(21L);
    }

    @Test void gridSellFollowRepricesLegacyInventoryToCurrentAskWhenWindowIsStale() {
        mmProps.setSellFollowEnabled(true);
        mmProps.setGridSellFollowWindowEnabled(true);
        mmProps.setGridStepPct(new BigDecimal("0.001"));
        mmProps.setGridLevels(5);
        mmProps.setSellRepriceThresholdPct(new BigDecimal("0.0001"));
        windowStore.save(new MovingGridEngine().initial("btcusdt", new BigDecimal("60000"),
                mmProps.getGridStepPct(), mmProps.getGridLevels(), LocalDateTime.now()));
        when(spot.queryOrder(777L)).thenReturn(order("submitted", "0"))
                .thenReturn(order("canceled", "0"));
        when(spot.cancelOrder(777L)).thenReturn(placeOk("777"));
        when(md.depth(eq("btcusdt"), anyInt())).thenReturn(depthOk(depth("59590", "59600")));
        when(spot.placeOrder(eq("acc-1"), eq("btcusdt"), eq("sell-limit-maker"),
                anyString(), eq("59600.00"), anyString())).thenReturn(placeOk("888"));

        svc.advanceCell(restingSellCell("59700.00", "60300.00"));

        verify(spot).cancelOrder(777L);
        verify(spot).placeOrder(eq("acc-1"), eq("btcusdt"), eq("sell-limit-maker"),
                anyString(), eq("59600.00"), anyString());
        verify(recorder).markMmSellReprice(eq(100L), eq(888L),
                eq(new BigDecimal("59600.00")), eq(BigDecimal.ZERO));
    }

    @Test void gridSellFollowUsesNearestWindowLevelAboveAsk() {
        mmProps.setSellFollowEnabled(true);
        mmProps.setGridSellFollowWindowEnabled(true);
        mmProps.setGridStepPct(new BigDecimal("0.001"));
        mmProps.setGridLevels(5);
        mmProps.setSellRepriceThresholdPct(new BigDecimal("0.0001"));
        windowStore.save(new MovingGridEngine().initial("btcusdt", new BigDecimal("60000"),
                mmProps.getGridStepPct(), mmProps.getGridLevels(), LocalDateTime.now()));
        when(spot.queryOrder(777L)).thenReturn(order("submitted", "0"))
                .thenReturn(order("canceled", "0"));
        when(spot.cancelOrder(777L)).thenReturn(placeOk("777"));
        when(md.depth(eq("btcusdt"), anyInt())).thenReturn(depthOk(depth("59860", "59870")));
        when(spot.placeOrder(eq("acc-1"), eq("btcusdt"), eq("sell-limit-maker"),
                anyString(), eq("59880.06"), anyString())).thenReturn(placeOk("888"));

        svc.advanceCell(restingSellCell("61000.00", "61500.00"));

        verify(spot).placeOrder(eq("acc-1"), eq("btcusdt"), eq("sell-limit-maker"),
                anyString(), eq("59880.06"), anyString());
        verify(recorder).markMmSellReprice(eq(100L), eq(888L),
                eq(new BigDecimal("59880.06")), eq(BigDecimal.ZERO));
    }

    @Test void gridSellFollowDeduplicatesNearCurrentWindowInsteadOfBreakEvenFloor() {
        mmProps.setSellFollowEnabled(true);
        mmProps.setGridSellFollowWindowEnabled(true);
        mmProps.setGridStepPct(new BigDecimal("0.001"));
        mmProps.setGridLevels(5);
        mmProps.setMinGapPct(new BigDecimal("0.001"));
        mmProps.setSellRepriceThresholdPct(new BigDecimal("0.0001"));
        windowStore.save(new MovingGridEngine().initial("btcusdt", new BigDecimal("60000"),
                mmProps.getGridStepPct(), mmProps.getGridLevels(), LocalDateTime.now()));
        when(recorder.findOpenMmCells("btcusdt")).thenReturn(List.of(restingSell(200L, "59880.06")));
        when(spot.queryOrder(777L)).thenReturn(order("submitted", "0"))
                .thenReturn(order("canceled", "0"));
        when(spot.cancelOrder(777L)).thenReturn(placeOk("777"));
        when(md.depth(eq("btcusdt"), anyInt())).thenReturn(depthOk(depth("59860", "59870")));
        when(spot.placeOrder(eq("acc-1"), eq("btcusdt"), eq("sell-limit-maker"),
                anyString(), eq("59940.00"), anyString())).thenReturn(placeOk("888"));

        svc.advanceCell(restingSellCell("61000.00", "61500.00"));

        verify(spot).placeOrder(eq("acc-1"), eq("btcusdt"), eq("sell-limit-maker"),
                anyString(), eq("59940.00"), anyString());
        verify(recorder).markMmSellReprice(eq(100L), eq(888L),
                eq(new BigDecimal("59940.00")), eq(BigDecimal.ZERO));
    }

    @Test void gridSellFollowDedupDoesNotMoveAboveWindowUpper() {
        mmProps.setSellFollowEnabled(true);
        mmProps.setGridSellFollowWindowEnabled(true);
        mmProps.setGridStepPct(new BigDecimal("0.001"));
        mmProps.setGridLevels(5);
        mmProps.setMinGapPct(new BigDecimal("0.001"));
        mmProps.setSellRepriceThresholdPct(new BigDecimal("0.0001"));
        windowStore.save(new MovingGridEngine().initial("btcusdt", new BigDecimal("60000"),
                mmProps.getGridStepPct(), mmProps.getGridLevels(), LocalDateTime.now()));
        when(recorder.findOpenMmCells("btcusdt")).thenReturn(List.of(
                restingSell(200L, "59880.06"),
                restingSell(201L, "59939.95"),
                restingSell(202L, "59999.84"),
                restingSell(203L, "60080.00")));
        when(spot.queryOrder(777L)).thenReturn(order("submitted", "0"))
                .thenReturn(order("canceled", "0"));
        when(spot.cancelOrder(777L)).thenReturn(placeOk("777"));
        when(md.depth(eq("btcusdt"), anyInt())).thenReturn(depthOk(depth("59860", "59870")));
        when(spot.placeOrder(eq("acc-1"), eq("btcusdt"), eq("sell-limit-maker"),
                anyString(), eq("59940.00"), anyString())).thenReturn(placeOk("888"));

        svc.advanceCell(restingSellCell("61000.00", "61500.00"));

        verify(spot).placeOrder(eq("acc-1"), eq("btcusdt"), eq("sell-limit-maker"),
                anyString(), eq("59940.00"), anyString());
        verify(recorder).markMmSellReprice(eq(100L), eq(888L),
                eq(new BigDecimal("59940.00")), eq(BigDecimal.ZERO));
    }


    @Test void gridSellFollowUsesNextOpenGridLevelInsteadOfWindowCeilingWhenLowerLevelsOccupied() {
        mmProps.setSellFollowEnabled(true);
        mmProps.setGridSellFollowWindowEnabled(true);
        mmProps.setGridStepPct(new BigDecimal("0.001"));
        mmProps.setGridLevels(20);
        mmProps.setMinGapPct(new BigDecimal("0.0009"));
        mmProps.setSellRepriceThresholdPct(new BigDecimal("0.0001"));
        windowStore.save(new MovingGridEngine().initial("btcusdt", new BigDecimal("4396.57"),
                mmProps.getGridStepPct(), mmProps.getGridLevels(), LocalDateTime.now()));
        when(recorder.findOpenMmCells("btcusdt")).thenReturn(List.of(
                restingSell(200L, "4344.10"),
                restingSell(201L, "4348.45"),
                restingSell(202L, "4396.57")));
        when(spot.queryOrder(777L)).thenReturn(order("submitted", "0"))
                .thenReturn(order("canceled", "0"));
        when(spot.cancelOrder(777L)).thenReturn(placeOk("777"));
        when(md.depth(eq("btcusdt"), anyInt())).thenReturn(depthOk(depth("4343.80", "4344.10")));
        when(spot.placeOrder(eq("acc-1"), eq("btcusdt"), eq("sell-limit-maker"),
                anyString(), eq("4352.80"), anyString())).thenReturn(placeOk("888"));

        svc.advanceCell(restingSellCell("4480.94", "4498.90"));

        verify(spot).placeOrder(eq("acc-1"), eq("btcusdt"), eq("sell-limit-maker"),
                anyString(), eq("4352.80"), anyString());
        verify(recorder).markMmSellReprice(eq(100L), eq(888L),
                eq(new BigDecimal("4352.80")), eq(BigDecimal.ZERO));
    }

    @Test void retryableGridSellWithoutOrderIdFetchesCurrentAskBeforeResubmitting() {
        mmProps.setSellFollowEnabled(true);
        mmProps.setGridSellFollowWindowEnabled(true);
        mmProps.setGridStepPct(new BigDecimal("0.001"));
        mmProps.setGridLevels(5);
        windowStore.save(new MovingGridEngine().initial("btcusdt", new BigDecimal("60000"),
                mmProps.getGridStepPct(), mmProps.getGridLevels(), LocalDateTime.now()));
        when(md.depth(eq("btcusdt"), anyInt())).thenReturn(depthOk(depth("59590", "59600")));
        when(spot.placeOrder(eq("acc-1"), eq("btcusdt"), eq("sell-limit-maker"),
                eq("0.000300"), eq("59600.00"), anyString())).thenReturn(placeOk("889"));

        svc.advanceCell(retryableSellCellWithoutOrder("61000.00", "61500.00"));

        verify(spot).placeOrder(eq("acc-1"), eq("btcusdt"), eq("sell-limit-maker"),
                eq("0.000300"), eq("59600.00"), anyString());
        verify(recorder).markMmSellReprice(eq(101L), eq(889L),
                eq(new BigDecimal("59600.00")), eq(BigDecimal.ZERO));
    }

    private HtxRestResponse<HtxOrder> order(String state, String filled) {
        HtxOrder order = new HtxOrder();
        order.setState(state);
        order.setFilledAmount(new BigDecimal(filled));
        HtxRestResponse<HtxOrder> response = new HtxRestResponse<>();
        response.setStatus("ok");
        response.setData(order);
        return response;
    }
}
