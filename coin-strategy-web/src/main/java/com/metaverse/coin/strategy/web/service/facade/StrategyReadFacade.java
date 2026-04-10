package com.metaverse.coin.strategy.web.service.facade;

import com.metaverse.coin.strategy.web.model.StrategyDashboardOverviewView;
import com.metaverse.coin.strategy.web.model.StrategyDetailView;

public interface StrategyReadFacade {

    default StrategyDashboardOverviewView overview() {
        return overview(null);
    }

    StrategyDashboardOverviewView overview(String mode);

    default StrategyDetailView detail(String strategyCode) {
        return detail(strategyCode, null);
    }

    StrategyDetailView detail(String strategyCode, String mode);
}
