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
