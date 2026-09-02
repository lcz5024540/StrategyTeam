package com.metaverse.coin.strategy.common.model.arbitrage.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.experimental.Accessors;

import java.math.BigDecimal;
import java.util.Date;

/** Latest evaluated cross-exchange funding-rate direction, written by the Paper scanner. */
@Data
@Accessors(chain = true)
@TableName("cross_exchange_arb_opportunity")
public class CrossExchangeArbOpportunityEntity {

    @TableId(type = IdType.AUTO)
    private Long id;
    private String opportunityCode;
    private String symbol;
    private String longExchange;
    private String shortExchange;
    private BigDecimal longFundingRate;
    private Integer longFundingIntervalHours;
    private BigDecimal shortFundingRate;
    private Integer shortFundingIntervalHours;
    private BigDecimal hourlyFundingSpread;
    private BigDecimal estimatedNetYield;
    private BigDecimal executableNotional;
    private String status;
    private String rejectionReason;
    private Date discoveredAt;
    private Date updatedAt;
}
