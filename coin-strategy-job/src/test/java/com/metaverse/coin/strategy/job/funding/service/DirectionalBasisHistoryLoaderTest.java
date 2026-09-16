package com.metaverse.coin.strategy.job.funding.service;

import com.metaverse.coin.strategy.common.dao.mapper.CrossExchangeArbOpportunityMapper;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.CrossExchangeArbOpportunityEntity;
import com.metaverse.coin.strategy.core.arbitrage.multi.DirectionalBasisAdmissionGate;
import com.metaverse.coin.strategy.core.arbitrage.multi.Exchange;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DirectionalBasisHistoryLoaderTest {
    @Test
    void restoresNewestRowsInChronologicalOrder() {
        CrossExchangeArbOpportunityMapper mapper = mock(CrossExchangeArbOpportunityMapper.class);
        when(mapper.selectList(any())).thenReturn(List.of(
                row(2L, "0.003", 2_000L), row(1L, "0.001", 1_000L)));
        DirectionalBasisAdmissionGate gate = new DirectionalBasisAdmissionGate(true, 2, 4, 70);

        new DirectionalBasisHistoryLoader(mapper, gate).restore();

        DirectionalBasisAdmissionGate.Profile profile = gate.profile(
                "BTCUSDT", Exchange.BITGET, Exchange.OKX);
        assertEquals(2, profile.sampleCount());
        assertEquals(0, new BigDecimal("0.002").compareTo(profile.median()));
    }

    private CrossExchangeArbOpportunityEntity row(long id, String basis, long discoveredAt) {
        return new CrossExchangeArbOpportunityEntity().setId(id).setSymbol("BTCUSDT")
                .setLongExchange("BITGET").setShortExchange("OKX")
                .setDirectionalBasis(new BigDecimal(basis)).setDiscoveredAt(new Date(discoveredAt));
    }
}
