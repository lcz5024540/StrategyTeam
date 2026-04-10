package com.metaverse.coin.strategy.job.smartwallet.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.metaverse.coin.strategy.common.dao.mapper.SmartWalletTradeEffectLabelMapper;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.SmartWalletTradeEffectLabelEntity;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.SmartWalletTradeEventEntity;
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

    private final SmartWalletTradeEffectLabelMapper tradeEffectLabelMapper;

    public TradeEvEstimate estimate(SmartWalletTradeEventEntity event, BigDecimal recommendedStakeUsdc) {
        if (event == null || !StringUtils.hasText(event.getMarketId())) {
            return emptyEstimate();
        }
        List<SmartWalletTradeEffectLabelEntity> labels = tradeEffectLabelMapper.selectList(new LambdaQueryWrapper<SmartWalletTradeEffectLabelEntity>()
                .eq(SmartWalletTradeEffectLabelEntity::getMarketId, event.getMarketId())
                .eq(SmartWalletTradeEffectLabelEntity::getOutcome, event.getOutcome())
                .eq(SmartWalletTradeEffectLabelEntity::getSide, normalizeSide(event.getSide()))
                .isNotNull(SmartWalletTradeEffectLabelEntity::getNetPnlRule)
                .lt(event.getOpenedAt() != null, SmartWalletTradeEffectLabelEntity::getEventAt, event.getOpenedAt())
                .orderByDesc(SmartWalletTradeEffectLabelEntity::getEventAt)
                .last("limit 20"));
        if (labels.isEmpty()) {
            return emptyEstimate();
        }

        BigDecimal totalWeight = BigDecimal.ZERO;
        BigDecimal weightedNetPnl = BigDecimal.ZERO;
        BigDecimal weightedFee = BigDecimal.ZERO;
        BigDecimal weightedSlippage = BigDecimal.ZERO;
        BigDecimal targetStake = safe(recommendedStakeUsdc);
        Date now = event.getOpenedAt() == null ? new Date() : event.getOpenedAt();

        for (int i = 0; i < labels.size(); i++) {
            SmartWalletTradeEffectLabelEntity label = labels.get(i);
            if (label == null || label.getNetPnlRule() == null) {
                continue;
            }
            BigDecimal labelStake = safe(label.getPaperStakeUsdc());
            BigDecimal scale = labelStake.compareTo(BigDecimal.ZERO) > 0 && targetStake.compareTo(BigDecimal.ZERO) > 0
                    ? targetStake.divide(labelStake, 8, RoundingMode.HALF_UP)
                    : BigDecimal.ONE;
            BigDecimal recencyWeight = recencyWeight(now, label.getEventAt(), i);
            BigDecimal slippageUsdc = firstNonNull(label.getRealizedSlippageUsdc(), label.getEstimatedSlippageUsdc());
            totalWeight = totalWeight.add(recencyWeight);
            weightedNetPnl = weightedNetPnl.add(safe(label.getNetPnlRule()).multiply(scale).multiply(recencyWeight));
            weightedFee = weightedFee.add(safe(label.getEstimatedFeeUsdc()).multiply(scale).multiply(recencyWeight));
            weightedSlippage = weightedSlippage.add(safe(slippageUsdc).multiply(scale).multiply(recencyWeight));
        }
        if (totalWeight.compareTo(BigDecimal.ZERO) <= 0) {
            return emptyEstimate();
        }
        return TradeEvEstimate.builder()
                .expectedNetEvUsdc(weightedNetPnl.divide(totalWeight, 8, RoundingMode.HALF_UP))
                .estimatedFeeUsdc(weightedFee.divide(totalWeight, 8, RoundingMode.HALF_UP))
                .estimatedSlippageUsdc(weightedSlippage.divide(totalWeight, 8, RoundingMode.HALF_UP))
                .sampleSize(labels.size())
                .build();
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

    private String normalizeSide(String side) {
        return "SELL".equalsIgnoreCase(side) ? "SELL" : "BUY";
    }

    private BigDecimal safe(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private BigDecimal firstNonNull(BigDecimal... values) {
        for (BigDecimal value : values) {
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private TradeEvEstimate emptyEstimate() {
        return TradeEvEstimate.builder()
                .expectedNetEvUsdc(BigDecimal.ZERO)
                .estimatedFeeUsdc(BigDecimal.ZERO)
                .estimatedSlippageUsdc(BigDecimal.ZERO)
                .sampleSize(0)
                .build();
    }

    @Data
    @Builder
    public static class TradeEvEstimate {
        private BigDecimal expectedNetEvUsdc;
        private BigDecimal estimatedFeeUsdc;
        private BigDecimal estimatedSlippageUsdc;
        private Integer sampleSize;
    }
}
