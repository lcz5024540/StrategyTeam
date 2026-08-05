package com.metaverse.coin.strategy.htx.recovery;

import com.metaverse.coin.strategy.htx.service.HtxTradeProperties;
import com.metaverse.coin.strategy.htx.persistence.HtxStatus;
import com.metaverse.coin.strategy.htx.persistence.HtxTradeRecorder;
import com.metaverse.coin.strategy.htx.persistence.entity.HtxExposureEntity;
import com.metaverse.coin.strategy.common.client.htx.HtxApiService;
import com.metaverse.coin.strategy.common.client.htx.rest.HtxSpotService;
import com.metaverse.coin.strategy.common.client.htx.model.HtxRestResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class HtxReconcilerTest {

    private HtxTradeRecorder recorder;
    private HtxApiService api;
    private HtxSpotService spot;
    private HtxTradeProperties props;
    private HtxReconciler reconciler;

    private HtxExposureEntity openExposure() {
        HtxExposureEntity ex = new HtxExposureEntity();
        ex.setId(7L);
        ex.setPairId(3L);
        ex.setSymbol("btcusdt");
        ex.setExposedSide("base-long");
        ex.setExposedAmount(new BigDecimal("0.00998"));
        ex.setStatus(HtxStatus.EXP_OPEN);
        return ex;
    }

    @BeforeEach
    void setUp() {
        recorder = mock(HtxTradeRecorder.class);
        api = mock(HtxApiService.class);
        spot = mock(HtxSpotService.class);
        when(api.getSpotService()).thenReturn(spot);
        props = new HtxTradeProperties();
        props.getTrade().setEnabled(true);
        props.getApi().setSpotAccountId("123");
        props.getReconciler().setMaxFlattenRetries(3);
        reconciler = new HtxReconciler(recorder, api, props);
    }

    @Test
    void openBaseLong_marketFlattens_marksFlattened() {
        when(recorder.findOpenExposures()).thenReturn(List.of(openExposure()));
        when(recorder.insertFlattenIntent(eq(3L), eq("btcusdt"), any())).thenReturn(55L);
        when(spot.placeOrder(eq("123"), eq("btcusdt"), eq("sell-market"), any(), isNull(), anyString()))
                .thenReturn(HtxRestResponse.<String>builder().status("ok").data("88001").build());

        reconciler.reconcile();

        verify(recorder).markExposureFlattened(eq(7L), eq(88001L));
    }

    @Test
    void flattenRestFails_incrementsRetry_notFlattened() {
        when(recorder.findOpenExposures()).thenReturn(List.of(openExposure()));
        when(recorder.insertFlattenIntent(anyLong(), anyString(), any())).thenReturn(55L);
        when(spot.placeOrder(anyString(), anyString(), eq("sell-market"), any(), isNull(), anyString()))
                .thenReturn(HtxRestResponse.<String>builder().status("error").errMsg("insufficient").build());

        reconciler.reconcile();

        verify(recorder, never()).markExposureFlattened(anyLong(), anyLong());
        verify(recorder).incrementFlattenRetry(eq(7L));
    }

    @Test
    void retriesExhausted_marksManual() {
        HtxExposureEntity ex = openExposure();
        ex.setFlattenRetries(3);
        when(recorder.findOpenExposures()).thenReturn(List.of(ex));

        reconciler.reconcile();

        verify(recorder).markExposureManual(eq(7L), anyString());
        verify(spot, never()).placeOrder(anyString(), anyString(), anyString(), any(), any(), anyString());
    }

    @Test
    void disabled_doesNothing() {
        props.getTrade().setEnabled(false);
        reconciler.reconcile();
        verifyNoInteractions(recorder);
    }
}
