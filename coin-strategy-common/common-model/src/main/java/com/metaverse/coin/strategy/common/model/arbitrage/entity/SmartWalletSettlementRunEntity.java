package com.metaverse.coin.strategy.common.model.arbitrage.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.experimental.Accessors;

import java.util.Date;

@Data
@Accessors(chain = true)
@TableName("smart_wallet_settlement_run")
public class SmartWalletSettlementRunEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String triggerType;
    private Integer positionLimit;
    private Integer marketLimit;
    private Boolean refreshCache;
    private String status;
    private Integer scannedPositions;
    private Integer distinctMarkets;
    private Integer fetchedMarkets;
    private Integer resolvedPositions;
    private Integer unresolvedMarkets;
    private Integer skippedFutureMarkets;
    private Integer failedMarkets;
    private String notes;
    private String errorMessage;
    private Date startedAt;
    private Date finishedAt;
    private Date createdAt;
    private Date updatedAt;
}
