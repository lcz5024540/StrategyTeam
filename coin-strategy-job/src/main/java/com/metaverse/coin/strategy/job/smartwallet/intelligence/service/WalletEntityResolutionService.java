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

        // F-P1-14: 真实查询主钱包地址（取代旧 "// 简化：不单独查询主钱包"）
        // 优先取 isPrimaryWallet=true 的行；若实体内没有显式 primary 标记，则退化为：
        //   1) 当前查询行自身已是 primary → 用当前 wallet；
        //   2) 同实体内 createdAt 最早的钱包（首个被发现的 → primary 语义）；
        //   3) 三层都失败 → 退回当前 wallet（保持向后兼容，不引入 null）。
        String primaryWalletAddress = resolvePrimaryWalletAddress(mapRecord, walletAddress);

        return EntityResolutionResult.builder()
                .walletAddress(walletAddress)
                .entityId(mapRecord.getEntityId())
                .confidence(ClusterConfidence.fromScore(mapRecord.getClusterConfidence()))
                .confidenceScore(mapRecord.getClusterConfidence())
                .entityWalletCount(walletCountInt)
                .multiWallet(walletCountInt > 1)
                .primaryWalletAddress(primaryWalletAddress)
                .clusterMethod(mapRecord.getClusterMethod())
                .fundingSourceAddress(mapRecord.getFundingSourceAddress())
                .resolvedFromMap(true)
                .pendingClustering(false)
                .build();
    }

    /**
     * F-P1-14: 解析实体的主钱包地址.
     *
     * <p>策略 (优先级降序):
     * <ol>
     *   <li>当前 mapRecord 自己即 primary → 直接返回当前 walletAddress (省一次 DB);</li>
     *   <li>查 wallet_entity_map 中同 entityId 且 is_primary_wallet=true 的行 (limit 1) → 用其 walletAddress;</li>
     *   <li>没有显式 primary 标记的实体 → 按 createdAt asc 取最早一行 (首个被发现的钱包) → 用其 walletAddress;</li>
     *   <li>全部 fallback 都失败 (查询异常) → 退回当前 walletAddress (保持向后兼容、不引入 null).</li>
     * </ol>
     *
     * <p>失败语义: 查询任一阶段抛异常时 swallow + log.warn, 返回当前 walletAddress, 不阻塞主流程.
     * 共识信号去重 / 实体级风控用到 primaryWalletAddress 的下游可以放心消费此字段非 null.
     */
    private String resolvePrimaryWalletAddress(WalletEntityMapEntity mapRecord, String currentWalletAddress) {
        try {
            // 优先级 1: 当前行本身就是 primary
            if (Boolean.TRUE.equals(mapRecord.getIsPrimaryWallet())) {
                return currentWalletAddress;
            }

            // 优先级 2: 同实体内显式 isPrimaryWallet=true 的行
            WalletEntityMapEntity primary = entityMapMapper.selectOne(
                    new LambdaQueryWrapper<WalletEntityMapEntity>()
                            .eq(WalletEntityMapEntity::getEntityId, mapRecord.getEntityId())
                            .eq(WalletEntityMapEntity::getIsPrimaryWallet, Boolean.TRUE)
                            .last("limit 1"));
            if (primary != null && StringUtils.hasText(primary.getWalletAddress())) {
                return primary.getWalletAddress();
            }

            // 优先级 3: 实体内最早创建的钱包 (首个被发现的 → primary 语义)
            WalletEntityMapEntity earliest = entityMapMapper.selectOne(
                    new LambdaQueryWrapper<WalletEntityMapEntity>()
                            .eq(WalletEntityMapEntity::getEntityId, mapRecord.getEntityId())
                            .orderByAsc(WalletEntityMapEntity::getCreatedAt)
                            .last("limit 1"));
            if (earliest != null && StringUtils.hasText(earliest.getWalletAddress())) {
                return earliest.getWalletAddress();
            }
        } catch (Exception ex) {
            log.warn("[F-P1-14] resolvePrimaryWalletAddress failed entityId={} wallet={}: {}",
                    mapRecord.getEntityId(), currentWalletAddress, ex.getMessage());
        }
        // 优先级 4: 全部失败 → 退回当前 wallet, 保持向后兼容
        return currentWalletAddress;
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
