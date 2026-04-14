package com.metaverse.coin.strategy.job.smartwallet.controller;

import com.metaverse.coin.strategy.common.model.arbitrage.entity.SmartWalletWalletFeatureDailyEntity;
import com.metaverse.coin.strategy.job.analytics.application.StrategyAnalyticsApplicationService;
import com.metaverse.coin.strategy.job.execution.application.ExecutionApplicationService;
import com.metaverse.coin.strategy.job.follow.application.FollowTaskApplicationService;
import com.metaverse.coin.strategy.job.marketdata.application.PredictionMarketDataApplicationService;
import com.metaverse.coin.strategy.job.risk.application.BaselineModelConfigApplicationService;
import com.metaverse.coin.strategy.job.risk.application.RiskPolicyApplicationService;
import com.metaverse.coin.strategy.job.settlement.application.SettlementApplicationService;
import com.metaverse.coin.strategy.job.smartwallet.config.SmartWalletProperties;
import com.metaverse.coin.strategy.job.smartwallet.service.PolymarketClobExecutionService;
import com.metaverse.coin.strategy.job.smartwallet.service.PolymarketConsensusSignalService;
import com.metaverse.coin.strategy.job.smartwallet.service.PolymarketMarketDataStreamService;
import com.metaverse.coin.strategy.job.smartwallet.service.PolymarketProductOverviewService;
import com.metaverse.coin.strategy.job.smartwallet.service.PolymarketSmartWalletMonitorService;
import com.metaverse.coin.strategy.job.smartwallet.service.PolymarketWalletDiscoveryService;
import com.metaverse.coin.strategy.job.smartwallet.service.PredictionMarketOpportunityService;
import com.metaverse.coin.strategy.job.smartwallet.service.MarketSnapshotCollectorService;
import com.metaverse.coin.strategy.job.smartwallet.service.SmartWalletAccountingService;
import com.metaverse.coin.strategy.job.smartwallet.service.SmartWalletClaimService;
import com.metaverse.coin.strategy.job.smartwallet.service.SmartWalletDataResetService;
import com.metaverse.coin.strategy.job.smartwallet.service.SmartWalletFollowService;
import com.metaverse.coin.strategy.job.smartwallet.service.SmartWalletFollowTaskService;
import com.metaverse.coin.strategy.job.smartwallet.service.SmartWalletLiveValidationService;
import com.metaverse.coin.strategy.job.smartwallet.service.SmartWalletRegistryService;
import com.metaverse.coin.strategy.job.smartwallet.service.SmartWalletSelectionService;
import com.metaverse.coin.strategy.job.smartwallet.service.SmartWalletSettlementSyncService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.env.Environment;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.LocalDate;
import java.util.List;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class SmartWalletControllerTest {

    @Mock
    private SmartWalletProperties properties;
    @Mock
    private SmartWalletRegistryService walletRegistryService;
    @Mock
    private PredictionMarketOpportunityService opportunityService;
    @Mock
    private SmartWalletFollowService followService;
    @Mock
    private SmartWalletAccountingService accountingService;
    @Mock
    private PolymarketSmartWalletMonitorService polymarketMonitorService;
    @Mock
    private PolymarketConsensusSignalService consensusSignalService;
    @Mock
    private PolymarketClobExecutionService clobExecutionService;
    @Mock
    private PolymarketMarketDataStreamService marketDataStreamService;
    @Mock
    private MarketSnapshotCollectorService marketSnapshotCollectorService;
    @Mock
    private PolymarketProductOverviewService productOverviewService;
    @Mock
    private PolymarketWalletDiscoveryService polymarketWalletDiscoveryService;
    @Mock
    private SmartWalletSelectionService selectionService;
    @Mock
    private SmartWalletFollowTaskService followTaskService;
    @Mock
    private SmartWalletClaimService claimService;
    @Mock
    private SmartWalletLiveValidationService liveValidationService;
    @Mock
    private SmartWalletSettlementSyncService settlementSyncService;
    @Mock
    private SmartWalletDataResetService dataResetService;
    @Mock
    private Environment environment;
    @Mock
    private PredictionMarketDataApplicationService predictionMarketDataApplicationService;
    @Mock
    private FollowTaskApplicationService followTaskApplicationService;
    @Mock
    private ExecutionApplicationService executionApplicationService;
    @Mock
    private SettlementApplicationService settlementApplicationService;
    @Mock
    private RiskPolicyApplicationService riskPolicyApplicationService;
    @Mock
    private BaselineModelConfigApplicationService baselineModelConfigApplicationService;
    @Mock
    private StrategyAnalyticsApplicationService strategyAnalyticsApplicationService;

    @InjectMocks
    private SmartWalletController controller;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test
    void shouldDefaultFollowTasksToIncludeRiskEvaluation() throws Exception {
        when(followTaskApplicationService.listTasks(5, true)).thenReturn(List.of());

        mockMvc.perform(get("/strategy/api/smart-wallet/follow-tasks")
                        .contextPath("/strategy/api")
                        .param("limit", "5"))
                .andExpect(status().isOk());

        verify(followTaskApplicationService).listTasks(5, true);
    }

    @Test
    void shouldExposeBaselineModelConfigEndpoint() throws Exception {
        mockMvc.perform(get("/strategy/api/smart-wallet/config/baseline-model")
                        .contextPath("/strategy/api"))
                .andExpect(status().isOk());

        verify(baselineModelConfigApplicationService).current();
    }

    @Test
    void shouldExposeWalletFeatureDailyListEndpoint() throws Exception {
        when(predictionMarketDataApplicationService.listWalletFeatureDaily(20))
                .thenReturn(List.of(new SmartWalletWalletFeatureDailyEntity()));

        mockMvc.perform(get("/strategy/api/smart-wallet/wallet-features/daily")
                        .contextPath("/strategy/api")
                        .param("limit", "20"))
                .andExpect(status().isOk());

        verify(predictionMarketDataApplicationService).listWalletFeatureDaily(20);
    }

    @Test
    void shouldBuildWalletFeatureDailyForSpecifiedDate() throws Exception {
        when(predictionMarketDataApplicationService.buildWalletFeatureDaily("wallet-1", LocalDate.of(2026, 4, 10)))
                .thenReturn(new SmartWalletWalletFeatureDailyEntity());

        mockMvc.perform(post("/strategy/api/smart-wallet/wallet-features/daily")
                        .contextPath("/strategy/api")
                        .param("walletAddress", "wallet-1")
                        .param("featureDate", "2026-04-10"))
                .andExpect(status().isOk());

        verify(predictionMarketDataApplicationService).buildWalletFeatureDaily("wallet-1", LocalDate.of(2026, 4, 10));
    }

    @Test
    void shouldAdmitWalletToWatchlistWhenQualified() throws Exception {
        when(polymarketWalletDiscoveryService.analyzeWalletAndAdmitWatchlist("0xabc"))
                .thenReturn(true);

        mockMvc.perform(post("/strategy/api/smart-wallet/polymarket/watchlist/admit")
                        .contextPath("/strategy/api")
                        .param("walletAddress", "0xabc"))
                .andExpect(status().isOk());

        verify(polymarketWalletDiscoveryService).analyzeWalletAndAdmitWatchlist("0xabc");
    }

    @Test
    void shouldReevaluateDeferredIntelligenceCandidates() throws Exception {
        when(followTaskService.reevaluateDeferredCandidates(12)).thenReturn(7);

        mockMvc.perform(post("/strategy/api/smart-wallet/follow-tasks/intelligence/reevaluate-deferred")
                        .contextPath("/strategy/api")
                        .param("limit", "12"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.limit").value(12))
                .andExpect(jsonPath("$.updated").value(7));

        verify(followTaskService).reevaluateDeferredCandidates(12);
    }

    @Test
    void shouldReplayRecentIntelligenceCandidates() throws Exception {
        when(followTaskService.replayRecentIntelligenceCandidates(25, true))
                .thenReturn(SmartWalletFollowTaskService.IntelligenceReplaySummary.builder()
                        .scanned(25)
                        .changed(6)
                        .skipped(19)
                        .build());

        mockMvc.perform(post("/strategy/api/smart-wallet/follow-tasks/intelligence/replay-recent")
                        .contextPath("/strategy/api")
                        .param("limit", "25")
                        .param("includeProcessed", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.limit").value(25))
                .andExpect(jsonPath("$.includeProcessed").value(true))
                .andExpect(jsonPath("$.scanned").value(25))
                .andExpect(jsonPath("$.changed").value(6))
                .andExpect(jsonPath("$.skipped").value(19));

        verify(followTaskService).replayRecentIntelligenceCandidates(25, true);
    }
}
