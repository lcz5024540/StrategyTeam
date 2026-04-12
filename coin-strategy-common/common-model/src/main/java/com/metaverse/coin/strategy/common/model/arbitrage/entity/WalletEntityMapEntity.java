package com.metaverse.coin.strategy.common.model.arbitrage.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.experimental.Accessors;

import java.math.BigDecimal;
import java.util.Date;

@Data
@Accessors(chain = true)
@TableName("wallet_entity_map")
public class WalletEntityMapEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 钱包地址（小写存储） */
    private String walletAddress;

    /** 实体UUID，同一真实交易者的多钱包共享同一 entity_id */
    private String entityId;

    /** 聚类方法: FUNDING_SOURCE / BEHAVIOR_SIM / MANUAL / SELF */
    private String clusterMethod;

    /** 聚类置信度 0-1 */
    private BigDecimal clusterConfidence;

    /** 共同入金地址（来自 Polygonscan 链上分析） */
    private String fundingSourceAddress;

    /** 行为相似度得分（BehaviorSimilarityClusterer 填充） */
    private BigDecimal behaviorSimScore;

    /** 是否为该实体的主钱包（首个被发现的钱包） */
    private Boolean isPrimaryWallet;

    /** 是否经过人工确认 */
    private Boolean verified;

    private Date createdAt;
    private Date updatedAt;
}
