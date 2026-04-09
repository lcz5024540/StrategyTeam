package com.metaverse.coin.strategy.common.model.arbitrage.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.experimental.Accessors;

import java.util.Date;

@Data
@Accessors(chain = true)
@TableName("smart_wallet_claim_run")
public class SmartWalletClaimRunEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String triggerType;
    private Integer positionLimit;
    private String status;
    private Integer scannedPositions;
    private Integer attemptedClaims;
    private Integer claimedPositions;
    private Integer waitingConfirmationPositions;
    private Integer failedPositions;
    private Integer skippedPositions;
    private String notes;
    private String errorMessage;
    private Date startedAt;
    private Date finishedAt;
    private Date createdAt;
    private Date updatedAt;
}
