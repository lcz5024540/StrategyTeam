package com.metaverse.coin.strategy.htx.strategy;

import com.metaverse.coin.strategy.common.client.htx.HtxApiService;
import com.metaverse.coin.strategy.common.client.htx.model.*;
import com.metaverse.coin.strategy.common.client.htx.rest.HtxMarketDataService;
import com.metaverse.coin.strategy.common.client.htx.rest.HtxAccountService;
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
    HtxAccountService account;
    HtxDepthGuard depthGuard;
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
        when(account.getAccountBalance("acc-1")).thenReturn(balanceOk("1000"));
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
        when(recorder.findRecoverableCancelledMmCells()).thenReturn(List.of());
        when(spot.queryOrder(777L)).thenReturn(sellFilled());

        svc.recoverOpenCells();

        verify(recorder).markMmDone(eq(100L), any());
        verify(recorder, never()).createMmCell(anyString(), any(), any(), any(), anyString());
    }

    @Test void recoverOpenCellsRechecksCancelledSubmittedBuyAndPlacesCostBasedSell() {
        when(recorder.findAllOpenMmCells()).thenReturn(List.of());
        HtxLockedPairEntity cancelled = new HtxLockedPairEntity();
        cancelled.setId(15L);
        cancelled.setClientOrderId("mm-btcusdt-old");
        cancelled.setSymbol("btcusdt");
        cancelled.setBuyPrice(new BigDecimal("60000.00"));
        cancelled.setSellPrice(new BigDecimal("60360.00"));
        cancelled.setReqQuantity(new BigDecimal("0.0003"));
        cancelled.setBuyOrderId(555L);
        cancelled.setStatus(HtxStatus.MM_CANCELLED_BUY);
        when(recorder.findRecoverableCancelledMmCells()).thenReturn(List.of(cancelled));
        HtxOrder filled = new HtxOrder();
        filled.setState("partial-canceled");
        filled.setFilledAmount(new BigDecimal("0.0003"));
        filled.setFilledCashAmount(new BigDecimal("17.91"));
        filled.setFilledFees(new BigDecimal("0.0000006"));
        HtxRestResponse<HtxOrder> query = new HtxRestResponse<>();
        query.setStatus("ok");
        query.setData(filled);
        when(spot.queryOrder(555L)).thenReturn(query);
        when(spot.placeOrder(eq("acc-1"), eq("btcusdt"), eq("sell-limit"),
                anyString(), eq("60058.20"), eq("mm-btcusdt-old-SELL")))
                .thenReturn(placeOk("777"));

        svc.recoverOpenCells();

        verify(recorder).markMmSellPending(eq(15L), any(), any(), eq(new BigDecimal("60058.20")));
        verify(recorder).markMmSellResting(15L, 777L);
    }
}
