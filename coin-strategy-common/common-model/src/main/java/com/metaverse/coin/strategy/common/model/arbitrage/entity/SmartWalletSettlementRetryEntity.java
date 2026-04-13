package com.metaverse.coin.strategy.common.model.arbitrage.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.experimental.Accessors;

import java.util.Date;

@Data
@Accessors(chain = true)
@TableName("smart_wallet_settlement_retry")
public class SmartWalletSettlementRetryEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String marketId;
    private String question;
    private String retryType;
    private Long latestRunId;
    private Integer failureCount;
    private String lastErrorMessage;
    private Date lastFailedAt;
    private Date nextRetryAt;
    private String status;
    private Date createdAt;
    private Date updatedAt;
}
