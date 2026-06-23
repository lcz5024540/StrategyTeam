package com.metaverse.coin.strategy.web.service;

import com.metaverse.coin.strategy.web.model.StrategyConflictRowView;
import com.metaverse.coin.strategy.web.model.StrategyDashboardOverviewView;
import com.metaverse.coin.strategy.web.model.StrategyDetailView;
import com.metaverse.coin.strategy.web.model.StrategyFactorView;
import com.metaverse.coin.strategy.web.model.StrategyOrderRowView;
import com.metaverse.coin.strategy.web.model.StrategyPositionRowView;
import com.metaverse.coin.strategy.web.model.StrategySummaryDetailView;
import com.metaverse.coin.strategy.web.model.StrategyTimelinePointView;
import com.metaverse.coin.strategy.web.service.facade.StrategyReadFacade;
import com.metaverse.coin.strategy.web.service.strategy.StrategyProductQueryService;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class StrategyDashboardService implements StrategyReadFacade {

    private final StrategyProductQueryService strategyProductQueryService;

    public StrategyDashboardService(StrategyProductQueryService strategyProductQueryService) {
        this.strategyProductQueryService = strategyProductQueryService;
    }

    @Override
    public StrategyDashboardOverviewView overview(String mode) {
        return strategyProductQueryService.overview(mode);
    }

    @Override
    public StrategyDetailView detail(String strategyCode, String mode) {
        return strategyProductQueryService.detail(strategyCode, mode);
    }

    @Override
    public StrategySummaryDetailView summary(String strategyCode, String mode) {
        return strategyProductQueryService.summaryDetail(strategyCode, mode);
    }

    @Override
    public List<StrategyPositionRowView> positions(String strategyCode, String mode) {
        return strategyProductQueryService.positions(strategyCode, mode);
    }

    @Override
    public List<StrategyPositionRowView> nextSettlements(String strategyCode, Integer limit, String mode) {
        return strategyProductQueryService.nextSettlements(strategyCode, limit == null ? 8 : limit, mode);
    }

    @Override
    public List<StrategyOrderRowView> orders(String strategyCode, Integer limit, String mode) {
        return strategyProductQueryService.recentOrders(strategyCode, limit == null ? 30 : limit, mode);
    }

    @Override
    public List<StrategyTimelinePointView> timeline(String strategyCode, Integer days, String mode) {
        return strategyProductQueryService.timeline(strategyCode, days == null ? 30 : days, mode);
    }

    @Override
    public List<StrategyConflictRowView> conflicts(String strategyCode, Integer limit, String mode) {
        return strategyProductQueryService.conflicts(strategyCode, limit == null ? 10 : limit, mode);
    }

    @Override
    public List<StrategyFactorView> factors(String strategyCode, String mode, String window) {
        return strategyProductQueryService.factors(strategyCode, mode, window);
    }

    @Override
    public StrategyFactorView factor(String strategyCode, String factorCode, String mode, String window) {
        return strategyProductQueryService.factor(strategyCode, factorCode, mode, window);
    }
}
