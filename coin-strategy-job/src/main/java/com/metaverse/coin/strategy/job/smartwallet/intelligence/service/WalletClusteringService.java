package com.metaverse.coin.strategy.job.smartwallet.intelligence.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.metaverse.coin.strategy.common.dao.mapper.WalletEntityMapMapper;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.WalletEntityMapEntity;
import com.metaverse.coin.strategy.job.smartwallet.config.SmartWalletProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.util.Date;
import java.util.List;
import java.util.Optional;

/**
 * 钱包聚类服务（Phase 1：仅实现入金来源聚类）
 *
 * 职责：
 * 1. 对单个钱包尝试进行聚类（查找其入金来源，与已有实体关联）
 * 2. 写入或更新 wallet_entity_map 表
 *
 * Phase 2 将扩展：BehaviorSimilarityClusterer
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WalletClusteringService {

    private final WalletEntityMapMapper entityMapMapper;
    private final FundingSourceClusterer fundingSourceClusterer;
    private final BehaviorSimilarityClusterer behaviorSimilarityClusterer;
    private final SmartWalletProperties properties;

    /** 入金来源聚类的置信度（高置信度，因为共同入金地址是强关联信号） */
    private static final BigDecimal FUNDING_SOURCE_CONFIDENCE = new BigDecimal("0.90");

    /**
     * 对指定钱包执行聚类尝试
     * - 如果在 wallet_entity_map 中已存在，直接返回（不重复计算）
     * - 否则尝试入金来源聚类，写入结果
     *
     * @param walletAddress 钱包地址（大小写不敏感）
     */
    public void clusterWallet(String walletAddress) {
        if (!StringUtils.hasText(walletAddress)) {
            return;
        }
        String normalized = walletAddress.toLowerCase().trim();

        // 已有记录则跳过（避免重复计算）
        Long existing = entityMapMapper.selectCount(new LambdaQueryWrapper<WalletEntityMapEntity>()
                .eq(WalletEntityMapEntity::getWalletAddress, normalized));
        if (existing != null && existing > 0) {
            return;
        }

        String polygonscanApiKey = properties.getIntelligence().getPolygonscanApiKey();
        Optional<String> fundingSource = fundingSourceClusterer.findFundingSource(normalized, polygonscanApiKey);

        if (fundingSource.isPresent() && tryClusterByFundingSource(normalized, fundingSource.get())) {
            return;
        }

        // 备选：行为相似度聚类（中置信）
        Optional<String> similarEntityId = behaviorSimilarityClusterer.findMostSimilarEntityId(normalized);
        if (similarEntityId.isPresent()) {
            tryClusterByBehaviorSimilarity(normalized, similarEntityId.get());
        } else {
            // 无法可信聚类：自身即实体，但保留 funding source 线索供后续人工/离线分析使用
            insertSelfEntity(normalized, fundingSource.orElse(null));
        }
    }

    /**
     * 根据共同入金来源地址进行聚类
     * 如果已有其他钱包使用相同入金地址，则归入同一实体
     * 否则创建新实体
     */
    private boolean tryClusterByFundingSource(String walletAddress, String fundingSource) {
        List<WalletEntityMapEntity> peers = entityMapMapper.selectList(new LambdaQueryWrapper<WalletEntityMapEntity>()
                .eq(WalletEntityMapEntity::getFundingSourceAddress, fundingSource));
        if (peers.isEmpty()) {
            return false;
        }
        if (peers.size() > 1) {
            log.info("[WalletClusteringService] Skip funding-source merge for wallet={} because source={} is already shared by {} wallets.",
                    walletAddress, fundingSource, peers.size());
            return false;
        }

        WalletEntityMapEntity peer = peers.get(0);
        BigDecimal similarity = behaviorSimilarityClusterer.computeSimilarity(walletAddress, peer.getWalletAddress());
        if (similarity.doubleValue() < behaviorSimilarityClusterer.similarityThreshold()) {
            log.info("[WalletClusteringService] Skip funding-source merge for wallet={} because similarity={} below threshold={} with peer={}.",
                    walletAddress, similarity, behaviorSimilarityClusterer.similarityThreshold(), peer.getWalletAddress());
            return false;
        }

        Date now = new Date();
        WalletEntityMapEntity entity = new WalletEntityMapEntity()
                .setWalletAddress(walletAddress)
                .setEntityId(peer.getEntityId())
                .setClusterMethod("FUNDING_SOURCE")
                .setClusterConfidence(FUNDING_SOURCE_CONFIDENCE)
                .setFundingSourceAddress(fundingSource)
                .setBehaviorSimScore(similarity)
                .setIsPrimaryWallet(false)
                .setVerified(false)
                .setCreatedAt(now)
                .setUpdatedAt(now);
        entityMapMapper.insert(entity);
        log.info("[WalletClusteringService] Clustered wallet={} into existing entity={} via funding source={} after behavior confirmation={}.",
                walletAddress, peer.getEntityId(), fundingSource, similarity);
        return true;
    }

    private void tryClusterByBehaviorSimilarity(String walletAddress, String targetEntityId) {
        Date now = new Date();
        WalletEntityMapEntity entity = new WalletEntityMapEntity()
                .setWalletAddress(walletAddress)
                .setEntityId(targetEntityId)
                .setClusterMethod("BEHAVIOR_SIM")
                .setClusterConfidence(behaviorSimilarityClusterer.getClusterConfidence())
                .setIsPrimaryWallet(false)
                .setVerified(false)
                .setCreatedAt(now)
                .setUpdatedAt(now);
        entityMapMapper.insert(entity);
        log.info("[WalletClusteringService] Clustered wallet={} into entity={} via behavior similarity.",
                walletAddress, targetEntityId);
    }

    /**
     * 将钱包注册为独立实体（entity_id = wallet_address，confidence = 0）
     */
    private void insertSelfEntity(String walletAddress, String fundingSource) {
        Date now = new Date();
        WalletEntityMapEntity entity = new WalletEntityMapEntity()
                .setWalletAddress(walletAddress)
                .setEntityId(walletAddress)  // 自身即实体
                .setClusterMethod("SELF")
                .setClusterConfidence(BigDecimal.ZERO)
                .setFundingSourceAddress(fundingSource)
                .setIsPrimaryWallet(true)
                .setVerified(false)
                .setCreatedAt(now)
                .setUpdatedAt(now);
        entityMapMapper.insert(entity);
    }

    /**
     * 批量对 watchlist 中所有未聚类的钱包进行聚类
     * 供定时任务调用
     *
     * @param walletAddresses 钱包地址列表
     * @return 处理数量
     */
    public int clusterBatch(List<String> walletAddresses) {
        int count = 0;
        for (String addr : walletAddresses) {
            try {
                clusterWallet(addr);
                count++;
            } catch (Exception e) {
                log.warn("[WalletClusteringService] Batch clustering failed for wallet={}, error={}", addr, e.getMessage());
            }
        }
        return count;
    }
}
