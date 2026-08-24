package com.metaverse.coin.strategy.htx.strategy;

import com.metaverse.coin.strategy.common.client.htx.HtxApiService;
import com.metaverse.coin.strategy.common.client.htx.model.HtxAccount;
import com.metaverse.coin.strategy.common.client.htx.model.HtxBalance;
import com.metaverse.coin.strategy.common.client.htx.model.HtxDepth;
import com.metaverse.coin.strategy.common.client.htx.model.HtxOrder;
import com.metaverse.coin.strategy.common.client.htx.model.HtxPriceLevel;
import com.metaverse.coin.strategy.common.client.htx.model.HtxRestResponse;
import com.metaverse.coin.strategy.common.client.htx.rest.HtxMarketDataService;
import com.metaverse.coin.strategy.common.client.htx.rest.HtxAccountService;
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
    HtxAccountService account;
    HtxTradeProperties tradeProps;
    HtxMarketMakerProperties mmProps;
    HtxTradeRecorder recorder;
    MarketMakerService svc;

    @BeforeEach void setup() {
        api = mock(HtxApiService.class);
        spot = mock(HtxSpotService.class);
        md = mock(HtxMarketDataService.class);
        account = mock(HtxAccountService.class);
        when(api.getSpotService()).thenReturn(spot);
        when(api.getMarketDataService()).thenReturn(md);
        when(api.getAccountService()).thenReturn(account);
        tradeProps = new HtxTradeProperties();
        tradeProps.getApi().setSpotAccountId("acc-1");
        tradeProps.getTrade().setEnabled(true);
        mmProps = new HtxMarketMakerProperties();
        mmProps.setEnabled(true);
        mmProps.setSellFollowEnabled(false);   // 本类既有卖腿用例锁 legacy；follow 新行为在 Task5/6 新测显式开启
        recorder = mock(HtxTradeRecorder.class);
        when(account.getAccountBalance("acc-1")).thenReturn(balanceOk("1000"));
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

    private HtxRestResponse<HtxOrder> orderOkWithCash(String state, String filled, String fees,
                                                       String amount, String filledCash) {
        HtxRestResponse<HtxOrder> response = orderOk(state, filled, fees, amount);
        if (filledCash != null) response.getData().setFilledCashAmount(new BigDecimal(filledCash));
        return response;
    }

    private HtxRestResponse<String> placeOk(String id) {
        HtxRestResponse<String> r = new HtxRestResponse<>();
        r.setStatus("ok");
        r.setData(id);
        return r;
    }

    private HtxRestResponse<HtxAccount> balanceOk(String availableUsdt) {
        HtxBalance balance = new HtxBalance();
        balance.setCurrency("usdt");
        balance.setType("trade");
        balance.setBalance(new BigDecimal(availableUsdt));
        HtxAccount data = new HtxAccount();
        data.setList(List.of(balance));
        HtxRestResponse<HtxAccount> response = new HtxRestResponse<>();
        response.setStatus("ok");
        response.setData(data);
        return response;
    }

    @Test void resting_buy_filled_places_sell_and_marks_resting() {
        when(spot.queryOrder(555L)).thenReturn(orderOk("filled", "0.0003", "0.0000006", null));
        when(spot.placeOrder(eq("acc-1"), eq("btcusdt"), eq("sell-limit-maker"), anyString(), eq("60058.20"), eq("mm-btcusdt-1-SELL")))
                .thenReturn(placeOk("777"));
        svc.advanceCell(buyCell(555L, LocalDateTime.now()));
        verify(spot).placeOrder(eq("acc-1"), eq("btcusdt"), eq("sell-limit-maker"), eq("0.000299"), eq("60058.20"), eq("mm-btcusdt-1-SELL"));
        verify(recorder).markMmSellPending(eq(100L), any(), any(),
                eq(new BigDecimal("59700.00")), eq(new BigDecimal("60058.20")));
        verify(recorder).markMmSellResting(100L, 777L);
    }

    @Test void restingBuyFilledPricesSellFromActualAverageCost() {
        when(spot.queryOrder(555L)).thenReturn(orderOkWithCash(
                "filled", "0.0003", "0.0000006", null, "17.91"));
        when(spot.placeOrder(eq("acc-1"), eq("btcusdt"), eq("sell-limit-maker"),
                anyString(), eq("60058.20"), eq("mm-btcusdt-1-SELL")))
                .thenReturn(placeOk("777"));

        svc.advanceCell(buyCell(555L, LocalDateTime.now()));

        verify(recorder).markMmSellPending(eq(100L), eq(new BigDecimal("0.0003")),
                eq(new BigDecimal("0.0000006")), eq(new BigDecimal("59700.00")),
                eq(new BigDecimal("60058.20")));
        verify(spot).placeOrder(eq("acc-1"), eq("btcusdt"), eq("sell-limit-maker"),
                anyString(), eq("60058.20"), eq("mm-btcusdt-1-SELL"));
    }

    @Test void ethSellAmountIsAlwaysRoundedDownAfterBaseFee() {
        MarketMakerCell cell = MarketMakerCell.builder()
                .pairId(101L).clientId("mm-ethusdt-1").symbol("ethusdt")
                .buyPrice(new BigDecimal("1896.09")).sellPrice(new BigDecimal("1907.47"))
                .qty(new BigDecimal("0.0105")).state(HtxStatus.MM_RESTING_BUY)
                .buyOrderId(556L).createdAt(LocalDateTime.now()).build();
        when(spot.queryOrder(556L)).thenReturn(orderOkWithCash(
                "filled", "0.0105", "0.000021", null, "19.908945"));
        when(spot.placeOrder(eq("acc-1"), eq("ethusdt"), eq("sell-limit-maker"),
                eq("0.0104"), anyString(), eq("mm-ethusdt-1-SELL")))
                .thenReturn(placeOk("778"));

        svc.advanceCell(cell);

        verify(spot).placeOrder(eq("acc-1"), eq("ethusdt"), eq("sell-limit-maker"),
                eq("0.0104"), anyString(), eq("mm-ethusdt-1-SELL"));
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
        when(spot.placeOrder(anyString(), anyString(), eq("sell-limit-maker"), anyString(), anyString(), anyString()))
                .thenReturn(placeOk("777"));
        svc.advanceCell(buyCell(555L, LocalDateTime.now().minusHours(2)));
        verify(spot).placeOrder(anyString(), anyString(), eq("sell-limit-maker"), anyString(), anyString(), anyString());
        verify(recorder).markMmSellResting(100L, 777L);
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

    @Test void sellRejectedAfterBuyFillStaysPendingForLimitRetry() {
        when(spot.queryOrder(555L)).thenReturn(orderOk("filled", "0.0003", "0.0000006", null));
        HtxRestResponse<String> err = new HtxRestResponse<>();
        err.setStatus("error");
        err.setErrMsg("sell-rejected");
        when(spot.placeOrder(anyString(), anyString(), eq("sell-limit-maker"), anyString(), anyString(), anyString()))
                .thenReturn(err);
        svc.advanceCell(buyCell(555L, LocalDateTime.now()));
        verify(recorder).markMmSellPending(eq(100L), any(), any(),
                eq(new BigDecimal("59700.00")), eq(new BigDecimal("60058.20")));
        verify(recorder, never()).recordExposure(anyLong(), anyString(), anyString(), any(), anyString());
        verify(recorder, never()).markMmCancelledBuy(anyLong());
        verify(recorder, never()).markMmSellResting(anyLong(), anyLong());
    }

    @Test void pendingSellWithoutOrderIdRetriesSameLimitOrder() {
        MarketMakerCell pending = sellCell(null);
        pending.setBuyFilledAmount(new BigDecimal("0.0003"));
        pending.setTotalFeeBase(new BigDecimal("0.0000006"));
        when(spot.placeOrder(eq("acc-1"), eq("btcusdt"), eq("sell-limit-maker"),
                anyString(), eq("60300.00"), eq("mm-btcusdt-1-SELL")))
                .thenReturn(placeOk("777"));

        svc.advanceCell(pending);

        verify(recorder).markMmSellResting(100L, 777L);
        verify(recorder, never()).recordExposure(anyLong(), anyString(), anyString(), any(), anyString());
    }

    @Test void pendingSellBelowMinimumNotionalBecomesDustWithoutOrder() {
        MarketMakerCell pending = sellCell(null);
        pending.setBuyFilledAmount(new BigDecimal("0.000010"));
        pending.setTotalFeeBase(BigDecimal.ZERO);

        svc.advanceCell(pending);

        verify(recorder).markMmDust(eq(100L), eq("btcusdt"), eq(new BigDecimal("0.000010")),
                eq(new BigDecimal("0.60300000")), eq(new BigDecimal("1.1")));
        verify(spot, never()).placeOrder(anyString(), anyString(), anyString(), anyString(), anyString(), anyString());
        verify(recorder, never()).markMmSellResting(anyLong(), anyLong());
    }

    @Test void pendingSellAtMinimumNotionalPlacesOrder() {
        mmProps.setMinSellOrderQuoteNotional(new BigDecimal("5.9939520000"));
        MarketMakerCell pending = sellCell(null);
        pending.setBuyFilledAmount(new BigDecimal("0.0001"));
        pending.setTotalFeeBase(BigDecimal.ZERO);
        pending.setSellPrice(new BigDecimal("10000"));
        when(spot.placeOrder(eq("acc-1"), eq("btcusdt"), eq("sell-limit-maker"),
                eq("0.000100"), eq("59939.52"), eq("mm-btcusdt-1-SELL")))
                .thenReturn(placeOk("779"));

        svc.advanceCell(pending);

        verify(spot).placeOrder(eq("acc-1"), eq("btcusdt"), eq("sell-limit-maker"),
                eq("0.000100"), eq("59939.52"), eq("mm-btcusdt-1-SELL"));
        verify(recorder).markMmSellResting(100L, 779L);
        verify(recorder, never()).markMmDust(anyLong(), anyString(), any(), any(), any());
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
        verify(recorder).recordExposure(eq(100L), eq("btcusdt"), eq(HtxStatus.EXPOSED_SIDE_BASE_LONG), any(), anyString());
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
        when(spot.placeOrder(eq("acc-1"), eq("btcusdt"), eq("buy-limit-maker"), anyString(), eq("60000.00"), anyString()))
                .thenReturn(placeOk("999"));

        svc.advanceCell(buyCell(555L, LocalDateTime.now()));

        verify(spot).cancelOrder(555L);
        verify(spot).placeOrder(eq("acc-1"), eq("btcusdt"), eq("buy-limit-maker"), anyString(), eq("60000.00"), anyString());
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
        when(spot.placeOrder(anyString(), anyString(), eq("sell-limit-maker"), anyString(), anyString(), anyString()))
                .thenReturn(placeOk("777"));

        svc.advanceCell(buyCell(555L, LocalDateTime.now()));

        verify(spot).placeOrder(anyString(), anyString(), eq("sell-limit-maker"), anyString(), anyString(), anyString());
        verify(recorder).markMmSellResting(100L, 777L);
        verify(recorder, never()).markMmReprice(anyLong(), anyLong(), any(), any());
        verify(spot, never()).placeOrder(anyString(), anyString(), eq("buy-limit-maker"), anyString(), anyString(), anyString());
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
        verify(spot, never()).placeOrder(anyString(), anyString(), eq("buy-limit-maker"), anyString(), anyString(), anyString());
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
        when(spot.placeOrder(eq("acc-1"), eq("btcusdt"), eq("buy-limit-maker"), anyString(), anyString(), anyString()))
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

    private HtxRestResponse<HtxDepth> depthBidAsk(String bid, String ask) {
        HtxDepth d = new HtxDepth();
        d.setBids(List.of(new HtxPriceLevel(new BigDecimal(bid), new BigDecimal("10"))));
        d.setAsks(List.of(new HtxPriceLevel(new BigDecimal(ask), new BigDecimal("10"))));
        HtxRestResponse<HtxDepth> r = new HtxRestResponse<>();
        r.setStatus("ok");
        r.setTick(d);
        return r;
    }

    private MarketMakerCell restingSellCell(Long sellId, String buy, String sell) {
        MarketMakerCell c = MarketMakerCell.builder()
                .pairId(100L).clientId("mm-btcusdt-1").symbol("btcusdt")
                .buyPrice(new BigDecimal(buy)).sellPrice(new BigDecimal(sell))
                .qty(new BigDecimal("0.0003")).state(HtxStatus.MM_RESTING_SELL)
                .buyOrderId(555L).sellOrderId(sellId).createdAt(LocalDateTime.now()).build();
        c.setBuyFilledAmount(new BigDecimal("0.0003"));
        c.setTotalFeeBase(new BigDecimal("0.0000006"));
        return c;
    }

    @Test void sellFollow_up_reprices_to_new_ask_maker() {
        mmProps.setSellFollowEnabled(true);
        mmProps.setSellRepriceThresholdPct(new BigDecimal("0.001"));
        // 当前挂卖 60300；盘口 ask=60800 > buy=59700 → desiredSell=60800.00，漂移超阈值 → 重挂
        when(spot.queryOrder(777L)).thenReturn(orderOk("submitted", "0", null, "0.0003"))
                .thenReturn(orderOk("canceled", "0", null, "0.0003")); // 撤单复查终态无成交
        when(spot.cancelOrder(777L)).thenReturn(placeOk("777"));
        when(md.depth(eq("btcusdt"), anyInt())).thenReturn(depthBidAsk("60780", "60800"));
        when(spot.placeOrder(eq("acc-1"), eq("btcusdt"), eq("sell-limit-maker"), anyString(), eq("60800.00"), anyString()))
                .thenReturn(placeOk("888"));

        svc.advanceCell(restingSellCell(777L, "59700.00", "60300.00"));

        verify(spot).cancelOrder(777L);
        verify(spot).placeOrder(eq("acc-1"), eq("btcusdt"), eq("sell-limit-maker"), anyString(), eq("60800.00"), anyString());
        verify(recorder).markMmSellReprice(eq(100L), eq(888L),
                eq(new BigDecimal("60800.00")), eq(BigDecimal.ZERO));
    }

    @Test void sellFollow_reprice_after_partial_fill_places_only_remaining_inventory() {
        mmProps.setSellFollowEnabled(true);
        mmProps.setSellRepriceThresholdPct(new BigDecimal("0.001"));
        when(spot.queryOrder(777L)).thenReturn(orderOk("submitted", "0", null, "0.000299"))
                .thenReturn(orderOk("canceled", "0.000100", null, "0.000299"));
        when(spot.cancelOrder(777L)).thenReturn(placeOk("777"));
        when(md.depth(eq("btcusdt"), anyInt())).thenReturn(depthBidAsk("60780", "60800"));
        when(spot.placeOrder(eq("acc-1"), eq("btcusdt"), eq("sell-limit-maker"),
                eq("0.000199"), eq("60800.00"), anyString())).thenReturn(placeOk("888"));

        svc.advanceCell(restingSellCell(777L, "59700.00", "60300.00"));

        verify(spot).placeOrder(eq("acc-1"), eq("btcusdt"), eq("sell-limit-maker"),
                eq("0.000199"), eq("60800.00"), anyString());
        verify(recorder).markMmSellReprice(eq(100L), eq(888L),
                eq(new BigDecimal("60800.00")), eq(new BigDecimal("0.000100")));
    }

    @Test void sellFollow_milddown_reprices_to_break_even_floor() {
        mmProps.setSellFollowEnabled(true);
        mmProps.setSellRepriceThresholdPct(new BigDecimal("0.001"));
        // ask=59600 < buy=59700 → 含费保本地板 59939.52；当前挂 60300 → 重挂到保本地板
        when(spot.queryOrder(777L)).thenReturn(orderOk("submitted", "0", null, "0.0003"))
                .thenReturn(orderOk("canceled", "0", null, "0.0003"));
        when(spot.cancelOrder(777L)).thenReturn(placeOk("777"));
        when(md.depth(eq("btcusdt"), anyInt())).thenReturn(depthBidAsk("59650", "59600"));
        when(spot.placeOrder(eq("acc-1"), eq("btcusdt"), eq("sell-limit-maker"), anyString(), eq("59939.52"), anyString()))
                .thenReturn(placeOk("888"));

        svc.advanceCell(restingSellCell(777L, "59700.00", "60300.00"));

        verify(spot).placeOrder(eq("acc-1"), eq("btcusdt"), eq("sell-limit-maker"), anyString(), eq("59939.52"), anyString());
        verify(recorder).markMmSellReprice(eq(100L), eq(888L),
                eq(new BigDecimal("59939.52")), eq(BigDecimal.ZERO));
    }

    @Test void sellFollow_within_threshold_is_noop() {
        mmProps.setSellFollowEnabled(true);
        mmProps.setSellRepriceThresholdPct(new BigDecimal("0.001"));
        // 当前挂 60300；ask=60310 → desiredSell=60310，|60310-60300|/60300=0.000166 < 0.001 → noop
        when(spot.queryOrder(777L)).thenReturn(orderOk("submitted", "0", null, "0.0003"));
        when(md.depth(eq("btcusdt"), anyInt())).thenReturn(depthBidAsk("60290", "60310"));

        svc.advanceCell(restingSellCell(777L, "59700.00", "60300.00"));

        verify(spot, never()).cancelOrder(anyLong());
        verify(spot, never()).placeOrder(anyString(), anyString(), anyString(), anyString(), anyString(), anyString());
        verify(recorder, never()).markMmSellReprice(anyLong(), anyLong(), any(), any());
    }

    @Test void sellFollow_reprice_cancel_race_filled_marks_done() {
        mmProps.setSellFollowEnabled(true);
        mmProps.setSellRepriceThresholdPct(new BigDecimal("0.001"));
        when(spot.queryOrder(777L)).thenReturn(orderOk("submitted", "0", null, "0.0003"))
                .thenReturn(orderOk("filled", "0.0003", null, "0.0003")); // 撤单竞态：已成交
        when(spot.cancelOrder(777L)).thenReturn(placeOk("777"));
        when(md.depth(eq("btcusdt"), anyInt())).thenReturn(depthBidAsk("60780", "60800"));

        svc.advanceCell(restingSellCell(777L, "59700.00", "60300.00"));

        verify(recorder).markMmDone(eq(100L), any());
        verify(spot, never()).placeOrder(anyString(), anyString(), eq("sell-limit-maker"), anyString(), anyString(), anyString());
        verify(recorder, never()).markMmSellReprice(anyLong(), anyLong(), any(), any());
    }

    @Test void sellFollow_reprice_cancel_not_settled_defers() {
        mmProps.setSellFollowEnabled(true);
        mmProps.setSellRepriceThresholdPct(new BigDecimal("0.001"));
        when(spot.queryOrder(777L)).thenReturn(orderOk("submitted", "0", null, "0.0003"))
                .thenReturn(orderOk("canceling", "0", null, "0.0003")); // 未终态、无成交 → defer
        when(spot.cancelOrder(777L)).thenReturn(placeOk("777"));
        when(md.depth(eq("btcusdt"), anyInt())).thenReturn(depthBidAsk("60780", "60800"));

        svc.advanceCell(restingSellCell(777L, "59700.00", "60300.00"));

        verify(spot).cancelOrder(777L);
        verify(spot, never()).placeOrder(anyString(), anyString(), eq("sell-limit-maker"), anyString(), anyString(), anyString());
        verify(recorder, never()).markMmSellReprice(anyLong(), anyLong(), any(), any());
        verify(recorder, never()).markMmDone(anyLong(), any());
    }

    @Test void sellFollow_disabled_holds_to_fill_no_depth_fetch() {
        mmProps.setSellFollowEnabled(false);
        when(spot.queryOrder(777L)).thenReturn(orderOk("submitted", "0", null, "0.0003"));

        svc.advanceCell(restingSellCell(777L, "59700.00", "60300.00"));

        verify(spot, never()).cancelOrder(anyLong());
        verify(spot, never()).placeOrder(anyString(), anyString(), anyString(), anyString(), anyString(), anyString());
        verify(md, never()).depth(anyString(), anyInt());   // 关闭时连深度都不取
    }

    @Test void sellFollow_stopLoss_flattens_via_market_taker() {
        mmProps.setSellFollowEnabled(true);
        mmProps.setStopLossEnabled(true);
        mmProps.setStopLossPct(new BigDecimal("0.005"));
        // 止损线=59700*(1-0.005)=59401.5；bestBid=59300 ≤ 线 → 割肉
        when(spot.queryOrder(777L)).thenReturn(orderOk("submitted", "0", null, "0.0003"))
                .thenReturn(orderOk("canceled", "0", null, "0.0003")); // 撤单终态无成交
        when(spot.cancelOrder(777L)).thenReturn(placeOk("777"));
        when(md.depth(eq("btcusdt"), anyInt())).thenReturn(depthBidAsk("59300", "59320"));
        // sellable = 0.0003 - 0.0000006 = 0.0002994 → btc scale6 DOWN = 0.000299
        when(spot.placeOrder(eq("acc-1"), eq("btcusdt"), eq("sell-market"), eq("0.000299"), isNull(), anyString()))
                .thenReturn(placeOk("999"));

        svc.advanceCell(restingSellCell(777L, "59700.00", "60300.00"));

        verify(spot).cancelOrder(777L);
        verify(spot).placeOrder(eq("acc-1"), eq("btcusdt"), eq("sell-market"), eq("0.000299"), isNull(), anyString());
        verify(recorder).markMmSellReprice(eq(100L), eq(999L), eq(new BigDecimal("60300.00")),
                eq(BigDecimal.ZERO));
        verify(recorder).updatePairStatus(eq(100L), eq(HtxStatus.MM_RESTING_SELL),
                eq("MM_STOP_LOSS"), anyString());
        verify(recorder, never()).markMmDone(anyLong(), any());
        verify(spot, never()).placeOrder(anyString(), anyString(), eq("sell-limit-maker"), anyString(), anyString(), anyString());
    }

    @Test void sellFollow_stopLoss_cancel_race_filled_marks_done_no_market_sell() {
        mmProps.setSellFollowEnabled(true);
        mmProps.setStopLossEnabled(true);
        mmProps.setStopLossPct(new BigDecimal("0.005"));
        when(spot.queryOrder(777L)).thenReturn(orderOk("submitted", "0", null, "0.0003"))
                .thenReturn(orderOk("filled", "0.0003", null, "0.0003")); // 撤单竞态：已成交
        when(spot.cancelOrder(777L)).thenReturn(placeOk("777"));
        when(md.depth(eq("btcusdt"), anyInt())).thenReturn(depthBidAsk("59300", "59320"));

        svc.advanceCell(restingSellCell(777L, "59700.00", "60300.00"));

        verify(recorder).markMmDone(eq(100L), any());
        verify(spot, never()).placeOrder(anyString(), anyString(), eq("sell-market"), anyString(), any(), anyString());
        verify(recorder, never()).updatePairStatus(anyLong(), any(), eq("MM_STOP_LOSS"), anyString());
    }

    @Test void sellFollow_stopLoss_cancel_not_settled_defers_no_market_sell() {
        mmProps.setSellFollowEnabled(true);
        mmProps.setStopLossEnabled(true);
        mmProps.setStopLossPct(new BigDecimal("0.005"));
        when(spot.queryOrder(777L)).thenReturn(orderOk("submitted", "0", null, "0.0003"))
                .thenReturn(orderOk("canceling", "0", null, "0.0003")); // 未终态 → defer
        when(spot.cancelOrder(777L)).thenReturn(placeOk("777"));
        when(md.depth(eq("btcusdt"), anyInt())).thenReturn(depthBidAsk("59300", "59320"));

        svc.advanceCell(restingSellCell(777L, "59700.00", "60300.00"));

        verify(spot).cancelOrder(777L);
        verify(spot, never()).placeOrder(anyString(), anyString(), eq("sell-market"), anyString(), any(), anyString());
        verify(recorder, never()).markMmDone(anyLong(), any());
        verify(recorder, never()).updatePairStatus(anyLong(), any(), eq("MM_STOP_LOSS"), anyString());
    }
}
