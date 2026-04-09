package com.metaverse.coin.strategy.job.smartwallet.controller;

import com.metaverse.coin.strategy.job.analytics.application.StrategyAnalyticsApplicationService;
import com.metaverse.coin.strategy.job.execution.application.ExecutionApplicationService;
import com.metaverse.coin.strategy.job.follow.application.FollowTaskApplicationService;
import com.metaverse.coin.strategy.job.marketdata.application.PredictionMarketDataApplicationService;
import com.metaverse.coin.strategy.job.risk.application.RiskPolicyApplicationService;
import com.metaverse.coin.strategy.job.settlement.application.SettlementApplicationService;
import com.metaverse.coin.strategy.job.smartwallet.config.SmartWalletProperties;
import com.metaverse.coin.strategy.job.smartwallet.service.PolymarketClobExecutionService;
import com.metaverse.coin.strategy.job.smartwallet.service.PolymarketConsensusSignalService;
import com.metaverse.coin.strategy.job.smartwallet.service.PolymarketProductOverviewService;
import com.metaverse.coin.strategy.job.smartwallet.service.PolymarketSmartWalletMonitorService;
import com.metaverse.coin.strategy.job.smartwallet.service.PolymarketWalletDiscoveryService;
import com.metaverse.coin.strategy.job.smartwallet.service.PredictionMarketOpportunityService;
import com.metaverse.coin.strategy.job.smartwallet.service.SmartWalletAccountingService;
import com.metaverse.coin.strategy.job.smartwallet.service.SmartWalletClaimService;
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

import java.util.List;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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

        mockMvc.perform(get("/smart-wallet/follow-tasks").param("limit", "5"))
                .andExpect(status().isOk());

        verify(followTaskApplicationService).listTasks(5, true);
    }
}
