package com.metaverse.coin.strategy.web.client;

import com.metaverse.coin.strategy.web.model.StrategyDashboardOverviewView;
import com.metaverse.coin.strategy.web.model.StrategyDetailView;

public interface StrategyPlatformQueryClient {

    default StrategyDashboardOverviewView getOverview() {
        return getOverview(null);
    }

    StrategyDashboardOverviewView getOverview(String mode);

    default StrategyDetailView getStrategyDetail(String strategyCode) {
        return getStrategyDetail(strategyCode, null);
    }

    StrategyDetailView getStrategyDetail(String strategyCode, String mode);
}
