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
@TableName("wallet_follow_candidate")
public class WalletFollowCandidateEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 来源交易事件ID（关联 smart_wallet_trade_event） */
    private Long sourceEventId;

    /** 触发钱包地址 */
    private String walletAddress;

    /** 解析到的实体ID（未聚类时 = walletAddress） */
    private String entityId;

    /** 聚类置信度 0-1（未聚类时 = 0） */
    private BigDecimal clusterConfidence;

    /** 聚类方法 */
    private String clusterMethod;

    /** 该实体下的钱包总数 */
    private Integer entityWalletCount;

    /** 该实体在本市场是否首次触发信号 */
    private Boolean isEntityFirstSignal;

    /** 去重后的实体共识数（同市场同方向有多少不同实体参与） */
    private Integer entityConsensusDedupCount;

    /** enrichment 状态: ENRICHED / SKIPPED_NO_PROFILE / PENDING */
    private String enrichmentStatus;

    /** 候选被拒绝的原因（如有） */
    private String rejectionReason;

    /** 最终生成的 follow task ID（如有） */
    private Long followTaskId;

    private Date createdAt;
    private Date updatedAt;
}
