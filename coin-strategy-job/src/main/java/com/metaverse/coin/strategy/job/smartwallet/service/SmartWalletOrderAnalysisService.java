package com.metaverse.coin.strategy.job.smartwallet.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.metaverse.coin.strategy.common.dao.mapper.SmartWalletOrderAnalysisMapper;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.SmartWalletOrderAnalysisEntity;
import com.metaverse.coin.strategy.job.smartwallet.model.WalletOrderAnalysisView;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
@Slf4j
@RequiredArgsConstructor
public class SmartWalletOrderAnalysisService {

    private static final String SOURCE = "POLYMARKET";
    static final long ORDER_ANALYSIS_STALE_MS = 10L * 60L * 1000L;

    private final SmartWalletOrderAnalysisMapper orderAnalysisMapper;
    private final WalletOrderAggregationService walletOrderAggregationService;

    public List<SmartWalletOrderAnalysisEntity> analyzeAndPersist(String walletAddress) {
        if (!StringUtils.hasText(walletAddress)) {
            return List.of();
        }
        String normalizedWallet = walletAddress.trim();
        WalletOrderAggregationService.WalletSnapshot snapshot = walletOrderAggregationService.snapshot(normalizedWallet);
        List<WalletOrderAggregationService.ClosedOrderSnapshot> closedOrders = snapshot.getClosedOrders();
        if (closedOrders == null || closedOrders.isEmpty()) {
            return listPersisted(normalizedWallet);
        }
        Map<String, SmartWalletOrderAnalysisEntity> existingByKey = new HashMap<>();
        for (SmartWalletOrderAnalysisEntity existing : listPersisted(normalizedWallet)) {
            existingByKey.put(uniqueKey(existing.getMarketId(), existing.getOutcome(), existing.getClosedAt()), existing);
        }

        List<SmartWalletOrderAnalysisEntity> built = new ArrayList<>();
        for (WalletOrderAggregationService.ClosedOrderSnapshot order : closedOrders) {
            SmartWalletOrderAnalysisEntity entity = buildEntity(order);
            String uniqueKey = uniqueKey(entity.getMarketId(), entity.getOutcome(), entity.getClosedAt());
            SmartWalletOrderAnalysisEntity existing = existingByKey.get(uniqueKey);
            if (existing == null) {
                entity.setCreatedAt(new Date());
                entity.setUpdatedAt(new Date());
                orderAnalysisMapper.insert(entity);
                built.add(entity);
            } else {
                entity.setId(existing.getId());
                entity.setCreatedAt(existing.getCreatedAt());
                entity.setUpdatedAt(new Date());
                orderAnalysisMapper.updateById(entity);
                built.add(entity);
            }
        }

        return built.stream()
                .sorted(Comparator.comparing(SmartWalletOrderAnalysisEntity::getClosedAt,
                        Comparator.nullsLast(Comparator.reverseOrder())))
                .toList();
    }

    public List<SmartWalletOrderAnalysisEntity> listByWalletAddress(String walletAddress,
                                                                    boolean refresh,
                                                                    String exitStyle,
                                                                    Boolean isWin) {
        List<SmartWalletOrderAnalysisEntity> rows = listFreshOrRefresh(walletAddress, refresh);
        String normalizedExitStyle = StringUtils.hasText(exitStyle) ? exitStyle.trim().toUpperCase(Locale.ROOT) : null;
        return rows.stream()
                .filter(row -> normalizedExitStyle == null
                        || normalizedExitStyle.equalsIgnoreCase(defaultString(row.getExitStyle())))
                .filter(row -> isWin == null || isWin.equals(Boolean.TRUE.equals(row.getIsWin())))
                .sorted(Comparator.comparing(SmartWalletOrderAnalysisEntity::getClosedAt,
                        Comparator.nullsLast(Comparator.reverseOrder())))
                .toList();
    }

    public List<SmartWalletOrderAnalysisEntity> listFreshOrRefresh(String walletAddress, boolean refresh) {
        if (!StringUtils.hasText(walletAddress)) {
            return List.of();
        }
        if (refresh) {
            return analyzeAndPersist(walletAddress);
        }
        List<SmartWalletOrderAnalysisEntity> rows = listPersisted(walletAddress);
        if (isStale(rows)) {
            rows = analyzeAndPersist(walletAddress);
        }
        return rows == null ? List.of() : rows.stream()
                .sorted(Comparator.comparing(SmartWalletOrderAnalysisEntity::getClosedAt,
                        Comparator.nullsLast(Comparator.reverseOrder())))
                .toList();
    }

    boolean isStale(List<SmartWalletOrderAnalysisEntity> rows) {
        if (rows == null || rows.isEmpty()) {
            return true;
        }
        Date latestUpdatedAt = rows.stream()
                .map(SmartWalletOrderAnalysisEntity::getUpdatedAt)
                .filter(date -> date != null)
                .max(Date::compareTo)
                .orElse(null);
        if (latestUpdatedAt == null) {
            return true;
        }
        return System.currentTimeMillis() - latestUpdatedAt.getTime() > ORDER_ANALYSIS_STALE_MS;
    }

    public List<WalletOrderAnalysisView> toViews(List<SmartWalletOrderAnalysisEntity> rows) {
        if (rows == null || rows.isEmpty()) {
            return List.of();
        }
        return rows.stream().map(this::toView).toList();
    }

    public WalletOrderAnalysisView toView(SmartWalletOrderAnalysisEntity row) {
        if (row == null) {
            return null;
        }
        boolean earlySettlement = row.getClosedAt() != null
                && row.getMarketEndAt() != null
                && row.getClosedAt().getTime() < row.getMarketEndAt();
        return WalletOrderAnalysisView.builder()
                .walletAddress(row.getWalletAddress())
                .marketId(row.getMarketId())
                .question(row.getQuestion())
                .outcome(row.getOutcome())
                .openedAt(row.getOpenedAt())
                .closedAt(row.getClosedAt())
                .settlementAt(row.getClosedAt())
                .marketEndAt(row.getMarketEndAt())
                .investedUsdc(scale(row.getInvestedUsdc(), 4))
                .realizedPnlUsdc(scale(row.getRealizedPnlUsdc(), 4))
                .roiRate(scale(row.getRoiRate(), 4))
                .holdMinutes(scale(row.getHoldMinutes(), 2))
                .exitStyle(row.getExitStyle())
                .exitLeadMinutes(scale(row.getExitLeadMinutes(), 2))
                .isWin(Boolean.TRUE.equals(row.getIsWin()))
                .sourceConfidence(scale(legacySourceConfidence(row.getSourceConfidence()), 4))
                .pendingSettlement(false)
                .earlySettlement(earlySettlement)
                .build();
    }

    /** 单钱包最多加载的历史记录数，防止超大钱包撑爆堆内存 */
    private static final int MAX_PERSISTED_ROWS = 5000;

    private List<SmartWalletOrderAnalysisEntity> listPersisted(String walletAddress) {
        if (!StringUtils.hasText(walletAddress)) {
            return List.of();
        }
        return orderAnalysisMapper.selectList(new LambdaQueryWrapper<SmartWalletOrderAnalysisEntity>()
                .eq(SmartWalletOrderAnalysisEntity::getWalletAddress, walletAddress.trim())
                .eq(SmartWalletOrderAnalysisEntity::getSource, SOURCE)
                .orderByDesc(SmartWalletOrderAnalysisEntity::getClosedAt)
                .last("limit " + MAX_PERSISTED_ROWS));
    }

    private SmartWalletOrderAnalysisEntity buildEntity(WalletOrderAggregationService.ClosedOrderSnapshot order) {
        Date now = new Date();
        return new SmartWalletOrderAnalysisEntity()
                .setWalletAddress(order.getWalletAddress())
                .setSource(SOURCE)
                .setMarketId(order.getMarketId())
                .setQuestion(order.getQuestion())
                .setOutcome(normalizeOutcome(order.getOutcome()))
                .setOpenedAt(order.getOpenedAt())
                .setClosedAt(order.getSettlementAt())
                .setMarketEndAt(order.getMarketEndAt())
                .setAverageEntryPrice(BigDecimal.ZERO)
                .setInvestedUsdc(safe(order.getInvestedUsdc()))
                .setRealizedPnlUsdc(safe(order.getRealizedPnlUsdc()))
                .setRoiRate(order.getRoiRate() == null ? BigDecimal.ZERO : order.getRoiRate())
                .setHoldMinutes(order.getHoldMinutes())
                .setExitStyle(order.getExitStyle())
                .setExitLeadMinutes(order.getExitLeadMinutes())
                .setIsWin(Boolean.TRUE.equals(order.getIsWin()))
                .setSourceConfidence(order.getSourceConfidence())
                .setCreatedAt(now)
                .setUpdatedAt(now);
    }

    private String uniqueKey(String marketId, String outcome, Date closedAt) {
        return defaultString(marketId) + "|" + normalizeOutcome(outcome) + "|" + (closedAt == null ? "NULL" : closedAt.getTime());
    }

    private String normalizeOutcome(String outcome) {
        return StringUtils.hasText(outcome) ? outcome.trim().toUpperCase(Locale.ROOT) : "";
    }

    private String defaultString(String value) {
        return value == null ? "" : value;
    }

    private BigDecimal safe(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private BigDecimal scale(BigDecimal value, int scale) {
        return value == null ? null : value.setScale(scale, RoundingMode.HALF_UP);
    }

    private BigDecimal legacySourceConfidence(BigDecimal value) {
        BigDecimal confidence = value == null ? BigDecimal.ZERO : value;
        return confidence.min(new BigDecimal("0.5000"));
    }
}
