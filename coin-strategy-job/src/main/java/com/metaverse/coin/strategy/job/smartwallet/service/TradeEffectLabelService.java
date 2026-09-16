package com.metaverse.coin.strategy.job.smartwallet.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.metaverse.coin.strategy.common.dao.mapper.SmartWalletTradeEventMapper;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.SmartWalletMarketSnapshotEntity;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.SmartWalletTradeEventEntity;
import com.metaverse.coin.strategy.common.dao.mapper.SmartWalletTradeEffectLabelMapper;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.SmartWalletTradeEffectLabelEntity;
import com.metaverse.coin.strategy.common.service.smartwallet.config.SmartWalletProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

@Service
@Slf4j
@RequiredArgsConstructor
public class TradeEffectLabelService {

    private final SmartWalletTradeEffectLabelMapper tradeEffectLabelMapper;
    private final SmartWalletTradeEventMapper tradeEventMapper;
    private final MarketSnapshotCollectorService marketSnapshotCollectorService;
    private final SmartWalletProperties properties;

    public SmartWalletTradeEffectLabelEntity saveLabel(SmartWalletTradeEffectLabelEntity label) {
        if (label == null || label.getSourceEventId() == null) {
            throw new IllegalArgumentException("trade effect label requires sourceEventId");
        }
        SmartWalletTradeEffectLabelEntity existing = tradeEffectLabelMapper.selectOne(new LambdaQueryWrapper<SmartWalletTradeEffectLabelEntity>()
                .eq(SmartWalletTradeEffectLabelEntity::getSourceEventId, label.getSourceEventId())
                .last("limit 1"));
        if (existing != null) {
            label.setId(existing.getId());
            label.setCreatedAt(existing.getCreatedAt());
            label.setUpdatedAt(new Date());
            tradeEffectLabelMapper.updateById(label);
            return label;
        }
        Date now = new Date();
        label.setCreatedAt(label.getCreatedAt() == null ? now : label.getCreatedAt());
        label.setUpdatedAt(now);
        tradeEffectLabelMapper.insert(label);
        return label;
    }

    public SmartWalletTradeEffectLabelEntity getBySourceEventId(Long sourceEventId) {
        if (sourceEventId == null) {
            return null;
        }
        return tradeEffectLabelMapper.selectOne(new LambdaQueryWrapper<SmartWalletTradeEffectLabelEntity>()
                .eq(SmartWalletTradeEffectLabelEntity::getSourceEventId, sourceEventId)
                .last("limit 1"));
    }

    public List<SmartWalletTradeEffectLabelEntity> listRecentLabels(int limit) {
        return tradeEffectLabelMapper.selectList(new LambdaQueryWrapper<SmartWalletTradeEffectLabelEntity>()
                .orderByDesc(SmartWalletTradeEffectLabelEntity::getEventAt)
                .last("limit " + Math.max(1, Math.min(limit, 500))));
    }

    public SmartWalletTradeEffectLabelEntity buildLabel(Long sourceEventId) {
        if (sourceEventId == null) {
            throw new IllegalArgumentException("sourceEventId is required");
        }
        SmartWalletTradeEventEntity event = tradeEventMapper.selectById(sourceEventId);
        if (event == null) {
            throw new IllegalArgumentException("smart-wallet trade event not found: " + sourceEventId);
        }
        Date eventAt = event.getOpenedAt() == null ? new Date() : event.getOpenedAt();

        SmartWalletMarketSnapshotEntity snapshotT0 = marketSnapshotCollectorService.findLatestSnapshotAtOrBefore(
                event.getMarketId(), event.getOutcome(), eventAt);
        if (snapshotT0 == null) {
            snapshotT0 = marketSnapshotCollectorService.findNearestSnapshotAtOrAfter(
                    event.getMarketId(), event.getOutcome(), eventAt);
        }
        SmartWalletMarketSnapshotEntity snapshot10s = marketSnapshotCollectorService.findNearestSnapshotAtOrAfter(
                event.getMarketId(), event.getOutcome(), new Date(eventAt.getTime() + 10_000L));
        SmartWalletMarketSnapshotEntity snapshot30s = marketSnapshotCollectorService.findNearestSnapshotAtOrAfter(
                event.getMarketId(), event.getOutcome(), new Date(eventAt.getTime() + 30_000L));
        SmartWalletMarketSnapshotEntity snapshot60s = marketSnapshotCollectorService.findNearestSnapshotAtOrAfter(
                event.getMarketId(), event.getOutcome(), new Date(eventAt.getTime() + 60_000L));
        SmartWalletMarketSnapshotEntity snapshot5m = marketSnapshotCollectorService.findNearestSnapshotAtOrAfter(
                event.getMarketId(), event.getOutcome(), new Date(eventAt.getTime() + 5 * 60_000L));
        SmartWalletMarketSnapshotEntity snapshot15m = marketSnapshotCollectorService.findNearestSnapshotAtOrAfter(
                event.getMarketId(), event.getOutcome(), new Date(eventAt.getTime() + 15 * 60_000L));

        BigDecimal entryPrice = firstPositive(
                safe(event.getPrice()),
                snapshotMid(snapshotT0),
                snapshotLastTrade(snapshotT0)
        );
        BigDecimal stakeUsdc = resolvePaperStake(event);
        BigDecimal estimatedFeeRate = safe(properties.getExecutionWallet().getEstimatedFeeRate());
        BigDecimal estimatedSlippageRate = safe(properties.getExecutionWallet().getPriceSlippageRate());
        BigDecimal estimatedFeeUsdc = roundCost(stakeUsdc.multiply(estimatedFeeRate).multiply(new BigDecimal("2")));
        BigDecimal estimatedSlippageUsdc = roundCost(stakeUsdc.multiply(estimatedSlippageRate).multiply(new BigDecimal("2")));
        BigDecimal realizedSlippageUsdc = realizedSlippageUsdc(normalizeSide(event.getSide()), stakeUsdc, entryPrice, snapshotMid(snapshotT0));
        BigDecimal realizedSlippageBps = realizedSlippageBps(normalizeSide(event.getSide()), entryPrice, snapshotMid(snapshotT0));
        BigDecimal slippageCostForNetPnl = firstNonNull(realizedSlippageUsdc, estimatedSlippageUsdc);

        SmartWalletTradeEffectLabelEntity label = new SmartWalletTradeEffectLabelEntity()
                .setSourceEventId(event.getId())
                .setWalletAddress(event.getWalletAddress())
                .setMarketId(event.getMarketId())
                .setOutcome(event.getOutcome())
                .setSide(normalizeSide(event.getSide()))
                .setEventAt(eventAt)
                .setMidT0(snapshotMid(snapshotT0))
                .setMidT10s(snapshotMid(snapshot10s))
                .setMidT30s(snapshotMid(snapshot30s))
                .setMidT60s(snapshotMid(snapshot60s))
                .setMidT5m(snapshotMid(snapshot5m))
                .setMidT15m(snapshotMid(snapshot15m))
                .setLastTradeT10s(snapshotLastTrade(snapshot10s))
                .setLastTradeT30s(snapshotLastTrade(snapshot30s))
                .setLastTradeT5m(snapshotLastTrade(snapshot5m))
                .setPaperStakeUsdc(stakeUsdc)
                .setEstimatedFeeRate(estimatedFeeRate)
                .setEstimatedSlippageRate(estimatedSlippageRate)
                .setEstimatedFeeUsdc(estimatedFeeUsdc)
                .setEstimatedSlippageUsdc(estimatedSlippageUsdc)
                .setRealizedSlippageBps(realizedSlippageBps)
                .setRealizedSlippageUsdc(realizedSlippageUsdc)
                .setPaperEntryPrice(entryPrice)
                .setPaperExitPrice30s(firstPositive(snapshotMid(snapshot30s), snapshotLastTrade(snapshot30s)))
                .setPaperExitPrice5m(firstPositive(snapshotMid(snapshot5m), snapshotLastTrade(snapshot5m)))
                .setPaperExitPriceRule(firstPositive(snapshotMid(snapshot15m), snapshotLastTrade(snapshot15m)));

        label.setPaperPnl30s(grossPnl(label.getSide(), label.getPaperStakeUsdc(), label.getPaperEntryPrice(), label.getPaperExitPrice30s()));
        label.setPaperPnl5m(grossPnl(label.getSide(), label.getPaperStakeUsdc(), label.getPaperEntryPrice(), label.getPaperExitPrice5m()));
        label.setPaperPnlRule(grossPnl(label.getSide(), label.getPaperStakeUsdc(), label.getPaperEntryPrice(), label.getPaperExitPriceRule()));
        label.setNetPnl30s(netPnl(label.getPaperPnl30s(), estimatedFeeUsdc, slippageCostForNetPnl));
        label.setNetPnl5m(netPnl(label.getPaperPnl5m(), estimatedFeeUsdc, slippageCostForNetPnl));
        label.setNetPnlRule(netPnl(label.getPaperPnlRule(), estimatedFeeUsdc, slippageCostForNetPnl));
        label.setIsPositiveEv30s(isPositive(label.getNetPnl30s()));
        label.setIsPositiveEv5m(isPositive(label.getNetPnl5m()));
        label.setIsPositiveEvRule(isPositive(label.getNetPnlRule()));
        return saveLabel(label);
    }

    public List<SmartWalletTradeEffectLabelEntity> buildRecentLabels(Integer limit, boolean missingOnly) {
        int normalizedLimit = limit == null ? 50 : Math.max(1, Math.min(limit, 500));
        List<SmartWalletTradeEventEntity> events = tradeEventMapper.selectList(new LambdaQueryWrapper<SmartWalletTradeEventEntity>()
                .orderByDesc(SmartWalletTradeEventEntity::getOpenedAt)
                .last("limit " + normalizedLimit));
        List<SmartWalletTradeEffectLabelEntity> result = new ArrayList<>();
        for (SmartWalletTradeEventEntity event : events) {
            if (event == null || event.getId() == null) {
                continue;
            }
            if (missingOnly && getBySourceEventId(event.getId()) != null) {
                continue;
            }
            try {
                result.add(buildLabel(event.getId()));
            } catch (Exception ignored) {
                // Keep batch backfill resilient; failed events can be retried individually later.
            }
        }
        return result;
    }

    @Scheduled(fixedDelayString = "${smart-wallet.polymarket.trade-effect-label-fixed-delay-ms:60000}")
    public void scheduledBuildRecentLabels() {
        try {
            buildRecentLabels(50, true);
        } catch (Exception ex) {
            log.warn("Scheduled trade-effect-label backfill failed: {}", ex.getMessage());
        }
    }

    private BigDecimal snapshotMid(SmartWalletMarketSnapshotEntity snapshot) {
        return snapshot == null ? null : snapshot.getMidPrice();
    }

    private BigDecimal snapshotLastTrade(SmartWalletMarketSnapshotEntity snapshot) {
        return snapshot == null ? null : snapshot.getLastTradePrice();
    }

    private BigDecimal grossPnl(String side, BigDecimal stakeUsdc, BigDecimal entryPrice, BigDecimal exitPrice) {
        if (stakeUsdc == null || stakeUsdc.compareTo(BigDecimal.ZERO) <= 0
                || entryPrice == null || exitPrice == null
                || entryPrice.compareTo(BigDecimal.ZERO) <= 0
                || exitPrice.compareTo(BigDecimal.ZERO) <= 0) {
            return null;
        }
        BigDecimal quantity = stakeUsdc.divide(entryPrice, 8, RoundingMode.HALF_UP);
        BigDecimal pnl = "SELL".equalsIgnoreCase(side)
                ? entryPrice.subtract(exitPrice).multiply(quantity)
                : exitPrice.subtract(entryPrice).multiply(quantity);
        return pnl.setScale(8, RoundingMode.HALF_UP);
    }

    private BigDecimal netPnl(BigDecimal grossPnl, BigDecimal feeUsdc, BigDecimal slippageUsdc) {
        if (grossPnl == null) {
            return null;
        }
        return grossPnl
                .subtract(safe(feeUsdc))
                .subtract(safe(slippageUsdc))
                .setScale(8, RoundingMode.HALF_UP);
    }

    private BigDecimal realizedSlippageBps(String side, BigDecimal entryPrice, BigDecimal benchmarkMidPrice) {
        BigDecimal adverseMove = adversePriceMove(side, entryPrice, benchmarkMidPrice);
        if (adverseMove == null || benchmarkMidPrice == null || benchmarkMidPrice.compareTo(BigDecimal.ZERO) <= 0) {
            return null;
        }
        return adverseMove
                .divide(benchmarkMidPrice, 8, RoundingMode.HALF_UP)
                .multiply(new BigDecimal("10000"))
                .setScale(8, RoundingMode.HALF_UP);
    }

    private BigDecimal realizedSlippageUsdc(String side,
                                            BigDecimal stakeUsdc,
                                            BigDecimal entryPrice,
                                            BigDecimal benchmarkMidPrice) {
        BigDecimal adverseMove = adversePriceMove(side, entryPrice, benchmarkMidPrice);
        if (adverseMove == null
                || stakeUsdc == null || stakeUsdc.compareTo(BigDecimal.ZERO) <= 0
                || entryPrice == null || entryPrice.compareTo(BigDecimal.ZERO) <= 0) {
            return null;
        }
        BigDecimal quantity = stakeUsdc.divide(entryPrice, 8, RoundingMode.HALF_UP);
        return adverseMove.multiply(quantity).setScale(8, RoundingMode.HALF_UP);
    }

    private BigDecimal adversePriceMove(String side, BigDecimal entryPrice, BigDecimal benchmarkMidPrice) {
        if (entryPrice == null || benchmarkMidPrice == null
                || entryPrice.compareTo(BigDecimal.ZERO) <= 0
                || benchmarkMidPrice.compareTo(BigDecimal.ZERO) <= 0) {
            return null;
        }
        BigDecimal rawMove = "SELL".equalsIgnoreCase(side)
                ? benchmarkMidPrice.subtract(entryPrice)
                : entryPrice.subtract(benchmarkMidPrice);
        BigDecimal adverseMove = rawMove.max(BigDecimal.ZERO);
        return adverseMove.compareTo(BigDecimal.ZERO) > 0
                ? adverseMove.setScale(8, RoundingMode.HALF_UP)
                : BigDecimal.ZERO.setScale(8, RoundingMode.HALF_UP);
    }

    private Boolean isPositive(BigDecimal value) {
        return value == null ? null : value.compareTo(BigDecimal.ZERO) > 0;
    }

    private String normalizeSide(String side) {
        return "SELL".equalsIgnoreCase(side) ? "SELL" : "BUY";
    }

    private BigDecimal safe(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private BigDecimal resolvePaperStake(SmartWalletTradeEventEntity event) {
        BigDecimal triggerSize = safe(event == null ? null : event.getSizeUsdc());
        BigDecimal minOrder = safe(properties.getExecutionWallet().getMinOrderUsdc());
        BigDecimal maxOrder = safe(properties.getExecutionWallet().getMaxOrderUsdc());
        if (triggerSize.compareTo(BigDecimal.ZERO) <= 0) {
            return minOrder.setScale(8, RoundingMode.HALF_UP);
        }
        BigDecimal bounded = triggerSize.max(minOrder);
        if (maxOrder.compareTo(BigDecimal.ZERO) > 0) {
            bounded = bounded.min(maxOrder);
        }
        return bounded.setScale(8, RoundingMode.HALF_UP);
    }

    private BigDecimal roundCost(BigDecimal value) {
        return safe(value).setScale(8, RoundingMode.HALF_UP);
    }

    private BigDecimal firstPositive(BigDecimal... values) {
        for (BigDecimal value : values) {
            if (value != null && value.compareTo(BigDecimal.ZERO) > 0) {
                return value.setScale(8, RoundingMode.HALF_UP);
            }
        }
        return null;
    }

    private BigDecimal firstNonNull(BigDecimal... values) {
        for (BigDecimal value : values) {
            if (value != null) {
                return value.setScale(8, RoundingMode.HALF_UP);
            }
        }
        return null;
    }

    private SmartWalletMarketSnapshotEntity firstNonNull(SmartWalletMarketSnapshotEntity... snapshots) {
        for (SmartWalletMarketSnapshotEntity snapshot : snapshots) {
            if (snapshot != null) {
                return snapshot;
            }
        }
        return null;
    }
}
