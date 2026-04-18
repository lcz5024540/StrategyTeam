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
@TableName("hedge_arbitrage_log")
public class HedgeArbitrageLogEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String marketId;
    private String question;
    private String yesTokenId;
    private String noTokenId;

    private BigDecimal yesBestAsk;
    private BigDecimal noBestAsk;
    private BigDecimal yesLiquidityUsdc;
    private BigDecimal noLiquidityUsdc;

    private BigDecimal totalImpliedProb;
    private BigDecimal probDeviation;
    private BigDecimal grossEdgeRate;
    private BigDecimal expectedProfitUsdc;

    /**
     * SKIP_LOW_EDGE | SKIP_LIQUIDITY | SKIP_RISK | SKIP_DUPLICATE | SKIP_DISABLED | EXECUTED
     */
    private String action;
    private String skipReason;
    private Long hedgePositionId;

    /** PAPER | LIVE */
    private String mode;

    private Date scannedAt;
}
