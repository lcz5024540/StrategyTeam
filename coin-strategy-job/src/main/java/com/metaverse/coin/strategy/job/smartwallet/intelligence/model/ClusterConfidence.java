package com.metaverse.coin.strategy.job.smartwallet.intelligence.model;

/**
 * 钱包聚类置信度等级
 * 描述将两个或多个钱包归属到同一实体的可靠程度
 */
public enum ClusterConfidence {

    /**
     * 高置信 (≥ 0.85)：共同入金地址 + 行为高度一致
     * 可以认为是同一实体，用于共识去重和风控判断
     */
    HIGH,

    /**
     * 中置信 (0.60 - 0.85)：行为相似但入金来源不明
     * 谨慎处理，建议记录但不强制去重
     */
    MEDIUM,

    /**
     * 低置信 (0.40 - 0.60)：仅行为弱相关
     * 仅供参考，不影响共识计数
     */
    LOW,

    /**
     * 未解析：没有聚类信息，视为独立实体
     * entity_id = wallet_address（自身即实体）
     */
    UNRESOLVED;

    public static ClusterConfidence fromScore(java.math.BigDecimal score) {
        if (score == null) return UNRESOLVED;
        double d = score.doubleValue();
        if (d >= 0.85) return HIGH;
        if (d >= 0.60) return MEDIUM;
        if (d >= 0.40) return LOW;
        return UNRESOLVED;
    }
}
