package com.metaverse.coin.strategy.job.smartwallet.intelligence.service;

import com.metaverse.coin.strategy.common.dao.mapper.WalletEntityMapMapper;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.WalletEntityMapEntity;
import com.metaverse.coin.strategy.job.smartwallet.intelligence.model.EntityResolutionResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WalletEntityResolutionServiceTest {

    @Mock
    private WalletEntityMapMapper entityMapMapper;

    @Mock
    private WalletClusteringService clusteringService;

    @Test
    void shouldReturnMappedEntityAfterFirstMissTriggersClustering() {
        WalletEntityMapEntity clustered = new WalletEntityMapEntity()
                .setWalletAddress("0xabc")
                .setEntityId("entity-1")
                .setClusterMethod("FUNDING_SOURCE")
                .setClusterConfidence(new BigDecimal("0.90"));

        when(entityMapMapper.selectOne(any()))
                .thenReturn(null)
                .thenReturn(clustered);
        when(entityMapMapper.selectCount(any())).thenReturn(2L);
        doNothing().when(clusteringService).clusterWallet("0xabc");

        WalletEntityResolutionService service = new WalletEntityResolutionService(entityMapMapper, clusteringService);
        EntityResolutionResult result = service.resolveEntity("0xAbC");

        assertEquals("entity-1", result.getEntityId());
        assertEquals(2, result.getEntityWalletCount());
        assertTrue(result.isResolvedFromMap());
        assertFalse(result.isPendingClustering());
        verify(clusteringService, times(1)).clusterWallet("0xabc");
    }

    @Test
    void shouldStayPendingWhenClusteringStillDoesNotProduceMap() {
        when(entityMapMapper.selectOne(any())).thenReturn(null);
        doNothing().when(clusteringService).clusterWallet("0xabc");

        WalletEntityResolutionService service = new WalletEntityResolutionService(entityMapMapper, clusteringService);
        EntityResolutionResult result = service.resolveEntity("0xAbC");

        assertEquals("0xabc", result.getEntityId());
        assertTrue(result.isPendingClustering());
        assertFalse(result.isResolvedFromMap());
        verify(clusteringService, times(1)).clusterWallet("0xabc");
    }
}
