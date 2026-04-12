package com.metaverse.coin.strategy.job.smartwallet.intelligence.model;

import lombok.Builder;
import lombok.Data;

/**
 * 钱包地址 → 实体解析结果
 */
@Data
@Builder
public class EntityResolutionResult {

    /** 原始钱包地址 */
    private String walletAddress;

    /**
     * 解析到的实体ID
     * - 已聚类：为 UUID（多钱包共享）
     * - 未聚类：= walletAddress 自身（fallback，自身即实体）
     */
    private String entityId;

    /** 聚类置信度等级 */
    private ClusterConfidence confidence;

    /** 置信度数值 0-1 */
    private java.math.BigDecimal confidenceScore;

    /** 该实体下的钱包总数（含当前钱包） */
    private int entityWalletCount;

    /** 是否为多钱包实体（entityWalletCount > 1） */
    private boolean multiWallet;

    /** 该实体的主钱包地址（首次发现的钱包） */
    private String primaryWalletAddress;

    /** 聚类方法 */
    private String clusterMethod;

    /** 共同入金地址（如有） */
    private String fundingSourceAddress;

    /** 是否命中了 wallet_entity_map 中的持久化记录 */
    private boolean resolvedFromMap;

    /** 是否在本次解析时发生了未命中后的兜底返回 */
    private boolean pendingClustering;

    /**
     * 创建"自身即实体"的默认结果（未聚类时使用）
     */
    public static EntityResolutionResult selfEntity(String walletAddress) {
        return selfEntity(walletAddress, false);
    }

    public static EntityResolutionResult selfEntity(String walletAddress, boolean pendingClustering) {
        return EntityResolutionResult.builder()
                .walletAddress(walletAddress)
                .entityId(walletAddress.toLowerCase())
                .confidence(ClusterConfidence.UNRESOLVED)
                .confidenceScore(java.math.BigDecimal.ZERO)
                .entityWalletCount(1)
                .multiWallet(false)
                .primaryWalletAddress(walletAddress)
                .clusterMethod("SELF")
                .resolvedFromMap(false)
                .pendingClustering(pendingClustering)
                .build();
    }
}
