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
@TableName("hedge_position")
public class HedgePositionEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String marketId;
    private String question;
    private String yesTokenId;
    private String noTokenId;

    private BigDecimal yesEntryPrice;
    private BigDecimal noEntryPrice;
    private BigDecimal probDeviation;
    private BigDecimal grossEdgeRate;

    private BigDecimal quantity;
    private BigDecimal yesCostUsdc;
    private BigDecimal noCostUsdc;
    private BigDecimal totalCostUsdc;
    private BigDecimal expectedProfitUsdc;

    private BigDecimal yesLiquidityUsdc;
    private BigDecimal noLiquidityUsdc;

    private String executionAddress;

    /** OPEN | SETTLING | SETTLED | CANCELLED */
    private String status;
    /** YES | NO */
    private String finalOutcome;
    private BigDecimal claimedUsdc;
    private BigDecimal feeUsdc;
    private BigDecimal realizedPnlUsdc;

    private Long marketEndAt;
    private Date openedAt;
    private Date settlingAt;
    private Date settledAt;
    private Date createdAt;
    private Date updatedAt;
}
