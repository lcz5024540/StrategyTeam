package com.metaverse.coin.strategy.htx.persistence;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.metaverse.coin.strategy.htx.persistence.entity.HtxContractCycleEntity;
import com.metaverse.coin.strategy.htx.persistence.entity.HtxContractLegEntity;
import com.metaverse.coin.strategy.htx.persistence.mapper.HtxContractCycleMapper;
import com.metaverse.coin.strategy.htx.persistence.mapper.HtxContractLegMapper;
import com.metaverse.coin.strategy.htx.service.HtxContractCycleRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class HtxContractTradeRecorderTest {

    private HtxContractCycleMapper mapper;
    private HtxContractLegMapper legMapper;
    private HtxContractTradeRecorder recorder;

    @BeforeEach
    void setUp() {
        mapper = mock(HtxContractCycleMapper.class);
        legMapper = mock(HtxContractLegMapper.class);
        recorder = new HtxContractTradeRecorder(mapper, legMapper);
    }

    @Test
    void activeCycleReadFailureFailsClosed() {
        when(mapper.selectCount(any(Wrapper.class))).thenThrow(new RuntimeException("db down"));

        assertThrows(HtxPersistenceException.class, () -> recorder.hasActiveCycle("BTC-USDT"));
    }

    @Test
    void activeCycleCountBlocksNewCycle() {
        when(mapper.selectCount(any(Wrapper.class))).thenReturn(1L);

        assertTrue(recorder.hasActiveCycle("BTC-USDT"));
    }

    @Test
    void createIntentPersistsContractUnitsAndLeverageBeforeSend() {
        when(mapper.insert(any())).thenAnswer(invocation -> {
            ((HtxContractCycleEntity) invocation.getArgument(0)).setId(42L);
            return 1;
        });
        HtxContractCycleRequest request = HtxContractCycleRequest.builder()
                .clientOrderId(100L).volume(2L)
                .openPrice(new BigDecimal("60000")).closePrice(new BigDecimal("60100")).build();

        long id = recorder.createIntent(request, "BTC-USDT", 4);

        assertEquals(42L, id);
        ArgumentCaptor<HtxContractCycleEntity> captor = ArgumentCaptor.forClass(HtxContractCycleEntity.class);
        verify(mapper).insert(captor.capture());
        assertEquals(HtxContractTradeRecorder.INTENT, captor.getValue().getStatus());
        assertEquals(2L, captor.getValue().getReqVolume());
        assertEquals(4, captor.getValue().getLeverage());
    }

    @Test
    void recordDryRunPersistsCompletedSimulation() {
        when(mapper.insert(any())).thenAnswer(invocation -> {
            ((HtxContractCycleEntity) invocation.getArgument(0)).setId(43L);
            return 1;
        });
        HtxContractCycleRequest request = HtxContractCycleRequest.builder()
                .clientOrderId(101L).volume(2L)
                .openPrice(new BigDecimal("4333.34")).closePrice(new BigDecimal("4359.35")).build();

        long id = recorder.recordDryRun(request, "XAUT-USDT", 50, "dry-run");

        assertEquals(43L, id);
        ArgumentCaptor<HtxContractCycleEntity> captor = ArgumentCaptor.forClass(HtxContractCycleEntity.class);
        verify(mapper).insert(captor.capture());
        assertEquals(HtxContractTradeRecorder.DRY_RUN_DONE, captor.getValue().getStatus());
        assertEquals("XAUT-USDT", captor.getValue().getContractCode());
        assertEquals(50, captor.getValue().getLeverage());
        assertEquals(BigDecimal.ZERO, captor.getValue().getOpenFilledVolume());
        assertEquals(BigDecimal.ZERO, captor.getValue().getCloseFilledVolume());
    }

    @Test
    void createLegIntentPersistsPositionSideReduceOnlyAndPriceBeforeBatchSend() {
        recorder.createLegIntent(42L, HtxContractTradeRecorder.SHORT_CLOSE, 103L,
                "buy", "short", true, 2L, new BigDecimal("4359.33"));

        ArgumentCaptor<HtxContractLegEntity> captor = ArgumentCaptor.forClass(HtxContractLegEntity.class);
        verify(legMapper).insert(captor.capture());
        HtxContractLegEntity leg = captor.getValue();
        assertEquals(42L, leg.getCycleId());
        assertEquals(HtxContractTradeRecorder.SHORT_CLOSE, leg.getLegType());
        assertEquals("short", leg.getPositionSide());
        assertTrue(leg.getReduceOnly());
        assertEquals(new BigDecimal("4359.33"), leg.getPrice());
        assertEquals(HtxContractTradeRecorder.LEG_INTENT, leg.getState());
    }

    @Test
    void dailyMakerGridNetDefaultsToZeroAndDetectsLossStreak() {
        when(legMapper.sumMakerGridNetSince(any())).thenReturn(null);
        when(legMapper.findRecentMakerGridNet(3))
                .thenReturn(List.of(new BigDecimal("-0.1"), new BigDecimal("-0.2"), new BigDecimal("-0.3")));

        assertEquals(BigDecimal.ZERO, recorder.sumTodayMakerGridNet());
        assertTrue(recorder.reachedConsecutiveMakerGridLosses(3));
    }

    @Test
    void makerGridIntentCarriesRecoveryMarker() {
        when(mapper.insert(any())).thenAnswer(invocation -> {
            ((HtxContractCycleEntity) invocation.getArgument(0)).setId(44L);
            return 1;
        });
        HtxContractCycleRequest request = HtxContractCycleRequest.builder()
                .clientOrderId(102L).volume(2L).makerGrid(true)
                .openPrice(new BigDecimal("4333.32")).closePrice(new BigDecimal("4333.34")).build();

        recorder.createIntent(request, "XAUT-USDT", 5);

        ArgumentCaptor<HtxContractCycleEntity> captor = ArgumentCaptor.forClass(HtxContractCycleEntity.class);
        verify(mapper).insert(captor.capture());
        assertEquals("MAKER_GRID:intent", captor.getValue().getMessage());
    }
}
