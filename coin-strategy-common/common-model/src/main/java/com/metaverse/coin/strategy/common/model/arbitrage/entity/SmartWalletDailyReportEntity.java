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
@TableName("smart_wallet_daily_report")
public class SmartWalletDailyReportEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Date reportDate;
    private String executionAddress;
    private Integer totalOrders;
    private Integer filledOrders;
    private BigDecimal totalVolumeUsdc;
    private BigDecimal feeUsdc;
    private BigDecimal realizedPnlUsdc;
    private BigDecimal unrealizedPnlUsdc;
    private BigDecimal netPnlUsdc;
    private Integer winningPositions;
    private Integer losingPositions;
    private BigDecimal winRate;
    private BigDecimal lossRate;
    private Date createdAt;
    private Date updatedAt;
}
