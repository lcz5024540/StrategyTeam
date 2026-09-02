package com.metaverse.coin.strategy.web.controller.strategy;

import com.metaverse.coin.strategy.web.model.ArbitrageDashboardViews;
import com.metaverse.coin.strategy.web.service.strategy.ArbitrageDashboardQueryService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.List;

@RestController
@RequestMapping("/strategy-platform/arbitrage-dashboard")
public class ArbitrageDashboardController {

    private final ArbitrageDashboardQueryService queryService;

    public ArbitrageDashboardController(ArbitrageDashboardQueryService queryService) {
        this.queryService = queryService;
    }

    @GetMapping("/overview")
    public ArbitrageDashboardViews.Envelope<ArbitrageDashboardViews.Overview> overview() {
        return queryService.overview();
    }

    @GetMapping("/opportunities")
    public ArbitrageDashboardViews.Envelope<ArbitrageDashboardViews.OpportunityPage> opportunities(
            @RequestParam(defaultValue = "CROSS_EXCHANGE_FUNDING") String kind,
            @RequestParam(required = false) String symbol, @RequestParam(required = false) String status,
            @RequestParam(required = false) BigDecimal minNetYield, @RequestParam(required = false) BigDecimal minNotional,
            @RequestParam(defaultValue = "1") int page, @RequestParam(defaultValue = "50") int size) {
        return queryService.opportunities(kind, symbol, status, minNetYield, minNotional, page, size);
    }

    @GetMapping("/funding-rate-matrix")
    public ArbitrageDashboardViews.Envelope<ArbitrageDashboardViews.FundingRateMatrix> fundingRateMatrix() {
        return queryService.fundingRateMatrix();
    }

    @GetMapping("/opportunities/{id}")
    public ArbitrageDashboardViews.Envelope<ArbitrageDashboardViews.Opportunity> opportunity(@PathVariable long id) {
        return queryService.opportunity(id);
    }

    @GetMapping("/positions")
    public ArbitrageDashboardViews.Envelope<List<ArbitrageDashboardViews.Position>> positions(
            @RequestParam(required = false) String kind, @RequestParam(required = false) String status) {
        return queryService.positions(kind, status);
    }

    @GetMapping("/positions/{positionCode}")
    public ArbitrageDashboardViews.Envelope<ArbitrageDashboardViews.Position> position(@PathVariable String positionCode) {
        return queryService.position(positionCode);
    }

    @GetMapping("/timeseries")
    public ArbitrageDashboardViews.Envelope<List<ArbitrageDashboardViews.TimePoint>> timeseries() {
        return queryService.timeseries();
    }

    @GetMapping("/market-health")
    public ArbitrageDashboardViews.Envelope<List<ArbitrageDashboardViews.MarketHealth>> marketHealth() {
        return queryService.marketHealth();
    }

    @GetMapping("/data-quality")
    public ArbitrageDashboardViews.Envelope<ArbitrageDashboardViews.DataQuality> dataQuality() {
        return queryService.dataQuality();
    }
}
