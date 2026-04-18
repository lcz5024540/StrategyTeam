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
@TableName("single_exchange_arbitrage_funding_settlement")
public class SingleExchangeArbitrageFundingSettlement {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long positionId;

    private String positionCode;

    private String exchangeName;

    private String symbol;

    private Long fundingTime;

    private BigDecimal fundingRate;

    private BigDecimal markPrice;

    private BigDecimal positionQty;

    private BigDecimal notionalValue;

    private BigDecimal settlementAmount;

    private String direction;

    private Date createdAt;
}
