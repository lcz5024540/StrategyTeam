package com.metaverse.coin.strategy.job.smartwallet.intelligence.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.metaverse.coin.strategy.common.dao.mapper.WalletEntityMapMapper;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.WalletEntityMapEntity;
import com.metaverse.coin.strategy.job.smartwallet.intelligence.model.ClusterConfidence;
import com.metaverse.coin.strategy.job.smartwallet.intelligence.model.EntityResolutionResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 钱包地址 → 实体解析服务（核心入口）
 *
 * 解析逻辑：
 * 1. 查询 wallet_entity_map 表
 * 2. 命中 → 返回实体信息
 * 3. 未命中 → 触发异步聚类，当前请求返回 selfEntity（自身即实体）
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WalletEntityResolutionService {

    private final WalletEntityMapMapper entityMapMapper;
    private final WalletClusteringService clusteringService;

    /**
     * 解析单个钱包地址对应的实体
     *
     * @param walletAddress 钱包地址（大小写不敏感）
     * @return EntityResolutionResult，未聚类时返回 selfEntity
     */
    public EntityResolutionResult resolveEntity(String walletAddress) {
        if (!StringUtils.hasText(walletAddress)) {
            return EntityResolutionResult.selfEntity("unknown", true);
        }
        String normalized = walletAddress.toLowerCase().trim();

        WalletEntityMapEntity mapRecord = loadMapRecord(normalized);

        if (mapRecord == null) {
            clusteringService.clusterWallet(normalized);
            mapRecord = loadMapRecord(normalized);
        }

        if (mapRecord == null) {
            return EntityResolutionResult.selfEntity(walletAddress, true);
        }

        // 统计该实体下的钱包总数
        long walletCount = entityMapMapper.selectCount(new LambdaQueryWrapper<WalletEntityMapEntity>()
                .eq(WalletEntityMapEntity::getEntityId, mapRecord.getEntityId()));
        int walletCountInt = (int) Math.min(walletCount, Integer.MAX_VALUE);

        return EntityResolutionResult.builder()
                .walletAddress(walletAddress)
                .entityId(mapRecord.getEntityId())
                .confidence(ClusterConfidence.fromScore(mapRecord.getClusterConfidence()))
                .confidenceScore(mapRecord.getClusterConfidence())
                .entityWalletCount(walletCountInt)
                .multiWallet(walletCountInt > 1)
                .primaryWalletAddress(walletAddress)  // 简化：不单独查询主钱包
                .clusterMethod(mapRecord.getClusterMethod())
                .fundingSourceAddress(mapRecord.getFundingSourceAddress())
                .resolvedFromMap(true)
                .pendingClustering(false)
                .build();
    }

    /**
     * 批量解析钱包地址列表（用于共识信号实体去重）
     *
     * @param walletAddresses 钱包地址列表
     * @return Map<walletAddress, EntityResolutionResult>
     */
    public Map<String, EntityResolutionResult> resolveEntities(List<String> walletAddresses) {
        Map<String, EntityResolutionResult> result = new LinkedHashMap<>();
        if (walletAddresses == null || walletAddresses.isEmpty()) {
            return result;
        }
        for (String addr : walletAddresses) {
            result.put(addr.toLowerCase(), resolveEntity(addr));
        }
        return result;
    }

    /**
     * 强制重新聚类（用于后台任务或人工触发）
     */
    public void reCluster(String walletAddress) {
        if (!StringUtils.hasText(walletAddress)) {
            return;
        }
        String normalized = walletAddress.toLowerCase().trim();
        // 删除已有记录，重新聚类
        entityMapMapper.delete(new LambdaQueryWrapper<WalletEntityMapEntity>()
                .eq(WalletEntityMapEntity::getWalletAddress, normalized));
        clusteringService.clusterWallet(normalized);
    }

    /**
     * 获取某个实体下的所有钱包地址
     */
    public List<String> getWalletsByEntityId(String entityId) {
        if (!StringUtils.hasText(entityId)) {
            return new ArrayList<>();
        }
        return entityMapMapper.selectList(new LambdaQueryWrapper<WalletEntityMapEntity>()
                        .eq(WalletEntityMapEntity::getEntityId, entityId))
                .stream()
                .map(WalletEntityMapEntity::getWalletAddress)
                .toList();
    }
    private WalletEntityMapEntity loadMapRecord(String normalizedWalletAddress) {
        return entityMapMapper.selectOne(new LambdaQueryWrapper<WalletEntityMapEntity>()
                .eq(WalletEntityMapEntity::getWalletAddress, normalizedWalletAddress)
                .last("limit 1"));
    }
}
