package com.metaverse.coin.strategy.web.client;

import com.metaverse.coin.strategy.web.model.StrategyConflictRowView;
import com.metaverse.coin.strategy.web.model.StrategyDashboardOverviewView;
import com.metaverse.coin.strategy.web.model.StrategyDetailView;
import com.metaverse.coin.strategy.web.model.StrategyFactorView;
import com.metaverse.coin.strategy.web.model.StrategyOrderRowView;
import com.metaverse.coin.strategy.web.model.StrategyPositionRowView;
import com.metaverse.coin.strategy.web.model.StrategySummaryDetailView;
import com.metaverse.coin.strategy.web.model.StrategyTimelinePointView;

import java.util.List;

public interface StrategyPlatformQueryClient {

    default StrategyDashboardOverviewView getOverview() {
        return getOverview(null);
    }

    StrategyDashboardOverviewView getOverview(String mode);

    default StrategyDetailView getStrategyDetail(String strategyCode) {
        return getStrategyDetail(strategyCode, null);
    }

    StrategyDetailView getStrategyDetail(String strategyCode, String mode);

    StrategySummaryDetailView getStrategySummary(String strategyCode, String mode);

    List<StrategyPositionRowView> getStrategyPositions(String strategyCode, String mode);

    List<StrategyPositionRowView> getStrategyNextSettlements(String strategyCode, Integer limit, String mode);

    List<StrategyOrderRowView> getStrategyOrders(String strategyCode, Integer limit, String mode);

    List<StrategyTimelinePointView> getStrategyTimeline(String strategyCode, Integer days, String mode);

    List<StrategyConflictRowView> getStrategyConflicts(String strategyCode, Integer limit, String mode);

    List<StrategyFactorView> getStrategyFactors(String strategyCode, String mode, String window);

    StrategyFactorView getStrategyFactor(String strategyCode, String factorCode, String mode, String window);
}
