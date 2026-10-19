package com.metaverse.coin.strategy.web.controller.strategy;

import com.metaverse.coin.strategy.web.model.HtxTrendDashboardViews;
import com.metaverse.coin.strategy.web.service.strategy.HtxTrendDashboardQueryService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/strategy-platform/arbitrage-dashboard")
public class HtxTrendDashboardController {
    private final HtxTrendDashboardQueryService queryService;
    public HtxTrendDashboardController(HtxTrendDashboardQueryService queryService) { this.queryService = queryService; }
    @GetMapping("/htx-trend")
    public HtxTrendDashboardViews.Dashboard dashboard(
            @org.springframework.web.bind.annotation.RequestParam(defaultValue = "LIVE") String mode) {
        return queryService.dashboard(mode);
    }
}
