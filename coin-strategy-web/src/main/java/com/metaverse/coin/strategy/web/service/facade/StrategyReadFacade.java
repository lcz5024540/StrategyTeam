package com.metaverse.coin.strategy.web.service.facade;

import com.metaverse.coin.strategy.web.model.StrategyDashboardOverviewView;
import com.metaverse.coin.strategy.web.model.StrategyDetailView;

public interface StrategyReadFacade {

    StrategyDashboardOverviewView overview();

    StrategyDetailView detail(String strategyCode);
}
