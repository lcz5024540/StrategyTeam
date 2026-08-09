package com.metaverse.coin.strategy.htx.persistence;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.metaverse.coin.strategy.htx.persistence.entity.HtxContractCycleEntity;
import com.metaverse.coin.strategy.htx.persistence.mapper.HtxContractCycleMapper;
import com.metaverse.coin.strategy.htx.service.HtxContractCycleRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class HtxContractTradeRecorderTest {

    private HtxContractCycleMapper mapper;
    private HtxContractTradeRecorder recorder;

    @BeforeEach
    void setUp() {
        mapper = mock(HtxContractCycleMapper.class);
        recorder = new HtxContractTradeRecorder(mapper);
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
}
