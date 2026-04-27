package com.metaverse.coin.strategy.job.smartwallet.intelligence.model;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * 对共识信号进行实体去重后的结果
 * 解决一个实体持有多钱包时虚增共识强度的问题
 */
@Data
@Builder
public class EntityDedupedConsensus {

    /** 原始钱包地址数（当前系统的 distinctWallets） */
    private int rawWalletCount;

    /** 去重后的实体数（真实共识强度，用于替换 distinctWallets 的门控） */
    private int dedupedEntityCount;

    /** 参与的实体ID列表 */
    private List<String> entityIds;

    /** 按实体维度计算的平均评分（实体评分 = 该实体所有钱包评分的最大值） */
    private BigDecimal entityAvgScore;
    /** 实体评分映射（entityId -> 该实体最高钱包分） */
    private Map<String, BigDecimal> entityMaxScoreMap;
    /** 实体钱包映射（entityId -> 该实体下触发的钱包列表） */
    private Map<String, List<String>> entityWalletsMap;
    /** 实体解析结果映射（entityId -> 代表性解析结果） */
    private Map<String, EntityResolutionResult> entityResolutionMap;

    /**
     * 是否存在可疑的多钱包膨胀行为：
     * true = 某个实体有 > 1 个钱包同时触发了该信号
     * 此标志用于告警，不直接拒绝信号
     */
    private boolean hasSuspiciousMultiWallet;

    /** 发生多钱包膨胀的实体ID（如有，用于告警日志） */
    private List<String> suspiciousEntityIds;
}
