package com.metaverse.coin.strategy.common.service.smartwallet.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.metaverse.coin.strategy.common.dao.mapper.SmartWalletOrderStatsSnapshotMapper;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.SmartWalletOrderStatsSnapshotEntity;
import com.metaverse.coin.strategy.common.service.smartwallet.model.WalletOrderStatsView;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * Phase 4.4：读取 {@code smart_wallet_order_stats_snapshot} 表。
 *
 * <p>由 job 端 {@code ProfileOrderStatsSnapshotJob} 默认 15min 一次写入。
 * 当前只持久化 window="30d"，web 端如传 window=7d/all 等且表中无记录，返回空 view（only walletAddress filled）。</p>
 */
@Service
@RequiredArgsConstructor
public class SmartWalletProfileOrderStatsReadService {

    private static final String SOURCE = "POLYMARKET";
    private static final String DEFAULT_WINDOW = "30d";

    private final SmartWalletOrderStatsSnapshotMapper orderStatsSnapshotMapper;

    public WalletOrderStatsView getStats(String walletAddress, String window) {
        if (!StringUtils.hasText(walletAddress)) {
            return null;
        }
        String safeWindow = StringUtils.hasText(window) ? window.trim() : DEFAULT_WINDOW;
        SmartWalletOrderStatsSnapshotEntity entity = orderStatsSnapshotMapper.selectOne(
                new LambdaQueryWrapper<SmartWalletOrderStatsSnapshotEntity>()
                        .eq(SmartWalletOrderStatsSnapshotEntity::getWalletAddress, walletAddress.trim())
                        .eq(SmartWalletOrderStatsSnapshotEntity::getSource, SOURCE)
                        .eq(SmartWalletOrderStatsSnapshotEntity::getWindow, safeWindow)
                        .last("limit 1"));
        if (entity == null) {
            // 返回空 view（无表行时降级返回 walletAddress + window，其余 null）；前端可据此判断 "尚未生成"
            return WalletOrderStatsView.builder()
                    .walletAddress(walletAddress.trim())
                    .window(safeWindow)
                    .build();
        }
        return WalletOrderStatsView.builder()
                .walletAddress(entity.getWalletAddress())
                .window(entity.getWindow())
                .generatedAt(entity.getGeneratedAt())
                .closedOrderCount(entity.getClosedOrderCount())
                .winCount(entity.getWinCount())
                .lossCount(entity.getLossCount())
                .drawCount(entity.getDrawCount())
                .closedWinRate(entity.getClosedWinRate())
                .closedRealizedPnlUsdc(entity.getClosedRealizedPnlUsdc())
                .activeUnrealizedPnlUsdc(entity.getActiveUnrealizedPnlUsdc())
                .netPnlUsdc(entity.getNetPnlUsdc())
                .averageRoiRate(entity.getAverageRoiRate())
                .activePositionCount(entity.getActivePositionCount())
                .pendingSettlementCount(entity.getPendingSettlementCount())
                .earlySettlementRate(entity.getEarlySettlementRate())
                .settlementWinRate(entity.getSettlementWinRate())
                .build();
    }
}
