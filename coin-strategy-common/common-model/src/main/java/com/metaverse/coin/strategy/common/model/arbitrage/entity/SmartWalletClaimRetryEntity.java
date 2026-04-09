package com.metaverse.coin.strategy.common.model.arbitrage.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.experimental.Accessors;

import java.util.Date;

@Data
@Accessors(chain = true)
@TableName("smart_wallet_claim_retry")
public class SmartWalletClaimRetryEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long positionId;
    private String executionAddress;
    private String marketId;
    private String question;
    private Long latestRunId;
    private Integer failureCount;
    private String lastErrorMessage;
    private Date lastFailedAt;
    private Date nextRetryAt;
    private String status;
    private String txHash;
    private Date createdAt;
    private Date updatedAt;
}
