package com.metaverse.coin.strategy.job.smartwallet.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.metaverse.coin.strategy.common.dao.mapper.SmartWalletTradeEffectLabelMapper;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.SmartWalletMarketSnapshotEntity;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.SmartWalletProfileEntity;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.SmartWalletTradeEffectLabelEntity;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.SmartWalletTradeEventEntity;
import com.metaverse.coin.strategy.job.smartwallet.config.SmartWalletProperties;
import com.metaverse.coin.strategy.job.smartwallet.model.ConsensusSignalView;
import lombok.Builder;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Date;
import java.util.List;

@Service
@RequiredArgsConstructor
public class TradeEvScoringService {

    private static final BigDecimal TEN_THOUSAND = new BigDecimal("10000");

    private final SmartWalletTradeEffectLabelMapper tradeEffectLabelMapper;
    private final MarketSnapshotCollectorService marketSnapshotCollectorService;
    private final SmartWalletProperties properties;

    public TradeEvEstimate estimate(SmartWalletProfileEntity profile,
                                    SmartWalletTradeEventEntity event,
                                    ConsensusSignalView consensus,
                                    BigDecimal recommendedStakeUsdc) {
        if (event == null || !StringUtils.hasText(event.getMarketId())) {
            return emptyEstimate();
        }
        List<SmartWalletTradeEffectLabelEntity> labels = historicalLabels(event);
        HistoricalStats historical = summarizeHistorical(labels);
        SmartWalletMarketSnapshotEntity snapshot = resolveSnapshot(event);
        BigDecimal targetStake = safe(recommendedStakeUsdc);
        BigDecimal estimatedFeeUsdc = estimateFee(targetStake);
        BigDecimal estimatedSlippageUsdc = estimateSlippage(targetStake, event, snapshot, historical);
        BigDecimal rawFollowProbability = rawFollowProbability(profile, consensus, event, snapshot, historical);
        BigDecimal calibratedFollowProbability = calibrateProbability(
                rawFollowProbability,
                properties.getModelCalibration().getFollowProbabilityScale(),
                properties.getModelCalibration().getFollowProbabilityBias()
        );
        BigDecimal rawSlippageSpikeProbability = rawSlippageSpikeProbability(event, snapshot, historical);
        BigDecimal calibratedSlippageSpikeProbability = calibrateProbability(
                rawSlippageSpikeProbability,
                properties.getModelCalibration().getSlippageSpikeScale(),
                properties.getModelCalibration().getSlippageSpikeBias()
        );

        BigDecimal priorNetPnl = historical.averageNetPnlRule;
        BigDecimal confidence = sampleConfidence(historical.sampleSize);
        BigDecimal currentEdgeUsdc = currentEdgeUsdc(event, snapshot, targetStake);
        BigDecimal grossEdgePrior = priorNetPnl.max(BigDecimal.ZERO)
                .multiply(safe(properties.getBaselineModel().getGrossEdgePriorWeight()))
                .setScale(8, RoundingMode.HALF_UP);
        BigDecimal expectedGrossEdgeUsdc = blend(grossEdgePrior, currentEdgeUsdc.max(BigDecimal.ZERO), confidence);
        BigDecimal expectedNetEvUsdc = calibratedFollowProbability.multiply(expectedGrossEdgeUsdc)
                .subtract(estimatedFeeUsdc)
                .subtract(estimatedSlippageUsdc)
                .subtract(calibratedSlippageSpikeProbability.multiply(estimatedSlippageUsdc))
                .setScale(8, RoundingMode.HALF_UP);

        return TradeEvEstimate.builder()
                .expectedNetEvUsdc(expectedNetEvUsdc)
                .estimatedFeeUsdc(estimatedFeeUsdc)
                .estimatedSlippageUsdc(estimatedSlippageUsdc)
                .followProbability(rawFollowProbability)
                .calibratedFollowProbability(calibratedFollowProbability)
                .slippageSpikeProbability(calibratedSlippageSpikeProbability)
                .sampleSize(historical.sampleSize)
                .build();
    }

    private List<SmartWalletTradeEffectLabelEntity> historicalLabels(SmartWalletTradeEventEntity event) {
        return tradeEffectLabelMapper.selectList(new LambdaQueryWrapper<SmartWalletTradeEffectLabelEntity>()
                .eq(SmartWalletTradeEffectLabelEntity::getMarketId, event.getMarketId())
                .eq(SmartWalletTradeEffectLabelEntity::getOutcome, event.getOutcome())
                .eq(SmartWalletTradeEffectLabelEntity::getSide, normalizeSide(event.getSide()))
                .isNotNull(SmartWalletTradeEffectLabelEntity::getNetPnlRule)
                .lt(event.getOpenedAt() != null, SmartWalletTradeEffectLabelEntity::getEventAt, event.getOpenedAt())
                .orderByDesc(SmartWalletTradeEffectLabelEntity::getEventAt)
                .last("limit 30"));
    }

    private HistoricalStats summarizeHistorical(List<SmartWalletTradeEffectLabelEntity> labels) {
        if (labels == null || labels.isEmpty()) {
            return HistoricalStats.empty();
        }
        BigDecimal totalWeight = BigDecimal.ZERO;
        BigDecimal weightedNetPnl = BigDecimal.ZERO;
        BigDecimal weightedFee = BigDecimal.ZERO;
        BigDecimal weightedSlippage = BigDecimal.ZERO;
        BigDecimal weightedPositive = BigDecimal.ZERO;
        BigDecimal weightedSpike = BigDecimal.ZERO;
        BigDecimal weightedSlippageBps = BigDecimal.ZERO;
        Date now = new Date();
        int effectiveSamples = 0;
        for (int i = 0; i < labels.size(); i++) {
            SmartWalletTradeEffectLabelEntity label = labels.get(i);
            if (label == null || label.getNetPnlRule() == null) {
                continue;
            }
            BigDecimal weight = recencyWeight(now, label.getEventAt(), i);
            BigDecimal slippageUsdc = firstNonNull(label.getRealizedSlippageUsdc(), label.getEstimatedSlippageUsdc());
            BigDecimal slippageBps = firstNonNull(label.getRealizedSlippageBps(), impliedSlippageBps(label));
            totalWeight = totalWeight.add(weight);
            weightedNetPnl = weightedNetPnl.add(safe(label.getNetPnlRule()).multiply(weight));
            weightedFee = weightedFee.add(safe(label.getEstimatedFeeUsdc()).multiply(weight));
            weightedSlippage = weightedSlippage.add(safe(slippageUsdc).multiply(weight));
            weightedPositive = weightedPositive.add(booleanWeight(label.getIsPositiveEvRule()).multiply(weight));
            weightedSpike = weightedSpike.add(booleanWeight(isSlippageSpike(slippageBps)).multiply(weight));
            weightedSlippageBps = weightedSlippageBps.add(safe(slippageBps).multiply(weight));
            effectiveSamples++;
        }
        if (totalWeight.compareTo(BigDecimal.ZERO) <= 0) {
            return HistoricalStats.empty();
        }
        return HistoricalStats.builder()
                .sampleSize(effectiveSamples)
                .averageNetPnlRule(weightedNetPnl.divide(totalWeight, 8, RoundingMode.HALF_UP))
                .averageFeeUsdc(weightedFee.divide(totalWeight, 8, RoundingMode.HALF_UP))
                .averageSlippageUsdc(weightedSlippage.divide(totalWeight, 8, RoundingMode.HALF_UP))
                .positiveRate(weightedPositive.divide(totalWeight, 8, RoundingMode.HALF_UP))
                .slippageSpikeRate(weightedSpike.divide(totalWeight, 8, RoundingMode.HALF_UP))
                .averageSlippageBps(weightedSlippageBps.divide(totalWeight, 8, RoundingMode.HALF_UP))
                .build();
    }

    private SmartWalletMarketSnapshotEntity resolveSnapshot(SmartWalletTradeEventEntity event) {
        if (event == null || !StringUtils.hasText(event.getMarketId())) {
            return null;
        }
        Date eventAt = event.getOpenedAt() == null ? new Date() : event.getOpenedAt();
        SmartWalletMarketSnapshotEntity snapshot = marketSnapshotCollectorService.findLatestSnapshotAtOrBefore(
                event.getMarketId(),
                event.getOutcome(),
                eventAt
        );
        if (snapshot != null) {
            return snapshot;
        }
        return marketSnapshotCollectorService.findNearestSnapshotAtOrAfter(event.getMarketId(), event.getOutcome(), eventAt);
    }

    private BigDecimal estimateFee(BigDecimal recommendedStakeUsdc) {
        return safe(recommendedStakeUsdc)
                .multiply(safe(properties.getExecutionWallet().getEstimatedFeeRate()))
                .multiply(new BigDecimal("2"))
                .setScale(8, RoundingMode.HALF_UP);
    }

    private BigDecimal estimateSlippage(BigDecimal recommendedStakeUsdc,
                                        SmartWalletTradeEventEntity event,
                                        SmartWalletMarketSnapshotEntity snapshot,
                                        HistoricalStats historical) {
        BigDecimal historicalSlippage = historical.averageSlippageUsdc;
        BigDecimal microstructureSlippage = microstructureSlippage(recommendedStakeUsdc, event, snapshot);
        BigDecimal confidence = sampleConfidence(historical.sampleSize);
        if (historicalSlippage.compareTo(BigDecimal.ZERO) <= 0) {
            return microstructureSlippage;
        }
        if (microstructureSlippage.compareTo(BigDecimal.ZERO) <= 0) {
            return historicalSlippage;
        }
        return blend(historicalSlippage, microstructureSlippage, confidence).setScale(8, RoundingMode.HALF_UP);
    }

    private BigDecimal microstructureSlippage(BigDecimal recommendedStakeUsdc,
                                              SmartWalletTradeEventEntity event,
                                              SmartWalletMarketSnapshotEntity snapshot) {
        BigDecimal targetStake = safe(recommendedStakeUsdc);
        if (targetStake.compareTo(BigDecimal.ZERO) <= 0) {
            return BigDecimal.ZERO.setScale(8, RoundingMode.HALF_UP);
        }
        SmartWalletProperties.BaselineModel.Slippage config = properties.getBaselineModel().getSlippage();
        BigDecimal spreadBps = snapshot == null ? BigDecimal.ZERO : safe(snapshot.getSpreadBps());
        BigDecimal sameSideDepth = sameSideDepth(snapshot, event == null ? null : event.getSide());
        BigDecimal depthPenalty = sameSideDepth.compareTo(BigDecimal.ZERO) > 0
                ? targetStake.divide(sameSideDepth, 8, RoundingMode.HALF_UP).min(BigDecimal.ONE)
                : BigDecimal.ONE;
        BigDecimal spreadCost = targetStake.multiply(spreadBps.divide(TEN_THOUSAND, 8, RoundingMode.HALF_UP))
                .multiply(safe(config.getSpreadCostWeight()));
        BigDecimal impactCost = targetStake.multiply(depthPenalty).multiply(safe(config.getImpactCostWeight()));
        BigDecimal volatilityCost = targetStake.multiply(safe(snapshot == null ? null : snapshot.getRealizedVol30s()))
                .multiply(safe(config.getVolatilityCostWeight()));
        return spreadCost.add(impactCost).add(volatilityCost).setScale(8, RoundingMode.HALF_UP);
    }

    private BigDecimal rawFollowProbability(SmartWalletProfileEntity profile,
                                            ConsensusSignalView consensus,
                                            SmartWalletTradeEventEntity event,
                                            SmartWalletMarketSnapshotEntity snapshot,
                                            HistoricalStats historical) {
        SmartWalletProperties.BaselineModel.FollowProbability config = properties.getBaselineModel().getFollowProbability();
        BigDecimal walletScore = safe(profile == null ? null : profile.getWalletScore());
        BigDecimal consensusScore = safe(consensus == null ? null : consensus.getConsensusScore());
        BigDecimal consensusWallets = BigDecimal.valueOf(consensus == null ? 0 : consensus.getDistinctWallets());
        BigDecimal positiveRate = historical.positiveRate;
        BigDecimal triggerSizeScore = normalizedLogScale(event == null ? null : event.getSizeUsdc(), config.getTriggerSizeCapUsdc());
        BigDecimal spreadPenalty = ratio(safe(snapshot == null ? null : snapshot.getSpreadBps()), config.getSpreadPenaltyScaleBps());
        BigDecimal volatilityPenalty = ratio(safe(snapshot == null ? null : snapshot.getRealizedVol30s()), config.getVolatilityPenaltyScale());
        BigDecimal imbalanceBoost = safe(snapshot == null ? null : snapshot.getOrderImbalance()).max(BigDecimal.ZERO);
        BigDecimal distinctWalletCap = safe(config.getDistinctWalletCap()).compareTo(BigDecimal.ZERO) <= 0
                ? BigDecimal.ONE
                : safe(config.getDistinctWalletCap());
        BigDecimal score = safe(config.getIntercept())
                .add(walletScore.multiply(safe(config.getWalletScoreWeight())))
                .add(consensusScore.multiply(safe(config.getConsensusScoreWeight())))
                .add(positiveRate.multiply(safe(config.getPositiveRateWeight())))
                .add(triggerSizeScore.multiply(safe(config.getTriggerSizeWeight())))
                .add(consensusWallets.min(distinctWalletCap).divide(distinctWalletCap, 8, RoundingMode.HALF_UP)
                        .multiply(safe(config.getDistinctWalletWeight())))
                .add(imbalanceBoost.multiply(safe(config.getImbalanceWeight())))
                .subtract(spreadPenalty.multiply(safe(config.getSpreadPenaltyWeight())))
                .subtract(volatilityPenalty.multiply(safe(config.getVolatilityPenaltyWeight())));
        return sigmoid(score);
    }

    private BigDecimal rawSlippageSpikeProbability(SmartWalletTradeEventEntity event,
                                                   SmartWalletMarketSnapshotEntity snapshot,
                                                   HistoricalStats historical) {
        SmartWalletProperties.BaselineModel.SlippageSpike config = properties.getBaselineModel().getSlippageSpike();
        BigDecimal spreadRisk = ratio(safe(snapshot == null ? null : snapshot.getSpreadBps()), config.getSpreadRiskScaleBps());
        BigDecimal volatilityRisk = ratio(safe(snapshot == null ? null : snapshot.getRealizedVol30s()), config.getVolatilityRiskScale());
        BigDecimal sizeDepthRisk = positionSizeDepthRatio(event, snapshot);
        BigDecimal historicalSpikeRisk = historical.slippageSpikeRate;
        BigDecimal score = safe(config.getIntercept())
                .add(spreadRisk.multiply(safe(config.getSpreadRiskWeight())))
                .add(volatilityRisk.multiply(safe(config.getVolatilityRiskWeight())))
                .add(sizeDepthRisk.multiply(safe(config.getSizeDepthRiskWeight())))
                .add(historicalSpikeRisk.multiply(safe(config.getHistoricalSpikeWeight())));
        return sigmoid(score);
    }

    private BigDecimal currentEdgeUsdc(SmartWalletTradeEventEntity event,
                                       SmartWalletMarketSnapshotEntity snapshot,
                                       BigDecimal recommendedStakeUsdc) {
        if (event == null || snapshot == null || safe(recommendedStakeUsdc).compareTo(BigDecimal.ZERO) <= 0) {
            return BigDecimal.ZERO.setScale(8, RoundingMode.HALF_UP);
        }
        BigDecimal benchmark = firstPositive(snapshot.getMidPrice(), snapshot.getLastTradePrice());
        if (benchmark == null || safe(event.getPrice()).compareTo(BigDecimal.ZERO) <= 0) {
            return BigDecimal.ZERO.setScale(8, RoundingMode.HALF_UP);
        }
        BigDecimal priceEdge = "SELL".equalsIgnoreCase(normalizeSide(event.getSide()))
                ? safe(event.getPrice()).subtract(benchmark)
                : benchmark.subtract(safe(event.getPrice()));
        if (priceEdge.compareTo(BigDecimal.ZERO) <= 0) {
            return BigDecimal.ZERO.setScale(8, RoundingMode.HALF_UP);
        }
        BigDecimal quantity = safe(recommendedStakeUsdc).divide(safe(event.getPrice()), 8, RoundingMode.HALF_UP);
        return priceEdge.multiply(quantity).setScale(8, RoundingMode.HALF_UP);
    }

    private BigDecimal calibrateProbability(BigDecimal rawProbability, BigDecimal scale, BigDecimal bias) {
        BigDecimal clipped = clipProbability(rawProbability);
        BigDecimal logit = log(clipped.divide(BigDecimal.ONE.subtract(clipped), 12, RoundingMode.HALF_UP));
        BigDecimal transformed = safe(scale).multiply(logit).add(safe(bias));
        return sigmoid(transformed);
    }

    private BigDecimal log(BigDecimal value) {
        if (value == null || value.compareTo(BigDecimal.ZERO) <= 0) {
            return BigDecimal.ZERO;
        }
        return BigDecimal.valueOf(Math.log(value.doubleValue())).setScale(8, RoundingMode.HALF_UP);
    }

    private BigDecimal sigmoid(BigDecimal score) {
        double exp = Math.exp(-safe(score).doubleValue());
        return BigDecimal.valueOf(1D / (1D + exp)).setScale(8, RoundingMode.HALF_UP);
    }

    private BigDecimal sampleConfidence(Integer sampleSize) {
        int normalized = sampleSize == null ? 0 : Math.max(0, sampleSize);
        return BigDecimal.valueOf(Math.min(normalized, 20))
                .divide(new BigDecimal("20"), 8, RoundingMode.HALF_UP);
    }

    private BigDecimal blend(BigDecimal historical, BigDecimal liveEstimate, BigDecimal confidence) {
        BigDecimal normalizedConfidence = safe(confidence).max(BigDecimal.ZERO).min(BigDecimal.ONE);
        return safe(historical).multiply(normalizedConfidence)
                .add(safe(liveEstimate).multiply(BigDecimal.ONE.subtract(normalizedConfidence)))
                .setScale(8, RoundingMode.HALF_UP);
    }

    private BigDecimal normalizedLogScale(BigDecimal value, BigDecimal cap) {
        BigDecimal safeValue = safe(value);
        if (safeValue.compareTo(BigDecimal.ZERO) <= 0 || safe(cap).compareTo(BigDecimal.ZERO) <= 0) {
            return BigDecimal.ZERO;
        }
        double numerator = Math.log1p(safeValue.doubleValue());
        double denominator = Math.log1p(cap.doubleValue());
        if (denominator <= 0D) {
            return BigDecimal.ZERO;
        }
        return BigDecimal.valueOf(Math.min(1D, numerator / denominator)).setScale(8, RoundingMode.HALF_UP);
    }

    private BigDecimal ratio(BigDecimal numerator, BigDecimal denominator) {
        if (safe(denominator).compareTo(BigDecimal.ZERO) <= 0) {
            return BigDecimal.ZERO;
        }
        return safe(numerator).divide(denominator, 8, RoundingMode.HALF_UP).max(BigDecimal.ZERO);
    }

    private BigDecimal positionSizeDepthRatio(SmartWalletTradeEventEntity event, SmartWalletMarketSnapshotEntity snapshot) {
        BigDecimal triggerSize = safe(event == null ? null : event.getSizeUsdc());
        BigDecimal depth = sameSideDepth(snapshot, event == null ? null : event.getSide());
        if (triggerSize.compareTo(BigDecimal.ZERO) <= 0 || depth.compareTo(BigDecimal.ZERO) <= 0) {
            return triggerSize.compareTo(BigDecimal.ZERO) > 0 ? BigDecimal.ONE : BigDecimal.ZERO;
        }
        return triggerSize.divide(depth, 8, RoundingMode.HALF_UP).min(BigDecimal.ONE);
    }

    private BigDecimal sameSideDepth(SmartWalletMarketSnapshotEntity snapshot, String side) {
        if (snapshot == null) {
            return BigDecimal.ZERO;
        }
        boolean sell = "SELL".equalsIgnoreCase(normalizeSide(side));
        return safe(sell ? snapshot.getBidDepth3c() : snapshot.getAskDepth3c())
                .max(safe(sell ? snapshot.getBidDepth1c() : snapshot.getAskDepth1c()));
    }

    private BigDecimal recencyWeight(Date now, Date eventAt, int index) {
        if (eventAt == null || now == null) {
            return BigDecimal.ONE.divide(BigDecimal.valueOf(index + 1L), 8, RoundingMode.HALF_UP);
        }
        long ageHours = Math.max(0L, (now.getTime() - eventAt.getTime()) / 3_600_000L);
        BigDecimal timeDecay = BigDecimal.ONE.divide(BigDecimal.valueOf(ageHours + 1L), 8, RoundingMode.HALF_UP);
        BigDecimal rankDecay = BigDecimal.ONE.divide(BigDecimal.valueOf(index + 1L), 8, RoundingMode.HALF_UP);
        return timeDecay.multiply(rankDecay).setScale(8, RoundingMode.HALF_UP);
    }

    private BigDecimal impliedSlippageBps(SmartWalletTradeEffectLabelEntity label) {
        BigDecimal slippageUsdc = firstNonNull(label.getRealizedSlippageUsdc(), label.getEstimatedSlippageUsdc());
        BigDecimal stakeUsdc = safe(label.getPaperStakeUsdc());
        if (slippageUsdc == null || stakeUsdc.compareTo(BigDecimal.ZERO) <= 0) {
            return BigDecimal.ZERO;
        }
        return slippageUsdc.divide(stakeUsdc, 8, RoundingMode.HALF_UP)
                .multiply(TEN_THOUSAND)
                .setScale(8, RoundingMode.HALF_UP);
    }

    private boolean isSlippageSpike(BigDecimal slippageBps) {
        return safe(slippageBps).compareTo(safe(properties.getBaselineModel().getSlippageSpike().getSpikeThresholdBps())) >= 0;
    }

    private BigDecimal booleanWeight(Boolean flag) {
        return Boolean.TRUE.equals(flag) ? BigDecimal.ONE : BigDecimal.ZERO;
    }

    private BigDecimal clipProbability(BigDecimal probability) {
        return safe(probability).max(new BigDecimal("0.000001")).min(new BigDecimal("0.999999"));
    }

    private BigDecimal firstPositive(BigDecimal... values) {
        for (BigDecimal value : values) {
            if (value != null && value.compareTo(BigDecimal.ZERO) > 0) {
                return value;
            }
        }
        return null;
    }

    private BigDecimal firstNonNull(BigDecimal... values) {
        for (BigDecimal value : values) {
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private String normalizeSide(String side) {
        return "SELL".equalsIgnoreCase(side) ? "SELL" : "BUY";
    }

    private BigDecimal safe(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private TradeEvEstimate emptyEstimate() {
        return TradeEvEstimate.builder()
                .expectedNetEvUsdc(BigDecimal.ZERO)
                .estimatedFeeUsdc(BigDecimal.ZERO)
                .estimatedSlippageUsdc(BigDecimal.ZERO)
                .followProbability(BigDecimal.ZERO)
                .calibratedFollowProbability(BigDecimal.ZERO)
                .slippageSpikeProbability(BigDecimal.ZERO)
                .sampleSize(0)
                .build();
    }

    @Data
    @Builder
    public static class TradeEvEstimate {
        private BigDecimal expectedNetEvUsdc;
        private BigDecimal estimatedFeeUsdc;
        private BigDecimal estimatedSlippageUsdc;
        private BigDecimal followProbability;
        private BigDecimal calibratedFollowProbability;
        private BigDecimal slippageSpikeProbability;
        private Integer sampleSize;
    }

    @Data
    @Builder
    private static class HistoricalStats {
        private Integer sampleSize;
        private BigDecimal averageNetPnlRule;
        private BigDecimal averageFeeUsdc;
        private BigDecimal averageSlippageUsdc;
        private BigDecimal averageSlippageBps;
        private BigDecimal positiveRate;
        private BigDecimal slippageSpikeRate;

        private static HistoricalStats empty() {
            return HistoricalStats.builder()
                    .sampleSize(0)
                    .averageNetPnlRule(BigDecimal.ZERO)
                    .averageFeeUsdc(BigDecimal.ZERO)
                    .averageSlippageUsdc(BigDecimal.ZERO)
                    .averageSlippageBps(BigDecimal.ZERO)
                    .positiveRate(BigDecimal.ZERO)
                    .slippageSpikeRate(BigDecimal.ZERO)
                    .build();
        }
    }
}
