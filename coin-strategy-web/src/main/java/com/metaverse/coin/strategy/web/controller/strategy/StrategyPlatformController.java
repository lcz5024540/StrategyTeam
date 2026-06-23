package com.metaverse.coin.strategy.web.controller.strategy;

import com.metaverse.coin.strategy.web.model.StrategyConflictRowView;
import com.metaverse.coin.strategy.web.model.StrategyDashboardOverviewView;
import com.metaverse.coin.strategy.web.model.StrategyDetailView;
import com.metaverse.coin.strategy.web.model.StrategyFactorView;
import com.metaverse.coin.strategy.web.model.StrategyOrderRowView;
import com.metaverse.coin.strategy.web.model.StrategyPositionRowView;
import com.metaverse.coin.strategy.web.model.StrategySummaryDetailView;
import com.metaverse.coin.strategy.web.model.StrategyTimelinePointView;
import com.metaverse.coin.strategy.web.service.facade.StrategyReadFacade;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/strategy-platform/strategy")
public class StrategyPlatformController {

    private final StrategyReadFacade strategyReadFacade;

    public StrategyPlatformController(StrategyReadFacade strategyReadFacade) {
        this.strategyReadFacade = strategyReadFacade;
    }

    @GetMapping("/overview")
    public StrategyDashboardOverviewView overview(@RequestParam(required = false) String mode) {
        return strategyReadFacade.overview(mode);
    }

    @GetMapping("/{strategyCode}")
    public StrategyDetailView detail(@PathVariable String strategyCode,
                                     @RequestParam(required = false) String mode) {
        return strategyReadFacade.detail(strategyCode, mode);
    }

    @GetMapping("/{strategyCode}/summary")
    public StrategySummaryDetailView summary(@PathVariable String strategyCode,
                                             @RequestParam(required = false) String mode) {
        return strategyReadFacade.summary(strategyCode, mode);
    }

    @GetMapping("/{strategyCode}/positions")
    public List<StrategyPositionRowView> positions(@PathVariable String strategyCode,
                                                   @RequestParam(required = false) String mode) {
        return strategyReadFacade.positions(strategyCode, mode);
    }

    @GetMapping("/{strategyCode}/next-settlements")
    public List<StrategyPositionRowView> nextSettlements(@PathVariable String strategyCode,
                                                         @RequestParam(defaultValue = "8") Integer limit,
                                                         @RequestParam(required = false) String mode) {
        return strategyReadFacade.nextSettlements(strategyCode, limit, mode);
    }

    @GetMapping("/{strategyCode}/orders")
    public List<StrategyOrderRowView> orders(@PathVariable String strategyCode,
                                             @RequestParam(defaultValue = "30") Integer limit,
                                             @RequestParam(required = false) String mode) {
        return strategyReadFacade.orders(strategyCode, limit, mode);
    }

    @GetMapping("/{strategyCode}/timeline")
    public List<StrategyTimelinePointView> timeline(@PathVariable String strategyCode,
                                                    @RequestParam(defaultValue = "30") Integer days,
                                                    @RequestParam(required = false) String mode) {
        return strategyReadFacade.timeline(strategyCode, days, mode);
    }

    @GetMapping("/{strategyCode}/conflicts")
    public List<StrategyConflictRowView> conflicts(@PathVariable String strategyCode,
                                                   @RequestParam(defaultValue = "10") Integer limit,
                                                   @RequestParam(required = false) String mode) {
        return strategyReadFacade.conflicts(strategyCode, limit, mode);
    }

    @GetMapping("/{strategyCode}/factors")
    public List<StrategyFactorView> factors(@PathVariable String strategyCode,
                                            @RequestParam(required = false) String mode,
                                            @RequestParam(defaultValue = "30D") String window) {
        return strategyReadFacade.factors(strategyCode, mode, window);
    }

    @GetMapping("/{strategyCode}/factors/{factorCode}")
    public StrategyFactorView factor(@PathVariable String strategyCode,
                                     @PathVariable String factorCode,
                                     @RequestParam(required = false) String mode,
                                     @RequestParam(defaultValue = "30D") String window) {
        return strategyReadFacade.factor(strategyCode, factorCode, mode, window);
    }
}
