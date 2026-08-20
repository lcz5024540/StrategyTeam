package com.metaverse.coin.strategy.job.funding.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.metaverse.coin.strategy.common.dao.mapper.CrossExchangeArbOpportunityMapper;
import com.metaverse.coin.strategy.common.dao.mapper.CrossExchangeArbPositionMapper;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.CrossExchangeArbOpportunityEntity;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.CrossExchangeArbPositionEntity;
import com.metaverse.coin.strategy.core.arbitrage.multi.CrossExchangeArbitragePersistenceService;
import com.metaverse.coin.strategy.core.arbitrage.multi.CrossExchangeOpportunity;
import com.metaverse.coin.strategy.core.arbitrage.multi.CrossExchangeOpportunityScanService;
import com.metaverse.coin.strategy.core.arbitrage.multi.CrossExchangePaperTradingService;
import com.metaverse.coin.strategy.core.arbitrage.multi.ExchangeHealthRegistry;
import com.metaverse.coin.strategy.core.arbitrage.multi.PaperHedgePosition;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/arbitrage/funding")
public class CrossExchangeFundingArbitrageController {
    private final CrossExchangeArbOpportunityMapper opportunities;
    private final CrossExchangeArbPositionMapper positions;
    private final CrossExchangePaperTradingService paper;
    private final CrossExchangeOpportunityScanService scanner;
    private final ExchangeHealthRegistry exchangeHealth;
    private final CrossExchangeArbitragePersistenceService persistence;

    public CrossExchangeFundingArbitrageController(CrossExchangeArbOpportunityMapper opportunities,
            CrossExchangeArbPositionMapper positions, CrossExchangePaperTradingService paper,
            CrossExchangeOpportunityScanService scanner, ExchangeHealthRegistry exchangeHealth,
            CrossExchangeArbitragePersistenceService persistence) {
        this.opportunities = opportunities;
        this.positions = positions;
        this.paper = paper;
        this.scanner = scanner;
        this.exchangeHealth = exchangeHealth;
        this.persistence = persistence;
    }

    @GetMapping("/opportunities")
    public List<CrossExchangeArbOpportunityEntity> opportunities() {
        return opportunities.selectList(new LambdaQueryWrapper<CrossExchangeArbOpportunityEntity>()
                .orderByDesc(CrossExchangeArbOpportunityEntity::getDiscoveredAt).last("LIMIT 100"));
    }

    @GetMapping("/positions")
    public List<CrossExchangeArbPositionEntity> positions() {
        return positions.selectList(new LambdaQueryWrapper<CrossExchangeArbPositionEntity>()
                .orderByDesc(CrossExchangeArbPositionEntity::getUpdatedAt).last("LIMIT 100"));
    }

    @GetMapping("/exchanges/health")
    public Map<String, Object> health() {
        return Map.of("mode", "DRY_RUN", "paused", paper.isPaused(),
                "circuitOpen", paper.isCircuitOpen(), "liveTradingEnabled", false,
                "exchanges", exchangeHealth.snapshot());
    }

    @PostMapping("/pause")
    public Map<String, Object> pause(@RequestParam(defaultValue = "true") boolean paused) {
        if (paused) {
            paper.pause();
        } else {
            paper.resume();
        }
        return health();
    }

    @PostMapping("/paper/open")
    public PaperHedgePosition paperOpen(@RequestBody PaperOpenRequest request) {
        if (request == null || request.opportunityId() == null || request.opportunityId().isBlank()
                || request.notional() == null || request.notional().signum() <= 0) {
            throw new IllegalArgumentException("opportunityId and positive notional are required");
        }
        Instant now = Instant.now();
        CrossExchangeOpportunity opportunity = scanner.approvedOpportunity(request.opportunityId(), now)
                .orElseThrow(() -> new IllegalArgumentException("approved opportunity is missing or expired"));
        return paper.open(opportunity, request.notional(), now);
    }

    @PostMapping("/paper/close")
    public PaperHedgePosition paperClose(@RequestParam String positionId) {
        return paper.close(positionId, Instant.now());
    }

    @PostMapping("/scan")
    public List<CrossExchangeOpportunity> scan(@RequestParam String symbol) {
        List<CrossExchangeOpportunity> approved = scanner.scan(symbol, Instant.now());
        scanner.lastEvaluated().forEach(persistence::saveOpportunity);
        return approved;
    }

    public record PaperOpenRequest(String opportunityId, BigDecimal notional) {
    }
}
