package com.metaverse.coin.strategy.common.model.arbitrage.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.experimental.Accessors;

import java.math.BigDecimal;
import java.util.Date;

@Data
@Accessors(chain = true)
@TableName("smart_wallet_order_analysis")
public class SmartWalletOrderAnalysisEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String walletAddress;
    private String source;
    private String marketId;
    private String question;
    private String outcome;
    private Date openedAt;
    private Date closedAt;
    private Long marketEndAt;
    private BigDecimal averageEntryPrice;
    private BigDecimal investedUsdc;
    private BigDecimal realizedPnlUsdc;
    private BigDecimal roiRate;
    private BigDecimal holdMinutes;
    private String exitStyle;
    private BigDecimal exitLeadMinutes;
    @TableField("is_win")
    private Boolean isWin;
    private BigDecimal sourceConfidence;
    private Date createdAt;
    private Date updatedAt;
}
