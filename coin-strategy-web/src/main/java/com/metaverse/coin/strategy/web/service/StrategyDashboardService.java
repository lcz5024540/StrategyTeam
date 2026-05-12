package com.metaverse.coin.strategy.web.service;

import com.metaverse.coin.strategy.web.client.StrategyPlatformQueryClient;
import com.metaverse.coin.strategy.web.model.StrategyConflictRowView;
import com.metaverse.coin.strategy.web.model.StrategyDashboardOverviewView;
import com.metaverse.coin.strategy.web.model.StrategyDetailView;
import com.metaverse.coin.strategy.web.model.StrategyFactorView;
import com.metaverse.coin.strategy.web.model.StrategyOrderRowView;
import com.metaverse.coin.strategy.web.model.StrategyPositionRowView;
import com.metaverse.coin.strategy.web.model.StrategySummaryDetailView;
import com.metaverse.coin.strategy.web.model.StrategyTimelinePointView;
import com.metaverse.coin.strategy.web.service.facade.StrategyReadFacade;
import org.springframework.stereotype.Service;

import java.util.List;

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

    @Override
    public StrategySummaryDetailView summary(String strategyCode, String mode) {
        return strategyPlatformQueryClient.getStrategySummary(strategyCode, mode);
    }

    @Override
    public List<StrategyPositionRowView> positions(String strategyCode, String mode) {
        return strategyPlatformQueryClient.getStrategyPositions(strategyCode, mode);
    }

    @Override
    public List<StrategyPositionRowView> nextSettlements(String strategyCode, Integer limit, String mode) {
        return strategyPlatformQueryClient.getStrategyNextSettlements(strategyCode, limit, mode);
    }

    @Override
    public List<StrategyOrderRowView> orders(String strategyCode, Integer limit, String mode) {
        return strategyPlatformQueryClient.getStrategyOrders(strategyCode, limit, mode);
    }

    @Override
    public List<StrategyTimelinePointView> timeline(String strategyCode, Integer days, String mode) {
        return strategyPlatformQueryClient.getStrategyTimeline(strategyCode, days, mode);
    }

    @Override
    public List<StrategyConflictRowView> conflicts(String strategyCode, Integer limit, String mode) {
        return strategyPlatformQueryClient.getStrategyConflicts(strategyCode, limit, mode);
    }

    @Override
    public List<StrategyFactorView> factors(String strategyCode, String mode, String window) {
        return strategyPlatformQueryClient.getStrategyFactors(strategyCode, mode, window);
    }

    @Override
    public StrategyFactorView factor(String strategyCode, String factorCode, String mode, String window) {
        return strategyPlatformQueryClient.getStrategyFactor(strategyCode, factorCode, mode, window);
    }
}
