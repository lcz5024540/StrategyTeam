package com.metaverse.coin.strategy.web.client;

import com.metaverse.coin.strategy.web.model.StrategyDashboardOverviewView;
import com.metaverse.coin.strategy.web.model.StrategyDetailView;

public interface StrategyPlatformQueryClient {

    StrategyDashboardOverviewView getOverview();

    StrategyDetailView getStrategyDetail(String strategyCode);
}
