package com.metaverse.coin.strategy.job.smartwallet.intelligence.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.metaverse.coin.strategy.common.dao.mapper.SmartWalletProfileMapper;
import com.metaverse.coin.strategy.common.dao.mapper.WalletEntityMapMapper;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.SmartWalletProfileEntity;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.WalletEntityMapEntity;
import com.metaverse.coin.strategy.common.service.smartwallet.config.SmartWalletProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 实体智能定时任务
 *
 * 功能：每小时扫描 watchlist 中未聚类的钱包，触发聚类处理
 * 将来可扩展：实体特征快照更新、多钱包告警报告等
 *
 * 性能优化：
 * - entity_map 查询限定在 watchlist 地址范围内（IN 子句），避免全表扫描
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WalletEntityIntelligenceJob {

    private final SmartWalletProfileMapper profileMapper;
    private final WalletEntityMapMapper entityMapMapper;
    private final WalletClusteringService clusteringService;
    private final SmartWalletProperties properties;

    /**
     * 每小时扫描未聚类的 watchlist 钱包并执行聚类
     * fixedDelay = 3600000ms (1 hour)
     */
    @Scheduled(fixedDelayString = "${smart-wallet.intelligence.cluster-fixed-delay-ms:3600000}")
    public void clusterUnresolvedWatchlistWallets() {
        if (!properties.isEnabled()) {
            return;
        }

        // 获取所有 watchlist 钱包地址
        List<SmartWalletProfileEntity> watchlistProfiles = profileMapper.selectList(
                new LambdaQueryWrapper<SmartWalletProfileEntity>()
                        .eq(SmartWalletProfileEntity::getWatchEnabled, true));

        if (watchlistProfiles.isEmpty()) {
            return;
        }

        List<String> watchlistAddresses = watchlistProfiles.stream()
                .map(p -> p.getWalletAddress().toLowerCase())
                .toList();

        // 仅查询 watchlist 范围内的已聚类地址，避免全表扫描
        Set<String> alreadyClustered = entityMapMapper.selectList(
                        new LambdaQueryWrapper<WalletEntityMapEntity>()
                                .in(WalletEntityMapEntity::getWalletAddress, watchlistAddresses)
                                .select(WalletEntityMapEntity::getWalletAddress))
                .stream()
                .map(WalletEntityMapEntity::getWalletAddress)
                .collect(Collectors.toSet());

        // 筛选未聚类的钱包
        List<String> unclustered = watchlistAddresses.stream()
                .filter(addr -> !alreadyClustered.contains(addr))
                .toList();

        if (unclustered.isEmpty()) {
            log.debug("[WalletEntityIntelligenceJob] All {} watchlist wallets already clustered.", watchlistProfiles.size());
            return;
        }

        log.info("[WalletEntityIntelligenceJob] Starting clustering for {} unclustered wallets out of {} watchlist wallets.",
                unclustered.size(), watchlistProfiles.size());

        int processed = clusteringService.clusterBatch(unclustered);

        log.info("[WalletEntityIntelligenceJob] Clustering completed. processed={}/{}", processed, unclustered.size());
    }
}
