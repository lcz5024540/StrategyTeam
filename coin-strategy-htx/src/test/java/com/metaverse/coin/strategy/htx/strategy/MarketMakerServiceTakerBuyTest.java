package com.metaverse.coin.strategy.htx.strategy;

import com.metaverse.coin.strategy.common.client.htx.HtxApiService;
import com.metaverse.coin.strategy.common.client.htx.model.HtxAccount;
import com.metaverse.coin.strategy.common.client.htx.model.HtxBalance;
import com.metaverse.coin.strategy.common.client.htx.model.HtxDepth;
import com.metaverse.coin.strategy.common.client.htx.model.HtxOrder;
import com.metaverse.coin.strategy.common.client.htx.model.HtxPriceLevel;
import com.metaverse.coin.strategy.common.client.htx.model.HtxRestResponse;
import com.metaverse.coin.strategy.common.client.htx.rest.HtxAccountService;
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

/**
 * 买腿超时穿价 taker 入场（buy-taker-escalation）分支单测。
 * 覆盖：达标升级、价差太宽退回、开关关闭、撤单竞态成交、撤单未终态 defer、余额不足、下单被拒、成交后开卖腿。
 * 装配风格对齐 {@code MarketMakerServiceAdvanceTest}，入口走 public {@code advanceCell(cell)}。
 */
class MarketMakerServiceTakerBuyTest {

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
        mmProps.setSymbols(List.of("btcusdt"));
        mmProps.setSpreadPct(new BigDecimal("0.003"));
        mmProps.setBuyTtlMs(1L);                                  // TTL=1ms，配合 createdAt=now-2h 必过期
        mmProps.setBuyTakerEscalationEnabled(true);
        mmProps.setBuyTakerMaxCrossPct(new BigDecimal("0.001"));
        mmProps.setSellFollowEnabled(false);                     // 卖腿走 legacy，openSellLeg 用 sellFromBuy
        recorder = mock(HtxTradeRecorder.class);
        when(account.getAccountBalance("acc-1")).thenReturn(balanceOk("1000"));
        svc = new MarketMakerService(api, mock(HtxDepthGuard.class), tradeProps, mmProps, recorder);
    }

    // ---- helpers ----

    private MarketMakerCell expiredBuyCell(Long buyId) {
        return MarketMakerCell.builder()
                .pairId(100L).clientId("mm-btcusdt-1").symbol("btcusdt")
                .buyPrice(new BigDecimal("60000.00")).sellPrice(new BigDecimal("60360.00"))
                .qty(new BigDecimal("0.0003")).state(HtxStatus.MM_RESTING_BUY)
                .buyOrderId(buyId).createdAt(LocalDateTime.now().minusHours(2)).build();
    }

    private MarketMakerCell expiredBuyCellRawQty(Long buyId) {
        // 复刻生产：cell 由 DB 加载，req_quantity decimal(36,18) → qty scale=18
        return MarketMakerCell.builder()
                .pairId(100L).clientId("mm-btcusdt-1").symbol("btcusdt")
                .buyPrice(new BigDecimal("60000.00")).sellPrice(new BigDecimal("60360.00"))
                .qty(new BigDecimal("0.000300000000000000")).state(HtxStatus.MM_RESTING_BUY)
                .buyOrderId(buyId).createdAt(LocalDateTime.now().minusHours(2)).build();
    }

    private HtxRestResponse<HtxOrder> order(String state, String filled, String amount) {
        HtxOrder o = new HtxOrder();
        o.setState(state);
        o.setFilledAmount(new BigDecimal(filled));
        o.setAmount(new BigDecimal(amount));
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

    private HtxRestResponse<HtxDepth> depthBidAsk(String bid, String ask) {
        HtxDepth d = new HtxDepth();
        d.setBids(List.of(new HtxPriceLevel(new BigDecimal(bid), new BigDecimal("10"))));
        d.setAsks(List.of(new HtxPriceLevel(new BigDecimal(ask), new BigDecimal("10"))));
        HtxRestResponse<HtxDepth> r = new HtxRestResponse<>();
        r.setStatus("ok");
        r.setTick(d);
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

    // ---- scenarios ----

    @Test void ttlExpired_narrowSpread_balanceOk_escalatesTakerBuyAtAsk() {
        when(spot.queryOrder(555L)).thenReturn(order("submitted", "0", "0.0003"))
                .thenReturn(order("canceled", "0", "0.0003"));
        when(spot.cancelOrder(555L)).thenReturn(placeOk("555"));
        when(md.depth(eq("btcusdt"), anyInt())).thenReturn(depthBidAsk("60000", "60030"));
        when(spot.placeOrder(eq("acc-1"), eq("btcusdt"), eq("buy-limit"), eq("0.0003"), eq("60030.00"), anyString()))
                .thenReturn(placeOk("888"));

        svc.advanceCell(expiredBuyCell(555L));

        verify(spot).placeOrder(eq("acc-1"), eq("btcusdt"), eq("buy-limit"), eq("0.0003"), eq("60030.00"), anyString());
        verify(recorder).markMmReprice(eq(100L), eq(888L),
                eq(new BigDecimal("60030.00")), eq(new BigDecimal("60390.18")));
        verify(recorder, never()).markMmCancelledBuy(anyLong());
    }

    @Test void ttlExpired_wideSpread_fallsBackToCancelledBuy() {
        when(spot.queryOrder(555L)).thenReturn(order("submitted", "0", "0.0003"))
                .thenReturn(order("canceled", "0", "0.0003"));
        when(spot.cancelOrder(555L)).thenReturn(placeOk("555"));
        when(md.depth(eq("btcusdt"), anyInt())).thenReturn(depthBidAsk("60000", "60200"));

        svc.advanceCell(expiredBuyCell(555L));

        verify(recorder).markMmCancelledBuy(100L);
        verify(spot, never()).placeOrder(anyString(), anyString(), eq("buy-limit"), anyString(), anyString(), anyString());
        verify(recorder, never()).markMmReprice(anyLong(), anyLong(), any(), any());
    }

    @Test void disabledFlag_ttlExpired_marksCancelledBuyNoEscalate() {
        mmProps.setBuyTakerEscalationEnabled(false);
        when(spot.queryOrder(555L)).thenReturn(order("submitted", "0", "0.0003"))
                .thenReturn(order("canceled", "0", "0.0003"));
        when(spot.cancelOrder(555L)).thenReturn(placeOk("555"));

        svc.advanceCell(expiredBuyCell(555L));

        verify(recorder).markMmCancelledBuy(100L);
        verify(spot, never()).placeOrder(anyString(), anyString(), eq("buy-limit"), anyString(), anyString(), anyString());
    }

    @Test void ttlExpired_cancelRaceFilled_opensSellNoEscalate() {
        when(spot.queryOrder(555L)).thenReturn(order("submitted", "0", "0.0003"))
                .thenReturn(order("canceled", "0.0003", "0.0003"));
        when(spot.cancelOrder(555L)).thenReturn(placeOk("555"));
        when(spot.placeOrder(anyString(), anyString(), eq("sell-limit-maker"), anyString(), anyString(), anyString()))
                .thenReturn(placeOk("777"));

        svc.advanceCell(expiredBuyCell(555L));

        verify(spot).placeOrder(anyString(), anyString(), eq("sell-limit-maker"), anyString(), anyString(), anyString());
        verify(recorder).markMmSellResting(100L, 777L);
        verify(spot, never()).placeOrder(anyString(), anyString(), eq("buy-limit"), anyString(), anyString(), anyString());
        verify(recorder, never()).markMmCancelledBuy(anyLong());
    }

    @Test void ttlExpired_cancelNotSettled_defersNoEscalateNoCancelledBuy() {
        when(spot.queryOrder(555L)).thenReturn(order("submitted", "0", "0.0003"))
                .thenReturn(order("canceling", "0", "0.0003"));
        when(spot.cancelOrder(555L)).thenReturn(placeOk("555"));
        when(md.depth(eq("btcusdt"), anyInt())).thenReturn(depthBidAsk("60000", "60030"));

        svc.advanceCell(expiredBuyCell(555L));

        verify(spot, never()).placeOrder(anyString(), anyString(), eq("buy-limit"), anyString(), anyString(), anyString());
        verify(recorder, never()).markMmCancelledBuy(anyLong());
        verify(recorder, never()).markMmReprice(anyLong(), anyLong(), any(), any());
    }

    @Test void ttlExpired_insufficientBalance_marksCancelledBuyNoEscalate() {
        when(account.getAccountBalance("acc-1")).thenReturn(balanceOk("5"));
        when(spot.queryOrder(555L)).thenReturn(order("submitted", "0", "0.0003"))
                .thenReturn(order("canceled", "0", "0.0003"));
        when(spot.cancelOrder(555L)).thenReturn(placeOk("555"));
        when(md.depth(eq("btcusdt"), anyInt())).thenReturn(depthBidAsk("60000", "60030"));

        svc.advanceCell(expiredBuyCell(555L));

        verify(recorder).markMmCancelledBuy(100L);
        verify(spot, never()).placeOrder(anyString(), anyString(), eq("buy-limit"), anyString(), anyString(), anyString());
    }

    @Test void ttlExpired_takerBuyRejected_marksCancelledBuy() {
        when(spot.queryOrder(555L)).thenReturn(order("submitted", "0", "0.0003"))
                .thenReturn(order("canceled", "0", "0.0003"));
        when(spot.cancelOrder(555L)).thenReturn(placeOk("555"));
        when(md.depth(eq("btcusdt"), anyInt())).thenReturn(depthBidAsk("60000", "60030"));
        HtxRestResponse<String> err = new HtxRestResponse<>();
        err.setStatus("error");
        err.setErrMsg("taker-buy-rejected");
        when(spot.placeOrder(eq("acc-1"), eq("btcusdt"), eq("buy-limit"), anyString(), anyString(), anyString()))
                .thenReturn(err);

        svc.advanceCell(expiredBuyCell(555L));

        verify(recorder).markMmCancelledBuy(100L);
        verify(recorder, never()).markMmReprice(anyLong(), anyLong(), any(), any());
    }

    @Test void ttlExpired_highFeeRaisesSellFloorBeforeTakerBuy() {
        mmProps.setFeeRatePerSide(new BigDecimal("0.05"));
        when(spot.queryOrder(555L)).thenReturn(order("submitted", "0", "0.0003"))
                .thenReturn(order("canceled", "0", "0.0003"));
        when(spot.cancelOrder(555L)).thenReturn(placeOk("555"));
        when(md.depth(eq("btcusdt"), anyInt())).thenReturn(depthBidAsk("60000", "60030"));
        when(spot.placeOrder(eq("acc-1"), eq("btcusdt"), eq("buy-limit"),
                eq("0.0003"), eq("60030.00"), anyString())).thenReturn(placeOk("999"));

        svc.advanceCell(expiredBuyCell(555L));

        verify(recorder).markMmReprice(100L, 999L,
                new BigDecimal("60030.00"), new BigDecimal("66515.24"));
    }

    @Test void ttlExpired_takerBuyRejectedBalanceClass_startsBackoffBlockingNewBuys() {
        // taker 下单被余额类错误拒 → markMmCancelledBuy + 启动 backoff；随后 openNewCellIfEligible 被短路返回 -1
        mmProps.setInsufficientBalanceBackoffMs(60_000L);
        when(spot.queryOrder(555L)).thenReturn(order("submitted", "0", "0.0003"))
                .thenReturn(order("canceled", "0", "0.0003"));
        when(spot.cancelOrder(555L)).thenReturn(placeOk("555"));
        when(md.depth(eq("btcusdt"), anyInt())).thenReturn(depthBidAsk("60000", "60030"));
        HtxRestResponse<String> err = new HtxRestResponse<>();
        err.setStatus("error");
        err.setErrCode("account-frozen-balance-insufficient-error");
        when(spot.placeOrder(eq("acc-1"), eq("btcusdt"), eq("buy-limit"), anyString(), anyString(), anyString()))
                .thenReturn(err);

        svc.advanceCell(expiredBuyCell(555L));
        verify(recorder).markMmCancelledBuy(100L);

        // backoff 已启动：新买尝试被短路，不查深度/不建 cell
        org.junit.jupiter.api.Assertions.assertEquals(-1L, svc.openNewCellIfEligible("btcusdt"));
        verify(recorder, never()).createMmCell(anyString(), any(), any(), any(), anyString());
    }

    @Test void ttlExpired_dbScale18Qty_escalatesTakerBuyWithNormalizedQty() {
        // 生产复现：DB scale-18 qty 经 normalizeQty 归一后应发 "0.0003"（btc 精度 6，strip 尾零）
        when(spot.queryOrder(555L)).thenReturn(order("submitted", "0", "0.0003"))
                .thenReturn(order("canceled", "0", "0.0003"));
        when(spot.cancelOrder(555L)).thenReturn(placeOk("555"));
        when(md.depth(eq("btcusdt"), anyInt())).thenReturn(depthBidAsk("60000", "60030"));
        when(spot.placeOrder(eq("acc-1"), eq("btcusdt"), eq("buy-limit"), eq("0.0003"), eq("60030.00"), anyString()))
                .thenReturn(placeOk("888"));

        svc.advanceCell(expiredBuyCellRawQty(555L));

        verify(spot).placeOrder(eq("acc-1"), eq("btcusdt"), eq("buy-limit"), eq("0.0003"), eq("60030.00"), anyString());
        verify(recorder, never()).markMmCancelledBuy(anyLong());
    }

    @Test void escalatedBuy_onceFilled_nextTickOpensSellLegViaExistingMachinery() {
        MarketMakerCell escalated = MarketMakerCell.builder()
                .pairId(100L).clientId("mm-btcusdt-1").symbol("btcusdt")
                .buyPrice(new BigDecimal("60030.00")).sellPrice(new BigDecimal("60390.18"))
                .qty(new BigDecimal("0.0003")).state(HtxStatus.MM_RESTING_BUY)
                .buyOrderId(888L).createdAt(LocalDateTime.now()).build();
        when(spot.queryOrder(888L)).thenReturn(order("filled", "0.0003", "0.0003"));
        when(spot.placeOrder(anyString(), anyString(), eq("sell-limit-maker"), anyString(), anyString(), anyString()))
                .thenReturn(placeOk("777"));

        svc.advanceCell(escalated);

        verify(spot).placeOrder(anyString(), anyString(), eq("sell-limit-maker"), anyString(), anyString(), anyString());
        verify(recorder).markMmSellResting(100L, 777L);
    }
}
