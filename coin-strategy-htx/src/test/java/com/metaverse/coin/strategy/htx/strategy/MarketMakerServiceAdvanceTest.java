package com.metaverse.coin.strategy.htx.strategy;

import com.metaverse.coin.strategy.common.client.htx.HtxApiService;
import com.metaverse.coin.strategy.common.client.htx.model.HtxDepth;
import com.metaverse.coin.strategy.common.client.htx.model.HtxOrder;
import com.metaverse.coin.strategy.common.client.htx.model.HtxPriceLevel;
import com.metaverse.coin.strategy.common.client.htx.model.HtxRestResponse;
import com.metaverse.coin.strategy.common.client.htx.rest.HtxMarketDataService;
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
import java.util.List;

import static org.mockito.Mockito.*;

class MarketMakerServiceAdvanceTest {

    HtxApiService api;
    HtxSpotService spot;
    HtxMarketDataService md;
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

    private HtxRestResponse<HtxDepth> depthWithBid(String bid) {
        HtxDepth d = new HtxDepth();
        d.setBids(List.of(new HtxPriceLevel(new BigDecimal(bid), new BigDecimal("10"))));
        d.setAsks(List.of(new HtxPriceLevel(new BigDecimal(bid).add(new BigDecimal("20")), new BigDecimal("10"))));
        HtxRestResponse<HtxDepth> r = new HtxRestResponse<>();
        r.setStatus("ok");
        r.setTick(d);
        return r;
    }

    @Test void resting_buy_drift_over_threshold_cancels_and_reprices_in_place() {
        mmProps.setEnabled(true);
        mmProps.setBuyTtlMs(3_600_000L);
        mmProps.setRepriceThresholdPct(new BigDecimal("0.001"));
        mmProps.setSpreadPct(new BigDecimal("0.003"));
        when(spot.queryOrder(555L)).thenReturn(orderOk("submitted", "0", null, null))
                .thenReturn(orderOk("canceled", "0", null, null));
        when(spot.cancelOrder(555L)).thenReturn(placeOk("555"));
        when(md.depth(eq("btcusdt"), anyInt())).thenReturn(depthWithBid("60000"));
        when(spot.placeOrder(eq("acc-1"), eq("btcusdt"), eq("buy-limit"), anyString(), eq("60000.00"), anyString()))
                .thenReturn(placeOk("999"));

        svc.advanceCell(buyCell(555L, LocalDateTime.now()));

        verify(spot).cancelOrder(555L);
        verify(spot).placeOrder(eq("acc-1"), eq("btcusdt"), eq("buy-limit"), anyString(), eq("60000.00"), anyString());
        verify(recorder).markMmReprice(eq(100L), eq(999L), eq(new BigDecimal("60000.00")), eq(new BigDecimal("60360.00")));
        verify(recorder, never()).markMmCancelledBuy(anyLong());
    }

    @Test void resting_buy_drift_within_threshold_is_noop() {
        mmProps.setEnabled(true);
        mmProps.setBuyTtlMs(3_600_000L);
        mmProps.setRepriceThresholdPct(new BigDecimal("0.001"));
        when(spot.queryOrder(555L)).thenReturn(orderOk("submitted", "0", null, null));
        when(md.depth(eq("btcusdt"), anyInt())).thenReturn(depthWithBid("59750"));

        svc.advanceCell(buyCell(555L, LocalDateTime.now()));

        verify(spot, never()).cancelOrder(anyLong());
        verify(recorder, never()).markMmReprice(anyLong(), anyLong(), any(), any());
        verify(spot, never()).placeOrder(anyString(), anyString(), anyString(), anyString(), anyString(), anyString());
    }

    @Test void resting_buy_reprice_cancel_race_filled_opens_sell_instead() {
        mmProps.setEnabled(true);
        mmProps.setBuyTtlMs(3_600_000L);
        mmProps.setRepriceThresholdPct(new BigDecimal("0.001"));
        when(spot.queryOrder(555L)).thenReturn(orderOk("submitted", "0", null, null))
                .thenReturn(orderOk("canceled", "0.0003", "0.0000006", null));
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

    @Test void resting_buy_reprice_cancel_not_settled_defers() {
        mmProps.setEnabled(true);
        mmProps.setBuyTtlMs(3_600_000L);
        mmProps.setRepriceThresholdPct(new BigDecimal("0.001"));
        mmProps.setSpreadPct(new BigDecimal("0.003"));
        when(spot.queryOrder(555L)).thenReturn(orderOk("submitted", "0", null, null))
                .thenReturn(orderOk("canceling", "0", null, null)); // post-cancel 未终态、无成交
        when(spot.cancelOrder(555L)).thenReturn(placeOk("555"));
        when(md.depth(eq("btcusdt"), anyInt())).thenReturn(depthWithBid("60000"));

        svc.advanceCell(buyCell(555L, LocalDateTime.now()));

        verify(spot).cancelOrder(555L);
        verify(spot, never()).placeOrder(anyString(), anyString(), eq("buy-limit"), anyString(), anyString(), anyString());
        verify(recorder, never()).markMmReprice(anyLong(), anyLong(), any(), any());
        verify(recorder, never()).markMmCancelledBuy(anyLong());
    }

    @Test void resting_buy_reprice_rejected_marks_cancelled_buy() {
        mmProps.setEnabled(true);
        mmProps.setBuyTtlMs(3_600_000L);
        mmProps.setRepriceThresholdPct(new BigDecimal("0.001"));
        mmProps.setSpreadPct(new BigDecimal("0.003"));
        when(spot.queryOrder(555L)).thenReturn(orderOk("submitted", "0", null, null))
                .thenReturn(orderOk("canceled", "0", null, null)); // post-cancel 终态、无成交
        when(spot.cancelOrder(555L)).thenReturn(placeOk("555"));
        when(md.depth(eq("btcusdt"), anyInt())).thenReturn(depthWithBid("60000"));
        HtxRestResponse<String> err = new HtxRestResponse<>();
        err.setStatus("error");
        err.setErrMsg("reprice-rejected");
        when(spot.placeOrder(eq("acc-1"), eq("btcusdt"), eq("buy-limit"), anyString(), anyString(), anyString()))
                .thenReturn(err);

        svc.advanceCell(buyCell(555L, LocalDateTime.now()));

        verify(recorder).markMmCancelledBuy(100L);
        verify(recorder, never()).markMmReprice(anyLong(), anyLong(), any(), any());
    }

    @Test void reprice_skipped_when_disabled_converges_via_ttl() {
        // 急停：enabled=false，即使漂移超阈值也不重挂；买单靠 TTL 收敛（此处 TTL 未到→noop）
        mmProps.setEnabled(false);
        mmProps.setBuyTtlMs(3_600_000L);
        mmProps.setRepriceThresholdPct(new BigDecimal("0.001"));
        when(spot.queryOrder(555L)).thenReturn(orderOk("submitted", "0", null, null));
        // depth 会漂移超阈值，但 disabled 下不应被查询/触发
        svc.advanceCell(buyCell(555L, LocalDateTime.now()));
        verify(spot, never()).cancelOrder(anyLong());
        verify(spot, never()).placeOrder(anyString(), anyString(), anyString(), anyString(), anyString(), anyString());
        verify(recorder, never()).markMmReprice(anyLong(), anyLong(), any(), any());
        verify(md, never()).depth(anyString(), anyInt()); // disabled 下连深度都不取（短路）
    }

    @Test void resting_buy_no_bestbid_is_noop() {
        mmProps.setBuyTtlMs(3_600_000L);
        when(spot.queryOrder(555L)).thenReturn(orderOk("submitted", "0", null, null));
        HtxRestResponse<HtxDepth> bad = new HtxRestResponse<>();
        bad.setStatus("error");
        when(md.depth(eq("btcusdt"), anyInt())).thenReturn(bad);

        svc.advanceCell(buyCell(555L, LocalDateTime.now()));

        verify(spot, never()).cancelOrder(anyLong());
        verify(spot, never()).placeOrder(anyString(), anyString(), anyString(), anyString(), anyString(), anyString());
        verify(recorder, never()).markMmReprice(anyLong(), anyLong(), any(), any());
        verify(recorder, never()).markMmCancelledBuy(anyLong());
    }
}
