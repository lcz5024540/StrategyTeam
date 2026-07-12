package com.metaverse.coin.strategy.job.smartwallet.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.metaverse.coin.strategy.common.dao.mapper.StrategyDecisionAuditMapper;
import com.metaverse.coin.strategy.common.dao.mapper.StrategyExperimentMapper;
import com.metaverse.coin.strategy.common.service.smartwallet.config.SmartWalletProperties;
import com.metaverse.coin.strategy.job.smartwallet.model.SmartWalletReplayBacktestResult;
import com.metaverse.coin.strategy.job.smartwallet.model.SmartWalletReplayStrategyMetrics;
import com.metaverse.coin.strategy.job.smartwallet.model.SmartWalletReplayWindowResult;
import com.metaverse.coin.strategy.job.smartwallet.model.StrategyExperimentResult;
import com.metaverse.coin.strategy.job.smartwallet.model.StrategyExperimentSpec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class StrategyExperimentServiceTest {

    @Mock
    private StrategyExperimentMapper experimentMapper;
    @Mock
    private StrategyDecisionAuditMapper decisionAuditMapper;
    @Mock
    private SmartWalletReplayBacktestService replayService;

    @Test
    void executePersistsWalkForwardResultAndRejectsInsufficientPointInTimeCoverage() {
        StrategyExperimentService service = new StrategyExperimentService(
                experimentMapper, decisionAuditMapper, replayService, new ObjectMapper());
        when(replayService.replayWithPolicies(any(), any(), any(), any()))
                .thenReturn(replayResult(new BigDecimal("0.97"), BigDecimal.ONE));

        StrategyExperimentResult result = service.execute(spec());

        assertEquals(StrategyExperimentService.STATUS_REJECTED_DATA_QUALITY, result.getStatus());
        assertEquals(1, result.getFolds().size());
        assertTrue(result.getGateFailures().contains("fold=1:POINT_IN_TIME_COVERAGE_BELOW_98_PERCENT"));
        assertEquals(64, result.getResultHash().length());
        verify(experimentMapper).insert(any());
        verify(experimentMapper).updateById(any());
    }

    private StrategyExperimentSpec spec() {
        StrategyExperimentSpec spec = new StrategyExperimentSpec();
        spec.setAsOf(new Date(1_800_000_000_000L));
        spec.setFoldCount(1);
        SmartWalletProperties.SignalRisk champion = new SmartWalletProperties.SignalRisk();
        champion.setPolicyTag("champion-v1");
        SmartWalletProperties.SignalRisk challenger = new SmartWalletProperties.SignalRisk();
        challenger.setPolicyTag("challenger-v2");
        spec.setChampionPolicy(champion);
        spec.setCandidatePolicy(challenger);
        return spec;
    }

    private SmartWalletReplayBacktestResult replayResult(BigDecimal pointInTimeCoverage,
                                                         BigDecimal labelCoverage) {
        SmartWalletReplayStrategyMetrics lane = SmartWalletReplayStrategyMetrics.builder()
                .acceptedEvents(10)
                .labelCoverageRate(labelCoverage)
                .build();
        return SmartWalletReplayBacktestResult.builder()
                .windows(List.of(SmartWalletReplayWindowResult.builder()
                        .scannedEvents(10)
                        .pointInTimeSnapshotCoverageRate(pointInTimeCoverage)
                        .baseline(lane)
                        .candidate(lane)
                        .build()))
                .build();
    }
}
