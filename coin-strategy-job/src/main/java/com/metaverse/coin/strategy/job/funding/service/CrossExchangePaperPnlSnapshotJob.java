package com.metaverse.coin.strategy.job.funding.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.metaverse.coin.strategy.common.dao.mapper.CrossExchangeArbPositionMapper;
import com.metaverse.coin.strategy.common.dao.mapper.CrossExchangeRiskSnapshotMapper;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.CrossExchangeArbPositionEntity;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.CrossExchangeRiskSnapshotEntity;
import com.metaverse.coin.strategy.core.arbitrage.config.MultiExchangeFundingRateProperties;
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
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Persists Paper PnL snapshots for the observation dashboard.
 *
 * <p>Orders and funding settlements remain the source of truth. This job records periodic derived
 * snapshots so a 7-day Paper run can be reviewed as a timeline, including losing intervals.</p>
 */
@Component
@ConditionalOnProperty(prefix = "arbitrage.multi-exchange", name = "paper-pnl-snapshot-enabled",
        havingValue = "true")
public class CrossExchangePaperPnlSnapshotJob {
    static final String STAGE_PORTFOLIO = "PAPER_PNL_PORTFOLIO";
    static final String STAGE_POSITION = "PAPER_PNL_POSITION";

    private static final Logger LOG = LoggerFactory.getLogger(CrossExchangePaperPnlSnapshotJob.class);

    private final CrossExchangePaperPnlService pnlService;
    private final CrossExchangeArbPositionMapper positions;
    private final CrossExchangeRiskSnapshotMapper snapshots;
    private final MultiExchangeFundingRateProperties properties;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final AtomicBoolean running = new AtomicBoolean(false);

    @Autowired
    public CrossExchangePaperPnlSnapshotJob(CrossExchangePaperPnlService pnlService,
            CrossExchangeArbPositionMapper positions,
            CrossExchangeRiskSnapshotMapper snapshots,
            MultiExchangeFundingRateProperties properties,
            ObjectMapper objectMapper) {
        this(pnlService, positions, snapshots, properties, objectMapper, Clock.systemUTC());
    }

    CrossExchangePaperPnlSnapshotJob(CrossExchangePaperPnlService pnlService,
            CrossExchangeArbPositionMapper positions,
            CrossExchangeRiskSnapshotMapper snapshots,
            MultiExchangeFundingRateProperties properties,
            ObjectMapper objectMapper,
            Clock clock) {
        this.pnlService = pnlService;
        this.positions = positions;
        this.snapshots = snapshots;
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${arbitrage.multi-exchange.paper-pnl-snapshot-delay-ms:60000}")
    public void snapshot() {
        snapshot(Instant.now(clock));
    }

    int snapshot(Instant now) {
        if (!properties.isPaperPnlSnapshotEnabled() || !running.compareAndSet(false, true)) {
            return 0;
        }
        try {
            int rows = 0;
            CrossExchangePaperPnlService.PortfolioPnlSummary portfolio =
                    pnlService.summarizePortfolio(properties.getPaperPnlSnapshotWindowDays());
            snapshots.insert(newSnapshot(null, null, STAGE_PORTFOLIO, portfolio.totalPnl(),
                    portfolio.source(), now, portfolioDetails(portfolio)));
            rows++;

            for (CrossExchangeArbPositionEntity position : openPositions()) {
                CrossExchangePaperPnlService.PaperPnlSummary summary =
                        pnlService.summarize(position.getPositionCode());
                snapshots.insert(newSnapshot(position.getPositionCode(), position.getOpportunityCode(),
                        STAGE_POSITION, summary.totalPnl(), summary.source(), now,
                        positionDetails(position, summary)));
                rows++;
            }
            return rows;
        } catch (RuntimeException exception) {
            LOG.warn("[FundingPaperPnlSnapshot] failed to persist paper pnl snapshot: {}",
                    exception.getMessage());
            return 0;
        } finally {
            running.set(false);
        }
    }

    private List<CrossExchangeArbPositionEntity> openPositions() {
        return positions.selectList(new LambdaQueryWrapper<CrossExchangeArbPositionEntity>()
                .in(CrossExchangeArbPositionEntity::getStatus,
                        PaperHedgePosition.Status.OPENED.name(),
                        PaperHedgePosition.Status.PARTIALLY_FILLED.name(),
                        PaperHedgePosition.Status.CLOSING.name())
                .orderByDesc(CrossExchangeArbPositionEntity::getOpenedAt)
                .last("LIMIT 500"));
    }

    private Map<String, Object> portfolioDetails(
            CrossExchangePaperPnlService.PortfolioPnlSummary portfolio) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("basisPnl", portfolio.basisPnl());
        details.put("fundingIncome", portfolio.fundingIncome());
        details.put("executionCost", portfolio.estimatedExecutionCost());
        details.put("totalPnl", portfolio.totalPnl());
        details.put("positionCount", portfolio.positionCount());
        details.put("openPositionCount", portfolio.openPositionCount());
        details.put("closedPositionCount", portfolio.closedPositionCount());
        details.put("orderCount", portfolio.orderCount());
        details.put("settlementCount", portfolio.settlementCount());
        details.put("windowDays", portfolio.windowDays());
        return details;
    }

    private Map<String, Object> positionDetails(CrossExchangeArbPositionEntity position,
            CrossExchangePaperPnlService.PaperPnlSummary summary) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("symbol", safe(position.getSymbol()));
        details.put("longExchange", safe(position.getLongExchange()));
        details.put("shortExchange", safe(position.getShortExchange()));
        details.put("basisPnl", summary.basisPnl());
        details.put("fundingIncome", summary.fundingIncome());
        details.put("executionCost", summary.estimatedExecutionCost());
        details.put("totalPnl", summary.totalPnl());
        details.put("orderCount", summary.orderCount());
        details.put("settlementCount", summary.settlementCount());
        details.put("markToMarketIncluded", summary.markToMarketIncluded());
        details.put("markToMarketMessage", safe(summary.markToMarketMessage()));
        return details;
    }

    private CrossExchangeRiskSnapshotEntity newSnapshot(String positionCode, String opportunityCode,
            String stage, BigDecimal totalPnl, String source, Instant now, Map<String, Object> details) {
        BigDecimal safeTotalPnl = totalPnl == null ? BigDecimal.ZERO : totalPnl;
        Map<String, Object> payload = new LinkedHashMap<>(details);
        payload.put("source", safe(source));
        payload.put("capturedAt", now.toString());
        return new CrossExchangeRiskSnapshotEntity()
                .setPositionCode(positionCode)
                .setOpportunityCode(opportunityCode)
                .setCheckStage(stage)
                .setPassed(safeTotalPnl.signum() >= 0)
                .setReason(safeTotalPnl.signum() >= 0 ? "PROFIT_OR_FLAT" : "LOSS")
                .setDetailsJson(toJson(payload))
                .setCreatedAt(Date.from(now));
    }

    private String toJson(Map<String, Object> details) {
        try {
            return objectMapper.writeValueAsString(details);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("failed to encode paper pnl snapshot", exception);
        }
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }
}
