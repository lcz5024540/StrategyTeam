package com.metaverse.coin.strategy.job.smartwallet.controller;

import com.metaverse.coin.strategy.common.model.arbitrage.entity.SmartWalletProfileEntity;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.SmartWalletWalletFeatureDailyEntity;
import com.metaverse.coin.strategy.job.analytics.application.StrategyAnalyticsApplicationService;
import com.metaverse.coin.strategy.job.execution.application.ExecutionApplicationService;
import com.metaverse.coin.strategy.job.follow.application.FollowTaskApplicationService;
import com.metaverse.coin.strategy.job.marketdata.application.PredictionMarketDataApplicationService;
import com.metaverse.coin.strategy.job.risk.application.BaselineModelConfigApplicationService;
import com.metaverse.coin.strategy.job.risk.application.RiskPolicyApplicationService;
import com.metaverse.coin.strategy.job.risk.application.StrategyRuntimeConfigService;
import com.metaverse.coin.strategy.job.settlement.application.SettlementApplicationService;
import com.metaverse.coin.strategy.common.service.smartwallet.config.SmartWalletProperties;
import com.metaverse.coin.strategy.common.service.smartwallet.model.DiscoverySessionCoverageView;
import com.metaverse.coin.strategy.job.smartwallet.model.SmartWalletProfileBatchOverrideRequest;
import com.metaverse.coin.strategy.job.smartwallet.model.SmartWalletProfileBatchOverrideResultView;
import com.metaverse.coin.strategy.job.smartwallet.model.SmartWalletProfileOpsCandidateView;
import com.metaverse.coin.strategy.job.smartwallet.model.SmartWalletProfileOverrideView;
import com.metaverse.coin.strategy.job.smartwallet.model.SmartWalletProfileOverrideRequest;
import com.metaverse.coin.strategy.job.smartwallet.model.SmartWalletProfileOverrideSummaryView;
import com.metaverse.coin.strategy.job.smartwallet.model.StrategyRuntimeConfigCatalogView;
import com.metaverse.coin.strategy.job.smartwallet.model.StrategyRuntimeConfigView;
import com.metaverse.coin.strategy.common.service.smartwallet.model.WalletArchetypeView;
import com.metaverse.coin.strategy.common.service.smartwallet.model.WalletBehaviorProfileView;
import com.metaverse.coin.strategy.common.service.smartwallet.model.WalletDiagnosisView;
import com.metaverse.coin.strategy.common.service.smartwallet.model.WalletOrderAnalysisView;
import com.metaverse.coin.strategy.common.service.smartwallet.model.WalletOrderStatsView;
import com.metaverse.coin.strategy.job.smartwallet.model.WalletScreenerView;
import com.metaverse.coin.strategy.job.smartwallet.service.WalletArchetypeClassifierService;
import com.metaverse.coin.strategy.job.smartwallet.service.PolymarketClobExecutionService;
import com.metaverse.coin.strategy.job.smartwallet.service.PolymarketConsensusSignalService;
import com.metaverse.coin.strategy.job.smartwallet.service.PolymarketMarketDataStreamService;
import com.metaverse.coin.strategy.job.smartwallet.service.PolymarketProductOverviewService;
import com.metaverse.coin.strategy.job.smartwallet.service.PolymarketSmartWalletMonitorService;
import com.metaverse.coin.strategy.job.smartwallet.service.PolymarketWalletDiscoveryService;
import com.metaverse.coin.strategy.job.smartwallet.service.PredictionMarketOpportunityService;
import com.metaverse.coin.strategy.job.smartwallet.service.FollowTradeAttributionService;
import com.metaverse.coin.strategy.job.smartwallet.service.MarketConcentrationService;
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
import com.metaverse.coin.strategy.job.smartwallet.service.WalletBehaviorProfileService;
import com.metaverse.coin.strategy.job.smartwallet.service.SmartWalletOrderAnalysisService;
import com.metaverse.coin.strategy.job.smartwallet.service.SmartWalletProfileStoreService;
import com.metaverse.coin.strategy.job.smartwallet.service.SmartWalletMarketMonitoringService;
import com.metaverse.coin.strategy.job.smartwallet.service.WalletOrderStatsService;
import com.metaverse.coin.strategy.job.smartwallet.service.WalletProfileAnalysisService;
import com.metaverse.coin.strategy.job.strategy.model.CursorPageView;
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

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
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
    private StrategyRuntimeConfigService strategyRuntimeConfigService;
    @Mock
    private StrategyAnalyticsApplicationService strategyAnalyticsApplicationService;
    @Mock
    private MarketConcentrationService marketConcentrationService;
    @Mock
    private FollowTradeAttributionService followTradeAttributionService;
    @Mock
    private WalletArchetypeClassifierService walletArchetypeClassifierService;
    @Mock
    private WalletBehaviorProfileService walletBehaviorProfileService;
    @Mock
    private SmartWalletOrderAnalysisService smartWalletOrderAnalysisService;
    @Mock
    private WalletOrderStatsService walletOrderStatsService;
    @Mock
    private SmartWalletProfileStoreService smartWalletProfileStoreService;
    @Mock
    private WalletProfileAnalysisService walletProfileAnalysisService;
    @Mock
    private SmartWalletMarketMonitoringService smartWalletMarketMonitoringService;

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
    void shouldExposeRuntimeConfigCatalogEndpoint() throws Exception {
        when(properties.isRuntimeConfigApiEnabled()).thenReturn(true);
        when(strategyRuntimeConfigService.catalog("POLYMARKET_COPY"))
                .thenReturn(StrategyRuntimeConfigCatalogView.builder()
                        .strategyCode("POLYMARKET_COPY")
                        .groups(List.of())
                        .items(List.of())
                        .build());

        mockMvc.perform(get("/strategy/api/smart-wallet/config/runtime/catalog")
                        .contextPath("/strategy/api"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.strategyCode").value("POLYMARKET_COPY"));

        verify(strategyRuntimeConfigService).catalog("POLYMARKET_COPY");
    }

    @Test
    void shouldUpdateRuntimeConfigEndpoint() throws Exception {
        when(properties.isRuntimeConfigApiEnabled()).thenReturn(true);
        when(strategyRuntimeConfigService.update(eq("POLYMARKET_COPY"), any()))
                .thenReturn(List.of(StrategyRuntimeConfigView.builder()
                        .strategyCode("POLYMARKET_COPY")
                        .key("smart-wallet.signal-risk.min-trigger-size-usdc")
                        .value("12.5")
                        .build()));

        mockMvc.perform(post("/strategy/api/smart-wallet/config/runtime")
                        .contextPath("/strategy/api")
                        .contentType("application/json")
                        .content("""
                                {"items":[{"key":"smart-wallet.signal-risk.min-trigger-size-usdc","value":"12.5"}]}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].value").value("12.5"));

        verify(strategyRuntimeConfigService).update(eq("POLYMARKET_COPY"), any());
    }

    @Test
    void shouldBlockRuntimeConfigEndpointWhenAdminModuleIsMissing() throws Exception {
        mockMvc.perform(post("/strategy/api/smart-wallet/config/runtime")
                        .contextPath("/strategy/api")
                        .contentType("application/json")
                        .content("""
                                {"items":[{"key":"smart-wallet.signal-risk.min-trigger-size-usdc","value":"12.5"}]}
                                """))
                .andExpect(status().isForbidden());
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
    void shouldExposeDiscoverySessionCoverageEndpoint() throws Exception {
        when(polymarketWalletDiscoveryService.sessionCoverage())
                .thenReturn(DiscoverySessionCoverageView.builder().lookbackDays(30).build());

        mockMvc.perform(get("/strategy/api/smart-wallet/discovery/session-coverage")
                        .contextPath("/strategy/api"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lookbackDays").value(30));

        verify(polymarketWalletDiscoveryService).sessionCoverage();
    }

    @Test
    void shouldForwardMarketMonitoringCategoryFilter() throws Exception {
        when(smartWalletMarketMonitoringService.tablePage(2, 20, 14, "election", "POLITICS", "HIGH", true, "YES"))
                .thenReturn(com.metaverse.coin.strategy.common.service.smartwallet.model.OffsetPageView.of(List.of(), 2, 20, 0));

        mockMvc.perform(get("/strategy/api/smart-wallet/reports/market-monitoring/page")
                        .contextPath("/strategy/api")
                        .param("page", "2")
                        .param("pageSize", "20")
                        .param("lookbackDays", "14")
                        .param("keyword", "election")
                        .param("category", "POLITICS")
                        .param("concentrationBand", "HIGH")
                        .param("hasPosition", "true")
                        .param("positionDirection", "YES"))
                .andExpect(status().isOk());

        verify(smartWalletMarketMonitoringService).tablePage(2, 20, 14, "election", "POLITICS", "HIGH", true, "YES");
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

    @Test
    void shouldExposeWatchlistBehaviorProfilesEndpoint() throws Exception {
        when(walletBehaviorProfileService.buildProfiles(any(), any())).thenReturn(List.of(
                WalletBehaviorProfileView.builder()
                        .walletAddress("0xabc")
                        .recommendedExitLane("EARLY_TAKE_PROFIT")
                        .build()
        ));

        mockMvc.perform(get("/strategy/api/smart-wallet/watchlist/behavior-profiles")
                        .contextPath("/strategy/api")
                        .param("limit", "10")
                        .param("scope", "candidates"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].walletAddress").value("0xabc"))
                .andExpect(jsonPath("$[0].recommendedExitLane").value("EARLY_TAKE_PROFIT"));
    }

    @Test
    void shouldExposeProfileScreenerEndpoint() throws Exception {
        when(walletProfileAnalysisService.screen(any(), eq(20), eq(null), eq("replicabilityScore"), eq("desc"),
                eq(null), eq(null), eq(null), eq(null), eq(null), eq(null), eq(null)))
                .thenReturn(CursorPageView.of(List.of(
                        WalletScreenerView.builder()
                                .walletAddress("0xabc")
                                .screenerTier("EXECUTABLE_A")
                                .qualityScore(new java.math.BigDecimal("0.81"))
                                .build()
                ), null));

        mockMvc.perform(get("/strategy/api/smart-wallet/profiles/screener")
                        .contextPath("/strategy/api"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].walletAddress").value("0xabc"))
                .andExpect(jsonPath("$.items[0].screenerTier").value("EXECUTABLE_A"))
                .andExpect(jsonPath("$.items[0].qualityScore").value(0.81));
    }

    @Test
    void shouldExposeWalletOrderAnalysisEndpoint() throws Exception {
        when(smartWalletOrderAnalysisService.listByWalletAddress("0xabc", false, null, null)).thenReturn(List.of());
        when(smartWalletOrderAnalysisService.toViews(List.of())).thenReturn(List.of(
                WalletOrderAnalysisView.builder()
                        .walletAddress("0xabc")
                        .exitStyle("EARLY_PROFIT")
                        .roiRate(new java.math.BigDecimal("0.18"))
                        .build()
        ));

        mockMvc.perform(get("/strategy/api/smart-wallet/profiles/0xabc/order-analysis")
                        .contextPath("/strategy/api"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].walletAddress").value("0xabc"))
                .andExpect(jsonPath("$.items[0].exitStyle").value("EARLY_PROFIT"))
                .andExpect(jsonPath("$.items[0].roiRate").value(0.18));
    }

    @Test
    void shouldExposeWalletDiagnosisEndpoint() throws Exception {
        when(walletProfileAnalysisService.diagnose("0xabc", false)).thenReturn(
                WalletDiagnosisView.builder()
                        .walletAddress("0xabc")
                        .winSourceType("LATENCY_ALPHA")
                        .recommendedExitLane("EARLY_TAKE_PROFIT")
                        .build()
        );

        mockMvc.perform(get("/strategy/api/smart-wallet/profiles/0xabc/diagnosis")
                        .contextPath("/strategy/api"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.walletAddress").value("0xabc"))
                .andExpect(jsonPath("$.winSourceType").value("LATENCY_ALPHA"))
                .andExpect(jsonPath("$.recommendedExitLane").value("EARLY_TAKE_PROFIT"));
    }

    @Test
    void shouldExposeWalletOrderStatsEndpoint() throws Exception {
        when(smartWalletOrderAnalysisService.listFreshOrRefresh("0xabc", false)).thenReturn(List.of());
        when(walletOrderStatsService.summarize("0xabc", List.of(), "30d")).thenReturn(
                WalletOrderStatsView.builder()
                        .walletAddress("0xabc")
                        .window("30D")
                        .closedOrderCount(12)
                        .closedWinRate(new java.math.BigDecimal("0.7500"))
                        .netPnlUsdc(new java.math.BigDecimal("88.5000"))
                        .build()
        );

        mockMvc.perform(get("/strategy/api/smart-wallet/profiles/0xabc/order-stats")
                        .contextPath("/strategy/api")
                        .param("window", "30d"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.walletAddress").value("0xabc"))
                .andExpect(jsonPath("$.closedOrderCount").value(12))
                .andExpect(jsonPath("$.closedWinRate").value(0.75))
                .andExpect(jsonPath("$.netPnlUsdc").value(88.5));
    }

    @Test
    void shouldRefreshAndPersistWatchlistBehaviorProfiles() throws Exception {
        when(walletBehaviorProfileService.refreshAndPersistProfiles(any(), any())).thenReturn(List.of(
                WalletBehaviorProfileView.builder()
                        .walletAddress("0xabc")
                        .recommendedExitLane("EARLY_TAKE_PROFIT")
                        .build()
        ));

        mockMvc.perform(post("/strategy/api/smart-wallet/watchlist/behavior-profiles/refresh")
                        .contextPath("/strategy/api")
                        .param("limit", "10")
                        .param("scope", "candidates"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.scope").value("candidates"))
                .andExpect(jsonPath("$.limit").value(10))
                .andExpect(jsonPath("$.persisted").value(1));
    }

    @Test
    void shouldExposeWarmWatchlistEndpoint() throws Exception {
        when(polymarketMonitorService.listWarmWatchlist()).thenReturn(List.of(
                new SmartWalletProfileEntity()
                        .setWalletAddress("0xwarm")
                        .setMonitorTier("WARM")
        ));

        mockMvc.perform(get("/strategy/api/smart-wallet/watchlist/warm")
                        .contextPath("/strategy/api"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].walletAddress").value("0xwarm"))
                .andExpect(jsonPath("$[0].monitorTier").value("WARM"));
    }

    @Test
    void shouldAllowWarmScopeForBehaviorProfiles() throws Exception {
        SmartWalletProfileEntity profile = new SmartWalletProfileEntity()
                .setWalletAddress("0xwarm")
                .setMonitorTier("WARM");
        when(polymarketMonitorService.listWarmWatchlist()).thenReturn(List.of(profile));
        when(walletBehaviorProfileService.buildProfiles(any(), eq(10))).thenReturn(List.of(
                WalletBehaviorProfileView.builder()
                        .walletAddress("0xwarm")
                        .recommendedExitLane("OBSERVE_ONLY")
                        .build()
        ));

        mockMvc.perform(get("/strategy/api/smart-wallet/watchlist/behavior-profiles")
                        .contextPath("/strategy/api")
                        .param("limit", "10")
                        .param("scope", "warm"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].walletAddress").value("0xwarm"))
                .andExpect(jsonPath("$[0].recommendedExitLane").value("OBSERVE_ONLY"));

        verify(polymarketMonitorService).listWarmWatchlist();
    }

    @Test
    void shouldExposeEnrichedWatchlistArchetypesEndpoint() throws Exception {
        SmartWalletProfileEntity profile = new SmartWalletProfileEntity()
                .setWalletAddress("0xabc");
        when(polymarketMonitorService.listQualifiedCandidates()).thenReturn(List.of(profile));
        when(walletArchetypeClassifierService.classify("0xabc", profile)).thenReturn(WalletArchetypeView.builder()
                .walletAddress("0xabc")
                .archetype("SWING")
                .followLane("SWING_FOLLOW")
                .build());
        when(walletBehaviorProfileService.buildProfile(profile)).thenReturn(WalletBehaviorProfileView.builder()
                .walletAddress("0xabc")
                .recommendedExitLane("EARLY_TAKE_PROFIT")
                .replicabilityScore(new java.math.BigDecimal("0.72"))
                .earlyExitRate(new java.math.BigDecimal("0.40"))
                .earlySettlementRate(new java.math.BigDecimal("0.40"))
                .build());

        mockMvc.perform(get("/strategy/api/smart-wallet/watchlist/archetypes")
                        .contextPath("/strategy/api")
                        .param("limit", "10")
                        .param("scope", "candidates"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].walletAddress").value("0xabc"))
                .andExpect(jsonPath("$[0].recommendedExitLane").value("EARLY_TAKE_PROFIT"))
                .andExpect(jsonPath("$[0].replicabilityScore").value(0.72))
                .andExpect(jsonPath("$[0].earlyExitRate").value(0.40));
    }

    @Test
    void shouldExportEnrichedWatchlistArchetypesCsv() throws Exception {
        SmartWalletProfileEntity profile = new SmartWalletProfileEntity()
                .setWalletAddress("0xabc");
        when(polymarketMonitorService.listQualifiedCandidates()).thenReturn(List.of(profile));
        when(walletArchetypeClassifierService.classify("0xabc", profile)).thenReturn(WalletArchetypeView.builder()
                .walletAddress("0xabc")
                .archetype("SWING")
                .followLane("SWING_FOLLOW")
                .build());
        when(walletBehaviorProfileService.buildProfile(profile)).thenReturn(WalletBehaviorProfileView.builder()
                .walletAddress("0xabc")
                .recommendedExitLane("EARLY_TAKE_PROFIT")
                .replicabilityScore(new java.math.BigDecimal("0.72"))
                .earlyExitRate(new java.math.BigDecimal("0.40"))
                .earlySettlementRate(new java.math.BigDecimal("0.40"))
                .summary("Wallet behaves like a non-high-frequency trader.")
                .build());

        mockMvc.perform(get("/strategy/api/smart-wallet/watchlist/archetypes/export")
                        .contextPath("/strategy/api")
                        .param("limit", "10")
                        .param("scope", "candidates"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("walletAddress")))
                .andExpect(content().string(containsString("recommendedExitLane")))
                .andExpect(content().string(containsString("EARLY_TAKE_PROFIT")))
                .andExpect(content().string(containsString("0xabc")));
    }

    @Test
    void shouldUpdateProfileOverride() throws Exception {
        SmartWalletProfileEntity profile = new SmartWalletProfileEntity()
                .setWalletAddress("0xabc")
                .setManualWatchEnabled(Boolean.FALSE)
                .setManualRecommendedExitLane("HOLD_TO_SETTLEMENT");
        when(smartWalletProfileStoreService.updateManualOverride(eq("0xabc"), any())).thenReturn(profile);

        mockMvc.perform(post("/strategy/api/smart-wallet/profiles/0xabc/override")
                        .contextPath("/strategy/api")
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("{\"manualWatchEnabled\":false,\"manualRecommendedExitLane\":\"HOLD_TO_SETTLEMENT\",\"note\":\"manual ops\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.walletAddress").value("0xabc"))
                .andExpect(jsonPath("$.manualWatchEnabled").value(false))
                .andExpect(jsonPath("$.manualRecommendedExitLane").value("HOLD_TO_SETTLEMENT"));
    }

    @Test
    void shouldBatchUpdateProfileOverrides() throws Exception {
        when(smartWalletProfileStoreService.batchUpdateManualOverrides(any())).thenReturn(
                SmartWalletProfileBatchOverrideResultView.builder()
                        .requestedCount(3)
                        .uniqueWalletCount(2)
                        .updatedCount(1)
                        .missingCount(1)
                        .updatedWalletAddresses(List.of("0xabc"))
                        .missingWalletAddresses(List.of("0xmissing"))
                        .build()
        );

        mockMvc.perform(post("/strategy/api/smart-wallet/profiles/overrides/batch")
                        .contextPath("/strategy/api")
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("{\"walletAddresses\":[\"0xabc\",\"0xmissing\",\"0xabc\"],\"manualWatchEnabled\":false,\"note\":\"batch ops\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.requestedCount").value(3))
                .andExpect(jsonPath("$.uniqueWalletCount").value(2))
                .andExpect(jsonPath("$.updatedCount").value(1))
                .andExpect(jsonPath("$.missingCount").value(1))
                .andExpect(jsonPath("$.updatedWalletAddresses[0]").value("0xabc"))
                .andExpect(jsonPath("$.missingWalletAddresses[0]").value("0xmissing"));
    }

    @Test
    void shouldListProfileOverrides() throws Exception {
        when(smartWalletProfileStoreService.listManualOverrides(20)).thenReturn(List.of(
                SmartWalletProfileOverrideView.builder()
                        .walletAddress("0xabc")
                        .manualWatchEnabled(false)
                        .manualRecommendedExitLane("HOLD_TO_SETTLEMENT")
                        .build()
        ));

        mockMvc.perform(get("/strategy/api/smart-wallet/profiles/overrides")
                        .contextPath("/strategy/api")
                        .param("limit", "20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].walletAddress").value("0xabc"))
                .andExpect(jsonPath("$[0].manualWatchEnabled").value(false))
                .andExpect(jsonPath("$[0].manualRecommendedExitLane").value("HOLD_TO_SETTLEMENT"));
    }

    @Test
    void shouldFilterProfileOverrideCandidates() throws Exception {
        SmartWalletProfileEntity profile = new SmartWalletProfileEntity()
                .setWalletAddress("0xabc")
                .setAlias("candidate-a")
                .setQualified(Boolean.TRUE)
                .setWatchEnabled(Boolean.FALSE)
                .setManualWatchEnabled(Boolean.FALSE)
                .setManualOverrideUpdatedAt(new java.util.Date());
        when(polymarketMonitorService.listQualifiedCandidates()).thenReturn(List.of(profile));
        when(walletBehaviorProfileService.buildProfile(profile)).thenReturn(WalletBehaviorProfileView.builder()
                .walletAddress("0xabc")
                .archetype("HF")
                .followLane("OBSERVE_ONLY")
                .highFrequencyTradingAddress(true)
                .replicabilityScore(new java.math.BigDecimal("0.25"))
                .recommendedExitLane("FAST_EXIT_OR_SKIP")
                .earlyExitRate(new java.math.BigDecimal("0.70"))
                .settlementCompletionRate(new java.math.BigDecimal("0.10"))
                .build());

        mockMvc.perform(get("/strategy/api/smart-wallet/profiles/overrides/candidates")
                        .contextPath("/strategy/api")
                        .param("scope", "candidates")
                        .param("recommendedExitLane", "FAST_EXIT_OR_SKIP")
                        .param("watchEnabled", "false")
                        .param("manualOnly", "true")
                        .param("highFrequencyTradingAddress", "true")
                        .param("maxReplicabilityScore", "0.30"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].walletAddress").value("0xabc"))
                .andExpect(jsonPath("$[0].recommendedExitLane").value("FAST_EXIT_OR_SKIP"))
                .andExpect(jsonPath("$[0].manualWatchEnabled").value(false))
                .andExpect(jsonPath("$[0].highFrequencyTradingAddress").value(true));
    }

    @Test
    void shouldExposeProfileOverrideSummary() throws Exception {
        when(smartWalletProfileStoreService.summarizeManualOverrides()).thenReturn(
                SmartWalletProfileOverrideSummaryView.builder()
                        .totalOverrides(3)
                        .observeOnlyOverrides(1)
                        .forceExecutableOverrides(1)
                        .exitLaneOverrides(1)
                        .noteOnlyOverrides(1)
                        .updatedLast24h(2)
                        .exitLaneCounts(java.util.Map.of("FAST_STOP_LOSS", 1))
                        .build()
        );

        mockMvc.perform(get("/strategy/api/smart-wallet/profiles/overrides/summary")
                        .contextPath("/strategy/api"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalOverrides").value(3))
                .andExpect(jsonPath("$.observeOnlyOverrides").value(1))
                .andExpect(jsonPath("$.updatedLast24h").value(2))
                .andExpect(jsonPath("$.exitLaneCounts.FAST_STOP_LOSS").value(1));
    }

    @Test
    void shouldExportProfileOverridesCsv() throws Exception {
        when(smartWalletProfileStoreService.listManualOverrides(20)).thenReturn(List.of(
                SmartWalletProfileOverrideView.builder()
                        .walletAddress("0xabc")
                        .manualWatchEnabled(false)
                        .manualRecommendedExitLane("HOLD_TO_SETTLEMENT")
                        .manualOverrideNote("manual ops")
                        .build()
        ));

        var result = mockMvc.perform(get("/strategy/api/smart-wallet/profiles/overrides/export")
                        .contextPath("/strategy/api")
                        .param("limit", "20"))
                .andExpect(status().isOk())
                .andReturn();

        mockMvc.perform(asyncDispatch(result))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("walletAddress")))
                .andExpect(content().string(containsString("HOLD_TO_SETTLEMENT")))
                .andExpect(content().string(containsString("manual ops")));
    }
}
