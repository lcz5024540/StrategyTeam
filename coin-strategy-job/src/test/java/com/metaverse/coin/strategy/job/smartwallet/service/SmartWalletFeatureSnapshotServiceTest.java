package com.metaverse.coin.strategy.job.smartwallet.service;

import com.metaverse.coin.strategy.common.dao.mapper.SmartWalletBehaviorProfileMapper;
import com.metaverse.coin.strategy.common.dao.mapper.SmartWalletFeatureSnapshotMapper;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.SmartWalletFeatureSnapshotEntity;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

@ExtendWith(MockitoExtension.class)
class SmartWalletFeatureSnapshotServiceTest {

    @Mock
    private SmartWalletFeatureSnapshotMapper snapshotMapper;
    @Mock
    private SmartWalletBehaviorProfileMapper behaviorProfileMapper;

    @Test
    void resolveAtUsesLatestFeatureKnownAtDecisionTime() {
        SmartWalletFeatureSnapshotService service = new SmartWalletFeatureSnapshotService(snapshotMapper, behaviorProfileMapper);
        SmartWalletFeatureSnapshotEntity early = snapshot(1L, 1_000L, 1_100L);
        SmartWalletFeatureSnapshotEntity late = snapshot(2L, 2_000L, 2_100L);

        assertEquals(early, service.resolveAt(List.of(early, late), new Date(1_500L)));
        assertEquals(late, service.resolveAt(List.of(early, late), new Date(2_500L)));
    }

    @Test
    void resolveAtRejectsSnapshotCreatedAfterDecisionEvenWhenFeatureAsOfIsOld() {
        SmartWalletFeatureSnapshotService service = new SmartWalletFeatureSnapshotService(snapshotMapper, behaviorProfileMapper);
        SmartWalletFeatureSnapshotEntity backfilledLater = snapshot(1L, 1_000L, 3_000L);

        assertNull(service.resolveAt(List.of(backfilledLater), new Date(2_000L)));
    }

    @Test
    void resolveAtIsDeterministicWhenFeatureTimesMatch() {
        SmartWalletFeatureSnapshotService service = new SmartWalletFeatureSnapshotService(snapshotMapper, behaviorProfileMapper);
        SmartWalletFeatureSnapshotEntity first = snapshot(10L, 1_000L, 1_000L);
        SmartWalletFeatureSnapshotEntity second = snapshot(11L, 1_000L, 1_000L);

        assertEquals(second, service.resolveAt(List.of(first, second), new Date(2_000L)));
    }

    private SmartWalletFeatureSnapshotEntity snapshot(long id, long featureAsOf, long featureAvailableAt) {
        return new SmartWalletFeatureSnapshotEntity()
                .setId(id)
                .setWalletAddress("0xabc")
                .setDatasetVersion(SmartWalletFeatureSnapshotService.LIVE_DATASET_VERSION)
                .setFeatureAsOf(new Date(featureAsOf))
                .setFeatureAvailableAt(new Date(featureAvailableAt));
    }
}
