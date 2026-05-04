package com.metaverse.coin.strategy.job.smartwallet.service;

import com.metaverse.coin.strategy.common.model.arbitrage.entity.SmartWalletMarketSnapshotEntity;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.SmartWalletTradeEventEntity;
import com.metaverse.coin.strategy.job.smartwallet.config.SmartWalletProperties;
import com.metaverse.coin.strategy.job.smartwallet.model.EntryTimingDecision;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

@Service
@RequiredArgsConstructor
public class EntryTimingRiskService {

    private static final BigDecimal ONE_HUNDRED = new BigDecimal("100");
    private static final BigDecimal TEN_THOUSAND = new BigDecimal("10000");

    private final MarketSnapshotCollectorService marketSnapshotCollectorService;

    public EntryTimingDecision evaluate(SmartWalletTradeEventEntity event,
                                        SmartWalletProperties.SignalRisk risk,
                                        String marketType) {
        List<String> reasons = new ArrayList<>();
        if (event == null || risk == null || !risk.isEnableEntryTimingGate()) {
            return accepted(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                    BigDecimal.ZERO, null, "disabled");
        }
        if (!StringUtils.hasText(event.getMarketId()) || !StringUtils.hasText(event.getOutcome())) {
            reasons.add("[ENTRY_TIMING_DATA_MISSING] marketId/outcome is missing.");
            return build(false, event.getPrice(), BigDecimal.ZERO, BigDecimal.ZERO, allowedDrift(risk, marketType),
                    BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, "missing", reasons);
        }

        BigDecimal leaderPrice = safe(event.getPrice());
        if (leaderPrice.compareTo(BigDecimal.ZERO) <= 0) {
            reasons.add("[ENTRY_TIMING_DATA_MISSING] leader fill price is missing.");
            return build(false, leaderPrice, BigDecimal.ZERO, BigDecimal.ZERO, allowedDrift(risk, marketType),
                    BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, "missing", reasons);
        }

        Date now = new Date();
        SmartWalletMarketSnapshotEntity latest = marketSnapshotCollectorService.findLatestSnapshotAtOrBefore(
                event.getMarketId(), event.getOutcome(), now);
        if (isStale(latest, risk, now)) {
            try {
                latest = marketSnapshotCollectorService.collectSnapshot(event.getMarketId(), event.getOutcome());
            } catch (Exception ignored) {
                // Keep fail-closed only for missing price. If a recent snapshot is unavailable,
                // fallback to leader price so normal risk gates can still decide in paper/simulation.
            }
        }

        BigDecimal currentEntryPrice = resolveEntryPrice(latest, leaderPrice);
        BigDecimal driftRate = currentEntryPrice.subtract(leaderPrice)
                .divide(leaderPrice, 8, RoundingMode.HALF_UP);
        BigDecimal allowedDrift = allowedDrift(risk, marketType);
        BigDecimal hardMaxDrift = safe(risk.getMaxEntryPriceDriftRate(), new BigDecimal("0.05"));
        BigDecimal spreadPct = spreadPct(latest);
        BigDecimal rangePosition1h = rangePosition1h(event, latest);
        BigDecimal marketMove5mRate = marketMove5mRate(event, latest);

        boolean accepted = true;
        if (driftRate.compareTo(hardMaxDrift) > 0) {
            accepted = false;
            reasons.add("[ENTRY_CHASE_BLOCK] currentEntryPrice=" + scale(currentEntryPrice)
                    + " leaderPrice=" + scale(leaderPrice)
                    + " driftRate=" + pct(driftRate)
                    + " hardMax=" + pct(hardMaxDrift) + ".");
        } else if (driftRate.compareTo(allowedDrift) > 0) {
            accepted = false;
            reasons.add("[ENTRY_DRIFT_BLOCK] currentEntryPrice=" + scale(currentEntryPrice)
                    + " leaderPrice=" + scale(leaderPrice)
                    + " driftRate=" + pct(driftRate)
                    + " allowed=" + pct(allowedDrift)
                    + " marketType=" + marketType + ".");
        }
        if (spreadPct.compareTo(safe(risk.getMaxEntrySpreadPct(), new BigDecimal("0.04"))) > 0) {
            accepted = false;
            reasons.add("[ENTRY_SPREAD_BLOCK] spreadPct=" + pct(spreadPct)
                    + " max=" + pct(safe(risk.getMaxEntrySpreadPct(), new BigDecimal("0.04"))) + ".");
        }
        if (rangePosition1h.compareTo(safe(risk.getEntryLocalTopRangePosition(), new BigDecimal("0.85"))) > 0
                && marketMove5mRate.compareTo(safe(risk.getEntryLocalTopMove5mRate(), new BigDecimal("0.06"))) > 0) {
            accepted = false;
            reasons.add("[ENTRY_LOCAL_TOP_BLOCK] rangePosition1h=" + rangePosition1h.setScale(4, RoundingMode.HALF_UP)
                    + " marketMove5mRate=" + pct(marketMove5mRate) + ".");
        }
        if (accepted) {
            reasons.add("[ENTRY_TIMING_PASS] currentEntryPrice=" + scale(currentEntryPrice)
                    + " leaderPrice=" + scale(leaderPrice)
                    + " driftRate=" + pct(driftRate)
                    + " allowed=" + pct(allowedDrift)
                    + " spreadPct=" + pct(spreadPct) + ".");
        }
        return build(accepted, leaderPrice, currentEntryPrice, driftRate, allowedDrift, spreadPct,
                rangePosition1h, marketMove5mRate, latest == null ? "fallback" : "snapshot", reasons);
    }

    private boolean isStale(SmartWalletMarketSnapshotEntity snapshot,
                            SmartWalletProperties.SignalRisk risk,
                            Date now) {
        if (snapshot == null || snapshot.getSnapshotAt() == null) {
            return true;
        }
        int maxAgeSeconds = risk.getEntryTimingSnapshotMaxAgeSeconds() == null
                ? 180
                : Math.max(15, risk.getEntryTimingSnapshotMaxAgeSeconds());
        return now.getTime() - snapshot.getSnapshotAt().getTime() > maxAgeSeconds * 1000L;
    }

    private BigDecimal resolveEntryPrice(SmartWalletMarketSnapshotEntity snapshot, BigDecimal fallback) {
        if (snapshot == null) {
            return fallback;
        }
        BigDecimal bestAsk = safe(snapshot.getBestAsk());
        if (bestAsk.compareTo(BigDecimal.ZERO) > 0) {
            return bestAsk;
        }
        BigDecimal mid = safe(snapshot.getMidPrice());
        if (mid.compareTo(BigDecimal.ZERO) > 0) {
            return mid;
        }
        BigDecimal last = safe(snapshot.getLastTradePrice());
        return last.compareTo(BigDecimal.ZERO) > 0 ? last : fallback;
    }

    private BigDecimal spreadPct(SmartWalletMarketSnapshotEntity snapshot) {
        if (snapshot == null) {
            return BigDecimal.ZERO;
        }
        BigDecimal spreadBps = safe(snapshot.getSpreadBps());
        if (spreadBps.compareTo(BigDecimal.ZERO) > 0) {
            return spreadBps.divide(TEN_THOUSAND, 8, RoundingMode.HALF_UP);
        }
        BigDecimal bid = safe(snapshot.getBestBid());
        BigDecimal ask = safe(snapshot.getBestAsk());
        BigDecimal mid = safe(snapshot.getMidPrice());
        if (bid.compareTo(BigDecimal.ZERO) <= 0 || ask.compareTo(BigDecimal.ZERO) <= 0 || mid.compareTo(BigDecimal.ZERO) <= 0) {
            return BigDecimal.ZERO;
        }
        return ask.subtract(bid).divide(mid, 8, RoundingMode.HALF_UP);
    }

    private BigDecimal rangePosition1h(SmartWalletTradeEventEntity event, SmartWalletMarketSnapshotEntity latest) {
        if (event == null || event.getOpenedAt() == null || latest == null) {
            return BigDecimal.ZERO;
        }
        Date start = new Date(Math.max(0L, latest.getSnapshotAt().getTime() - 3600_000L));
        List<SmartWalletMarketSnapshotEntity> snapshots = marketSnapshotCollectorService.listRecentSnapshots(
                event.getMarketId(), event.getOutcome(), 200).stream()
                .filter(item -> item.getSnapshotAt() != null && !item.getSnapshotAt().before(start))
                .toList();
        BigDecimal current = safe(latest.getMidPrice(), safe(latest.getLastTradePrice()));
        BigDecimal low = snapshots.stream().map(item -> safe(item.getMidPrice(), item.getLastTradePrice()))
                .filter(value -> value.compareTo(BigDecimal.ZERO) > 0)
                .min(BigDecimal::compareTo).orElse(BigDecimal.ZERO);
        BigDecimal high = snapshots.stream().map(item -> safe(item.getMidPrice(), item.getLastTradePrice()))
                .filter(value -> value.compareTo(BigDecimal.ZERO) > 0)
                .max(BigDecimal::compareTo).orElse(BigDecimal.ZERO);
        if (current.compareTo(BigDecimal.ZERO) <= 0 || high.compareTo(low) <= 0) {
            return BigDecimal.ZERO;
        }
        return current.subtract(low).divide(high.subtract(low), 8, RoundingMode.HALF_UP)
                .max(BigDecimal.ZERO).min(BigDecimal.ONE);
    }

    private BigDecimal marketMove5mRate(SmartWalletTradeEventEntity event, SmartWalletMarketSnapshotEntity latest) {
        if (event == null || latest == null || latest.getSnapshotAt() == null) {
            return BigDecimal.ZERO;
        }
        Date target = new Date(Math.max(0L, latest.getSnapshotAt().getTime() - 300_000L));
        SmartWalletMarketSnapshotEntity base = marketSnapshotCollectorService.findLatestSnapshotAtOrBefore(
                event.getMarketId(), event.getOutcome(), target);
        BigDecimal current = safe(latest.getMidPrice(), safe(latest.getLastTradePrice()));
        BigDecimal previous = safe(base == null ? null : base.getMidPrice(), base == null ? null : base.getLastTradePrice());
        if (current.compareTo(BigDecimal.ZERO) <= 0 || previous.compareTo(BigDecimal.ZERO) <= 0) {
            return BigDecimal.ZERO;
        }
        return current.subtract(previous).divide(previous, 8, RoundingMode.HALF_UP);
    }

    private BigDecimal allowedDrift(SmartWalletProperties.SignalRisk risk, String marketType) {
        if ("FAST_EVENT".equalsIgnoreCase(marketType)) {
            return safe(risk.getFastEventMaxEntryPriceDriftRate(), new BigDecimal("0.015"));
        }
        if ("LONG_HORIZON".equalsIgnoreCase(marketType)) {
            return safe(risk.getLongHorizonMaxEntryPriceDriftRate(), new BigDecimal("0.04"));
        }
        return safe(risk.getStandardMaxEntryPriceDriftRate(), new BigDecimal("0.03"));
    }

    private EntryTimingDecision accepted(BigDecimal leaderPrice,
                                         BigDecimal currentEntryPrice,
                                         BigDecimal driftRate,
                                         BigDecimal allowedDriftRate,
                                         BigDecimal spreadPct,
                                         BigDecimal rangePosition1h,
                                         String reason,
                                         String source) {
        List<String> reasons = new ArrayList<>();
        if (StringUtils.hasText(reason)) {
            reasons.add(reason);
        }
        return build(true, leaderPrice, currentEntryPrice, driftRate, allowedDriftRate, spreadPct,
                rangePosition1h, BigDecimal.ZERO, source, reasons);
    }

    private EntryTimingDecision build(boolean accepted,
                                      BigDecimal leaderPrice,
                                      BigDecimal currentEntryPrice,
                                      BigDecimal driftRate,
                                      BigDecimal allowedDriftRate,
                                      BigDecimal spreadPct,
                                      BigDecimal rangePosition1h,
                                      BigDecimal marketMove5mRate,
                                      String source,
                                      List<String> reasons) {
        return EntryTimingDecision.builder()
                .accepted(accepted)
                .leaderPrice(leaderPrice)
                .currentEntryPrice(currentEntryPrice)
                .driftRate(driftRate)
                .allowedDriftRate(allowedDriftRate)
                .spreadPct(spreadPct)
                .rangePosition1h(rangePosition1h)
                .marketMove5mRate(marketMove5mRate)
                .snapshotSource(source)
                .reasons(reasons)
                .build();
    }

    private BigDecimal safe(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private BigDecimal safe(BigDecimal value, BigDecimal fallback) {
        return value == null ? safe(fallback) : value;
    }

    private String pct(BigDecimal value) {
        return safe(value).multiply(ONE_HUNDRED).setScale(2, RoundingMode.HALF_UP) + "%";
    }

    private BigDecimal scale(BigDecimal value) {
        return safe(value).setScale(4, RoundingMode.HALF_UP);
    }
}
