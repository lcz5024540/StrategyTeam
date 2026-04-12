package com.metaverse.coin.strategy.job.smartwallet.intelligence.service;

import com.metaverse.coin.strategy.job.smartwallet.config.SmartWalletProperties;
import com.metaverse.coin.strategy.job.smartwallet.intelligence.model.ClusterConfidence;
import com.metaverse.coin.strategy.job.smartwallet.intelligence.model.EntityDedupedConsensus;
import com.metaverse.coin.strategy.job.smartwallet.intelligence.model.EntityResolutionResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class EntityAwareConsensusServiceTest {

    @Mock
    private WalletEntityResolutionService resolutionService;

    @Test
    void shouldDedupOnlyTrustedEntityLinks() {
        SmartWalletProperties properties = new SmartWalletProperties();
        properties.getIntelligence().setMinClusterConfidenceForDedup(new BigDecimal("0.40"));
        EntityAwareConsensusService service = new EntityAwareConsensusService(resolutionService, properties);

        Map<String, EntityResolutionResult> resolutions = new LinkedHashMap<>();
        resolutions.put("0xa", resolved("0xA", "entity-1", "BEHAVIOR_SIM", "0.70"));
        resolutions.put("0xb", resolved("0xB", "entity-1", "BEHAVIOR_SIM", "0.30"));

        when(resolutionService.resolveEntities(anyList())).thenReturn(resolutions);

        EntityDedupedConsensus result = service.deduplicateByEntity(
                List.of("0xA", "0xB"),
                Map.of("0xa", new BigDecimal("0.90"), "0xb", new BigDecimal("0.60"))
        );

        assertEquals(2, result.getDedupedEntityCount());
        assertFalse(result.isHasSuspiciousMultiWallet());
    }

    @Test
    void shouldDedupTrustedHighConfidenceEntityLinksAndFlagInflation() {
        SmartWalletProperties properties = new SmartWalletProperties();
        properties.getIntelligence().setMinClusterConfidenceForDedup(new BigDecimal("0.40"));
        EntityAwareConsensusService service = new EntityAwareConsensusService(resolutionService, properties);

        Map<String, EntityResolutionResult> resolutions = new LinkedHashMap<>();
        resolutions.put("0xa", resolved("0xA", "entity-1", "FUNDING_SOURCE", "0.90"));
        resolutions.put("0xb", resolved("0xB", "entity-1", "FUNDING_SOURCE", "0.90"));

        when(resolutionService.resolveEntities(anyList())).thenReturn(resolutions);

        EntityDedupedConsensus result = service.deduplicateByEntity(
                List.of("0xA", "0xB"),
                Map.of("0xa", new BigDecimal("0.90"), "0xb", new BigDecimal("0.60"))
        );

        assertEquals(1, result.getDedupedEntityCount());
        assertTrue(result.isHasSuspiciousMultiWallet());
        assertEquals(new BigDecimal("0.9000"), result.getEntityAvgScore());
    }

    private EntityResolutionResult resolved(String walletAddress, String entityId, String clusterMethod, String confidenceScore) {
        return EntityResolutionResult.builder()
                .walletAddress(walletAddress)
                .entityId(entityId)
                .confidence(ClusterConfidence.fromScore(new BigDecimal(confidenceScore)))
                .confidenceScore(new BigDecimal(confidenceScore))
                .entityWalletCount(2)
                .multiWallet(true)
                .primaryWalletAddress(walletAddress)
                .clusterMethod(clusterMethod)
                .resolvedFromMap(true)
                .pendingClustering(false)
                .build();
    }
}
