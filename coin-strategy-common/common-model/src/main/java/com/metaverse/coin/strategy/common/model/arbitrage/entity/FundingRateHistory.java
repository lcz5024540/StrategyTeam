package com.metaverse.coin.strategy.common.model.arbitrage.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.experimental.Accessors;

import java.math.BigDecimal;
import java.util.Date;

/**
 * 资金费率历史表
 */
@Data
@Accessors(chain = true)
@TableName("funding_rate_history")
public class FundingRateHistory {

    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 交易对 (e.g., BTCUSDT)
     */
    private String symbol;

    /**
     * 资金费率
     */
    private BigDecimal fundingRate;

    /**
     * 记录时的标记价格
     */
    private BigDecimal markPrice;

    /**
     * 资金费率结算时间戳 (毫秒)
     */
    private Long fundingTime;

    /**
     * 记录创建时间
     */
    private Date createdAt;
}
