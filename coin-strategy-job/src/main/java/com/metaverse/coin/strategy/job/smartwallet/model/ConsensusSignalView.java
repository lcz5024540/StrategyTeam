package com.metaverse.coin.strategy.job.smartwallet.model;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

@Data
@Builder
public class ConsensusSignalView {
    private String marketId;
    private String question;
    private String outcome;
    private String side;

    /** 原始参与钱包地址数（未去重，历史兼容字段，勿用于风控门控） */
    private int distinctWallets;

    /**
     * 实体去重后的参与实体数（真实共识强度）
     * 修复「地址膨胀」bug：一个实体的多个钱包只计 1 票
     * 风控门控应使用此字段（minDistinctEntities），而非 distinctWallets
     */
    private int dedupedEntityCount;

    /**
     * 是否存在可疑的多钱包膨胀行为
     * true = 同一实体有 >1 个钱包触发了相同信号（告警标志）
     */
    private boolean hasSuspiciousMultiWallet;

    private BigDecimal totalTriggerSizeUsdc;
    private BigDecimal averageTriggerPrice;

    /** 实体维度的平均评分（去重后，一个实体取最高钱包分） */
    private BigDecimal consensusScore;
    private BigDecimal qualityWeightedConsensusScore;
    private BigDecimal topEntityWeightShare;
    private BigDecimal leaderFollowerScore;
    private BigDecimal entityIndependenceScore;
    private BigDecimal recentConsistencyScore;

    private Date latestOpenedAt;
    @Builder.Default
    private List<String> walletAddresses = new ArrayList<>();
}
