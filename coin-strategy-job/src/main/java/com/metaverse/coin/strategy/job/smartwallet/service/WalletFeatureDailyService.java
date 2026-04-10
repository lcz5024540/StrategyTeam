package com.metaverse.coin.strategy.job.smartwallet.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.metaverse.coin.strategy.common.dao.mapper.SmartWalletProfileMapper;
import com.metaverse.coin.strategy.common.dao.mapper.SmartWalletTradeEffectLabelMapper;
import com.metaverse.coin.strategy.common.dao.mapper.SmartWalletTradeEventMapper;
import com.metaverse.coin.strategy.common.dao.mapper.SmartWalletWalletFeatureDailyMapper;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.SmartWalletProfileEntity;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.SmartWalletTradeEffectLabelEntity;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.SmartWalletTradeEventEntity;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.SmartWalletWalletFeatureDailyEntity;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
@Slf4j
@RequiredArgsConstructor
public class WalletFeatureDailyService {

    private final SmartWalletWalletFeatureDailyMapper walletFeatureDailyMapper;
    private final SmartWalletTradeEventMapper tradeEventMapper;
    private final SmartWalletTradeEffectLabelMapper tradeEffectLabelMapper;
    private final SmartWalletProfileMapper profileMapper;

    public SmartWalletWalletFeatureDailyEntity buildFeature(String walletAddress, LocalDate featureDate) {
        if (!StringUtils.hasText(walletAddress)) {
            throw new IllegalArgumentException("walletAddress is required");
        }
        LocalDate targetDate = featureDate == null ? LocalDate.now() : featureDate;
        Date featureDay = java.sql.Date.valueOf(targetDate);
        Date start30d = toDate(targetDate.minusDays(29).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli());
        Date start7d = toDate(targetDate.minusDays(6).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli());
        Date endExclusive = toDate(targetDate.plusDays(1).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli());

        List<SmartWalletTradeEventEntity> events30d = tradeEventMapper.selectList(new LambdaQueryWrapper<SmartWalletTradeEventEntity>()
                .eq(SmartWalletTradeEventEntity::getWalletAddress, walletAddress.trim())
                .ge(SmartWalletTradeEventEntity::getOpenedAt, start30d)
                .lt(SmartWalletTradeEventEntity::getOpenedAt, endExclusive)
                .orderByDesc(SmartWalletTradeEventEntity::getOpenedAt));
        List<SmartWalletTradeEffectLabelEntity> labels30d = tradeEffectLabelMapper.selectList(new LambdaQueryWrapper<SmartWalletTradeEffectLabelEntity>()
                .eq(SmartWalletTradeEffectLabelEntity::getWalletAddress, walletAddress.trim())
                .ge(SmartWalletTradeEffectLabelEntity::getEventAt, start30d)
                .lt(SmartWalletTradeEffectLabelEntity::getEventAt, endExclusive)
                .orderByDesc(SmartWalletTradeEffectLabelEntity::getEventAt));
        SmartWalletProfileEntity profile = profileMapper.selectOne(new LambdaQueryWrapper<SmartWalletProfileEntity>()
                .eq(SmartWalletProfileEntity::getWalletAddress, walletAddress.trim())
                .last("limit 1"));

        int trades30d = events30d.size();
        int trades7d = 0;
        Set<String> markets30d = new HashSet<>();
        BigDecimal totalSize30d = BigDecimal.ZERO;
        Map<String, Integer> marketCounts = new HashMap<>();
        for (SmartWalletTradeEventEntity event : events30d) {
            if (event.getOpenedAt() != null && !event.getOpenedAt().before(start7d)) {
                trades7d++;
            }
            if (StringUtils.hasText(event.getMarketId())) {
                String marketId = event.getMarketId().trim();
                markets30d.add(marketId);
                marketCounts.merge(marketId, 1, Integer::sum);
            }
            totalSize30d = totalSize30d.add(safe(event.getSizeUsdc()));
        }

        BigDecimal pnl30d = BigDecimal.ZERO;
        int positiveCount = 0;
        int pricedLabels = 0;
        for (SmartWalletTradeEffectLabelEntity label : labels30d) {
            BigDecimal netPnl = firstNonNull(label.getNetPnlRule(), label.getNetPnl5m(), label.getNetPnl30s());
            if (netPnl == null) {
                continue;
            }
            pnl30d = pnl30d.add(netPnl);
            pricedLabels++;
            if (netPnl.compareTo(BigDecimal.ZERO) > 0) {
                positiveCount++;
            }
        }

        BigDecimal winRate30d = pricedLabels == 0
                ? safe(profile == null ? null : profile.getWinRate())
                : BigDecimal.valueOf(positiveCount).divide(BigDecimal.valueOf(pricedLabels), 8, RoundingMode.HALF_UP);
        BigDecimal avgSize30d = trades30d == 0
                ? safe(profile == null ? null : profile.getAverageSizeUsdc())
                : totalSize30d.divide(BigDecimal.valueOf(trades30d), 8, RoundingMode.HALF_UP);
        BigDecimal concentrationMarket30d = trades30d == 0
                ? BigDecimal.ZERO
                : BigDecimal.valueOf(marketCounts.values().stream().mapToInt(Integer::intValue).max().orElse(0))
                .divide(BigDecimal.valueOf(trades30d), 8, RoundingMode.HALF_UP);
        BigDecimal followableRate30d = pricedLabels == 0
                ? BigDecimal.ZERO
                : BigDecimal.valueOf(positiveCount).divide(BigDecimal.valueOf(pricedLabels), 8, RoundingMode.HALF_UP);

        SmartWalletWalletFeatureDailyEntity entity = walletFeatureDailyMapper.selectOne(new LambdaQueryWrapper<SmartWalletWalletFeatureDailyEntity>()
                .eq(SmartWalletWalletFeatureDailyEntity::getWalletAddress, walletAddress.trim())
                .eq(SmartWalletWalletFeatureDailyEntity::getFeatureDate, featureDay)
                .last("limit 1"));
        Date now = new Date();
        if (entity == null) {
            entity = new SmartWalletWalletFeatureDailyEntity()
                    .setWalletAddress(walletAddress.trim())
                    .setFeatureDate(featureDay)
                    .setCreatedAt(now);
        }
        entity.setTrades7d(trades7d)
                .setTrades30d(trades30d)
                .setMarkets30d(markets30d.size())
                .setPnl30d(pnl30d.setScale(8, RoundingMode.HALF_UP))
                .setWinRate30d(winRate30d.setScale(8, RoundingMode.HALF_UP))
                .setAvgSize30d(avgSize30d.setScale(8, RoundingMode.HALF_UP))
                .setDrawdown30d(safe(profile == null ? null : profile.getMaxDrawdownRate()).setScale(8, RoundingMode.HALF_UP))
                .setConcentrationMarket30d(concentrationMarket30d.setScale(8, RoundingMode.HALF_UP))
                .setLowLiquidityRatio30d(BigDecimal.ZERO.setScale(8, RoundingMode.HALF_UP))
                .setLateMarketRatio30d(BigDecimal.ZERO.setScale(8, RoundingMode.HALF_UP))
                .setFollowableRate30d(followableRate30d.setScale(8, RoundingMode.HALF_UP))
                .setUpdatedAt(now);
        if (entity.getId() == null) {
            walletFeatureDailyMapper.insert(entity);
        } else {
            walletFeatureDailyMapper.updateById(entity);
        }
        return entity;
    }

    public List<SmartWalletWalletFeatureDailyEntity> buildRecentFeatures(Integer walletLimit, Integer days, boolean missingOnly) {
        int safeWalletLimit = walletLimit == null ? 30 : Math.max(1, Math.min(walletLimit, 200));
        int safeDays = days == null ? 1 : Math.max(1, Math.min(days, 30));
        List<SmartWalletProfileEntity> profiles = profileMapper.selectList(new LambdaQueryWrapper<SmartWalletProfileEntity>()
                .orderByDesc(SmartWalletProfileEntity::getWalletScore)
                .orderByDesc(SmartWalletProfileEntity::getLastTradeAt)
                .last("limit " + safeWalletLimit));
        List<SmartWalletWalletFeatureDailyEntity> result = new java.util.ArrayList<>();
        for (SmartWalletProfileEntity profile : profiles) {
            if (profile == null || !StringUtils.hasText(profile.getWalletAddress())) {
                continue;
            }
            for (int offset = 0; offset < safeDays; offset++) {
                LocalDate date = LocalDate.now().minusDays(offset);
                if (missingOnly && existsFeature(profile.getWalletAddress(), date)) {
                    continue;
                }
                try {
                    result.add(buildFeature(profile.getWalletAddress(), date));
                } catch (Exception ex) {
                    log.debug("Skip wallet feature build for wallet={} date={} because {}",
                            profile.getWalletAddress(), date, ex.getMessage());
                }
            }
        }
        return result;
    }

    public List<SmartWalletWalletFeatureDailyEntity> listRecentFeatures(Integer limit) {
        int safeLimit = limit == null ? 100 : Math.max(1, Math.min(limit, 500));
        return walletFeatureDailyMapper.selectList(new LambdaQueryWrapper<SmartWalletWalletFeatureDailyEntity>()
                .orderByDesc(SmartWalletWalletFeatureDailyEntity::getFeatureDate)
                .orderByDesc(SmartWalletWalletFeatureDailyEntity::getId)
                .last("limit " + safeLimit));
    }

    @Scheduled(fixedDelayString = "${smart-wallet.polymarket.wallet-feature-daily-fixed-delay-ms:300000}")
    public void scheduledBuildRecentFeatures() {
        try {
            buildRecentFeatures(30, 1, true);
        } catch (Exception ex) {
            log.warn("Scheduled wallet-feature-daily build failed: {}", ex.getMessage());
        }
    }

    private boolean existsFeature(String walletAddress, LocalDate date) {
        Integer count = Math.toIntExact(walletFeatureDailyMapper.selectCount(new LambdaQueryWrapper<SmartWalletWalletFeatureDailyEntity>()
                .eq(SmartWalletWalletFeatureDailyEntity::getWalletAddress, walletAddress)
                .eq(SmartWalletWalletFeatureDailyEntity::getFeatureDate, java.sql.Date.valueOf(date))));
        return count > 0;
    }

    private BigDecimal safe(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private BigDecimal firstNonNull(BigDecimal primary, BigDecimal secondary, BigDecimal tertiary) {
        if (primary != null) {
            return primary;
        }
        if (secondary != null) {
            return secondary;
        }
        return tertiary;
    }

    private Date toDate(long epochMillis) {
        return new Date(epochMillis);
    }
}
