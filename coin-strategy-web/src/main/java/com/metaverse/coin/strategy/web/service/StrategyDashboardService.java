package com.metaverse.coin.strategy.web.service;

import com.metaverse.coin.strategy.web.client.StrategyPlatformQueryClient;
import com.metaverse.coin.strategy.web.model.StrategyDashboardOverviewView;
import com.metaverse.coin.strategy.web.model.StrategyDetailView;
import com.metaverse.coin.strategy.web.service.facade.StrategyReadFacade;
import org.springframework.stereotype.Service;

@Service
public class StrategyDashboardService implements StrategyReadFacade {

    private final StrategyPlatformQueryClient strategyPlatformQueryClient;

    public StrategyDashboardService(StrategyPlatformQueryClient strategyPlatformQueryClient) {
        this.strategyPlatformQueryClient = strategyPlatformQueryClient;
    }

    @Override
    public StrategyDashboardOverviewView overview(String mode) {
        return strategyPlatformQueryClient.getOverview(mode);
    }

    @Override
    public StrategyDetailView detail(String strategyCode, String mode) {
        return strategyPlatformQueryClient.getStrategyDetail(strategyCode, mode);
    }
}
