package com.metaverse.coin.strategy.web.service.facade;

import com.metaverse.coin.strategy.web.model.StrategyConflictRowView;
import com.metaverse.coin.strategy.web.model.StrategyDashboardOverviewView;
import com.metaverse.coin.strategy.web.model.StrategyDetailView;
import com.metaverse.coin.strategy.web.model.StrategyFactorView;
import com.metaverse.coin.strategy.web.model.StrategyOrderRowView;
import com.metaverse.coin.strategy.web.model.StrategyPositionRowView;
import com.metaverse.coin.strategy.web.model.StrategySummaryDetailView;
import com.metaverse.coin.strategy.web.model.StrategyTimelinePointView;

import java.util.List;

public interface StrategyReadFacade {

    default StrategyDashboardOverviewView overview() {
        return overview(null);
    }

    StrategyDashboardOverviewView overview(String mode);

    default StrategyDetailView detail(String strategyCode) {
        return detail(strategyCode, null);
    }

    StrategyDetailView detail(String strategyCode, String mode);

    StrategySummaryDetailView summary(String strategyCode, String mode);

    List<StrategyPositionRowView> positions(String strategyCode, String mode);

    List<StrategyPositionRowView> nextSettlements(String strategyCode, Integer limit, String mode);

    List<StrategyOrderRowView> orders(String strategyCode, Integer limit, String mode);

    List<StrategyTimelinePointView> timeline(String strategyCode, Integer days, String mode);

    List<StrategyConflictRowView> conflicts(String strategyCode, Integer limit, String mode);

    List<StrategyFactorView> factors(String strategyCode, String mode, String window);

    StrategyFactorView factor(String strategyCode, String factorCode, String mode, String window);
}
