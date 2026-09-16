package com.metaverse.coin.strategy.job.funding.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.metaverse.coin.strategy.common.dao.mapper.CrossExchangeArbPositionMapper;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.CrossExchangeArbPositionEntity;
import com.metaverse.coin.strategy.core.arbitrage.config.MultiExchangeFundingRateProperties;
import com.metaverse.coin.strategy.core.arbitrage.multi.CrossExchangeOpportunity;
import com.metaverse.coin.strategy.core.arbitrage.multi.CrossExchangeOpportunityScanService;
import com.metaverse.coin.strategy.core.arbitrage.multi.CrossExchangePaperTradingService;
import com.metaverse.coin.strategy.core.arbitrage.multi.PaperHedgePosition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Opens approved opportunities in Paper mode only.
 *
 * <p>The scheduled scanner remains responsible for market discovery. This job only consumes the
 * latest in-memory approved candidates, requotes them immediately, and then delegates to
 * {@link CrossExchangePaperTradingService#open} so every attempt still passes the same risk gates as
 * manual Paper opening.
 */
@Component
@ConditionalOnProperty(prefix = "arbitrage.multi-exchange", name = "auto-paper-open-enabled",
        havingValue = "true")
public class CrossExchangeAutoPaperOpenJob {
    private static final Logger LOG = LoggerFactory.getLogger(CrossExchangeAutoPaperOpenJob.class);

    private final CrossExchangeOpportunityScanService scanner;
    private final CrossExchangePaperTradingService paper;
    private final CrossExchangeArbPositionMapper positions;
    private final MultiExchangeFundingRateProperties properties;
    private final CrossExchangeStrategyLeaseService leases;
    private final CrossExchangePaperPnlService pnlService;
    private final Clock clock;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final Map<String, Instant> failedUntil = new ConcurrentHashMap<>();

    @Autowired
    public CrossExchangeAutoPaperOpenJob(CrossExchangeOpportunityScanService scanner,
            CrossExchangePaperTradingService paper, CrossExchangeArbPositionMapper positions,
            MultiExchangeFundingRateProperties properties, CrossExchangeStrategyLeaseService leases,
            CrossExchangePaperPnlService pnlService) {
        this(scanner, paper, positions, properties, leases, pnlService, Clock.systemUTC());
    }

    CrossExchangeAutoPaperOpenJob(CrossExchangeOpportunityScanService scanner,
            CrossExchangePaperTradingService paper, CrossExchangeArbPositionMapper positions,
            MultiExchangeFundingRateProperties properties, Clock clock) {
        this(scanner, paper, positions, properties, null, null, clock);
    }

    CrossExchangeAutoPaperOpenJob(CrossExchangeOpportunityScanService scanner,
            CrossExchangePaperTradingService paper, CrossExchangeArbPositionMapper positions,
            MultiExchangeFundingRateProperties properties, CrossExchangeStrategyLeaseService leases,
            Clock clock) {
        this(scanner, paper, positions, properties, leases, null, clock);
    }

    CrossExchangeAutoPaperOpenJob(CrossExchangeOpportunityScanService scanner,
            CrossExchangePaperTradingService paper, CrossExchangeArbPositionMapper positions,
            MultiExchangeFundingRateProperties properties, CrossExchangeStrategyLeaseService leases,
            CrossExchangePaperPnlService pnlService, Clock clock) {
        this.scanner = scanner;
        this.paper = paper;
        this.positions = positions;
        this.properties = properties;
        this.leases = leases;
        this.pnlService = pnlService;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${arbitrage.multi-exchange.auto-paper-open-delay-ms:10000}")
    public void openApproved() {
        openApproved(Instant.now(clock));
    }

    int openApproved(Instant now) {
        if (!properties.isAutoPaperOpenEnabled() || paper.isPaused() || paper.isCircuitOpen()
                || !running.compareAndSet(false, true)) {
            return 0;
        }
        try {
            List<CrossExchangeOpportunity> candidates = scanner.approvedOpportunities(now).stream()
                    .filter(opportunity -> opportunity.status() == CrossExchangeOpportunity.Status.APPROVED)
                    .sorted(Comparator.comparing(this::estimatedNetYield).reversed())
                    .toList();
            if (candidates.isEmpty()) {
                return 0;
            }
            int attempts = 0;
            int opened = 0;
            Set<String> activeSymbols = activeSymbols();
            if (activeSymbols.size() >= properties.getAutoPaperMaxOpenPositions()) {
                return 0;
            }
            if (dailyLossBudgetExhausted()) {
                return 0;
            }
            for (CrossExchangeOpportunity candidate : candidates) {
                if (attempts >= properties.getAutoPaperOpenMaxPerCycle()) {
                    break;
                }
                if (activeSymbols.contains(candidate.symbol())) {
                    continue;
                }
                if (!isAllowedPair(candidate) || isCoolingDown(candidate, now)) {
                    continue;
                }
                if (!hasEnoughExecutableLiquidity(candidate)) {
                    LOG.info("[FundingAutoPaperOpen] skip low executable liquidity: id={}, symbol={}, "
                                    + "long={}, short={}, executableNotional={}, required={}",
                            candidate.opportunityId(), candidate.symbol(),
                            candidate.longQuote().exchange(), candidate.shortQuote().exchange(),
                            candidate.executableNotional(),
                            properties.getAutoPaperMinimumExecutableNotional());
                    continue;
                }
                if (paper.isCircuitOpen(candidate, now)) {
                    LOG.info("[FundingAutoPaperOpen] skip scoped circuit: id={}, symbol={}, long={}, short={}",
                            candidate.opportunityId(), candidate.symbol(),
                            candidate.longQuote().exchange(), candidate.shortQuote().exchange());
                    continue;
                }
                if (leases != null && !leases.acquireSymbol(candidate.symbol(), now)) {
                    LOG.info("[FundingAutoPaperOpen] skip lease held by another scheduler: symbol={}",
                            candidate.symbol());
                    continue;
                }
                try {
                BigDecimal requestedNotional = requestedNotional(candidate);
                if (requestedNotional.signum() <= 0) {
                    continue;
                }
                Optional<CrossExchangeOpportunity> refreshed = scanner.revalidateApprovedOpportunity(
                        candidate.opportunityId(), requestedNotional, now);
                if (refreshed.isEmpty()) {
                    LOG.info("[FundingAutoPaperOpen] skip stale opportunity: id={}, symbol={}",
                            candidate.opportunityId(), candidate.symbol());
                    markFailure(candidate, now);
                    continue;
                }
                if (paper.isCircuitOpen(refreshed.get(), now)) {
                    LOG.info("[FundingAutoPaperOpen] skip refreshed scoped circuit: id={}, symbol={}, long={}, short={}",
                            refreshed.get().opportunityId(), refreshed.get().symbol(),
                            refreshed.get().longQuote().exchange(), refreshed.get().shortQuote().exchange());
                    continue;
                }
                try {
                    attempts++;
                    PaperHedgePosition position = paper.open(refreshed.get(), requestedNotional, now);
                    activeSymbols.add(position.opportunity().symbol());
                    if (position.status() == PaperHedgePosition.Status.OPENED) {
                        opened++;
                    }
                    LOG.info("[FundingAutoPaperOpen] opened paper position: position={}, symbol={}, long={}, "
                                    + "short={}, notional={}, status={}", position.positionId(),
                            position.opportunity().symbol(),
                            position.opportunity().longQuote().exchange(),
                            position.opportunity().shortQuote().exchange(),
                            requestedNotional, position.status());
                    if (position.status() == PaperHedgePosition.Status.MANUAL_INTERVENTION) {
                        paper.openCircuit();
                        break;
                    }
                } catch (RuntimeException exception) {
                    markFailure(candidate, now);
                    LOG.warn("[FundingAutoPaperOpen] failed to open paper position: id={}, symbol={}, reason={}",
                            candidate.opportunityId(), candidate.symbol(), exception.getMessage());
                }
                } finally {
                    if (leases != null) {
                        leases.releaseSymbol(candidate.symbol());
                    }
                }
            }
            return opened;
        } finally {
            running.set(false);
        }
    }

    private BigDecimal requestedNotional(CrossExchangeOpportunity opportunity) {
        BigDecimal configured = properties.getAutoPaperOpenNotional();
        if (configured != null && configured.signum() > 0) {
            return configured.min(opportunity.executableNotional());
        }
        return opportunity.executableNotional() == null ? BigDecimal.ZERO
                : opportunity.executableNotional();
    }

    private boolean isAllowedPair(CrossExchangeOpportunity opportunity) {
        Set<String> allowed = properties.getAutoPaperAllowedPairs();
        if (allowed.isEmpty()) {
            return true;
        }
        String pair = opportunity.longQuote().exchange().name() + ">"
                + opportunity.shortQuote().exchange().name();
        return allowed.contains(pair);
    }

    private boolean hasEnoughExecutableLiquidity(CrossExchangeOpportunity opportunity) {
        BigDecimal required = properties.getAutoPaperMinimumExecutableNotional();
        if (required == null || required.signum() <= 0) {
            return true;
        }
        BigDecimal executable = opportunity.executableNotional();
        return executable != null && executable.compareTo(required) >= 0;
    }

    private boolean isCoolingDown(CrossExchangeOpportunity opportunity, Instant now) {
        Instant until = failedUntil.get(opportunity.opportunityId());
        if (until == null) {
            return false;
        }
        if (!until.isAfter(now)) {
            failedUntil.remove(opportunity.opportunityId(), until);
            return false;
        }
        return true;
    }

    private void markFailure(CrossExchangeOpportunity opportunity, Instant now) {
        failedUntil.put(opportunity.opportunityId(), now.plus(properties.getAutoPaperFailureCooldown()));
    }

    private boolean dailyLossBudgetExhausted() {
        BigDecimal budget = properties.getAutoPaperDailyNetLossBudget();
        if (pnlService == null || budget == null || budget.signum() <= 0) {
            return false;
        }
        BigDecimal totalPnl = pnlService.summarizePortfolio(1).totalPnl();
        if (totalPnl.compareTo(budget.negate()) > 0) {
            return false;
        }
        LOG.warn("[FundingAutoPaperOpen] daily loss budget exhausted: pnl={}, budget={}",
                totalPnl, budget);
        return true;
    }

    private BigDecimal estimatedNetYield(CrossExchangeOpportunity opportunity) {
        return opportunity.estimatedNetYield() == null ? BigDecimal.ZERO
                : opportunity.estimatedNetYield();
    }

    private Set<String> activeSymbols() {
        Set<String> result = new HashSet<>();
        for (PaperHedgePosition position : paper.positions()) {
            if (position.status() != PaperHedgePosition.Status.CLOSED
                    && position.status() != PaperHedgePosition.Status.ROLLED_BACK) {
                result.add(position.opportunity().symbol());
            }
        }
        if (positions != null) {
            List<CrossExchangeArbPositionEntity> active = positions.selectList(
                    new LambdaQueryWrapper<CrossExchangeArbPositionEntity>()
                            .notIn(CrossExchangeArbPositionEntity::getStatus,
                                    PaperHedgePosition.Status.CLOSED.name(),
                                    PaperHedgePosition.Status.ROLLED_BACK.name()));
            for (CrossExchangeArbPositionEntity position : active) {
                if (position.getSymbol() != null && !position.getSymbol().isBlank()) {
                    result.add(position.getSymbol());
                }
            }
        }
        return result;
    }
}
