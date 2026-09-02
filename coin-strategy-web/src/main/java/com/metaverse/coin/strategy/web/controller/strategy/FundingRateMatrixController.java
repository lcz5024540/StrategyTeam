package com.metaverse.coin.strategy.web.controller.strategy;

import com.metaverse.coin.strategy.web.model.FundingRateMatrixViews;
import com.metaverse.coin.strategy.web.service.strategy.FundingRateMatrixQueryService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/strategy-platform/arbitrage-dashboard")
public class FundingRateMatrixController {

    private final FundingRateMatrixQueryService queryService;

    public FundingRateMatrixController(FundingRateMatrixQueryService queryService) {
        this.queryService = queryService;
    }

    @GetMapping("/funding-rate-matrix")
    public FundingRateMatrixViews.Envelope<FundingRateMatrixViews.Matrix> fundingRateMatrix() {
        return queryService.fundingRateMatrix();
    }
}
