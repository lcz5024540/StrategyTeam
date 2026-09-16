package com.metaverse.coin.strategy.job.funding.service;

import com.metaverse.coin.strategy.common.dao.mapper.CrossExchangeStrategyLeaseMapper;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CrossExchangeStrategyLeaseServiceTest {
    @Test
    void acquiresAndReleasesSymbolLease() {
        CrossExchangeStrategyLeaseMapper mapper = mock(CrossExchangeStrategyLeaseMapper.class);
        when(mapper.acquire(eq("cross-exchange-open:BTCUSDT"), any(), any(), any())).thenReturn(1);
        CrossExchangeStrategyLeaseService service = new CrossExchangeStrategyLeaseService(mapper);

        assertTrue(service.acquireSymbol("BTCUSDT", Instant.parse("2026-09-06T00:00:00Z")));
        service.releaseSymbol("BTCUSDT");

        verify(mapper).release(eq("cross-exchange-open:BTCUSDT"), any());
    }

    @Test
    void rejectsLeaseWhenAnotherSchedulerOwnsIt() {
        CrossExchangeStrategyLeaseMapper mapper = mock(CrossExchangeStrategyLeaseMapper.class);
        when(mapper.acquire(any(), any(), any(), any())).thenReturn(0);

        assertFalse(new CrossExchangeStrategyLeaseService(mapper)
                .acquireSymbol("BTCUSDT", Instant.parse("2026-09-06T00:00:00Z")));
    }
}
