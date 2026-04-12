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
@TableName("smart_wallet_follow_task")
public class SmartWalletFollowTaskEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String walletAddress;

    /** 触发钱包对应的实体ID */
    private String entityId;

    /** 关联的候选跟单信号ID */
    private Long followCandidateId;

    private Long sourceEventId;
    private String marketId;
    private String question;
    private String outcome;
    private Long marketEndAt;
    private String side;
    private BigDecimal triggerPrice;
    private BigDecimal triggerSizeUsdc;
    private BigDecimal recommendedStakeUsdc;
    private String status;
    private String executionMode;
    private String externalOrderId;
    private String errorMessage;
    private Date scheduledAt;
    private Date executedAt;
    private Date createdAt;
    private Date updatedAt;
}
