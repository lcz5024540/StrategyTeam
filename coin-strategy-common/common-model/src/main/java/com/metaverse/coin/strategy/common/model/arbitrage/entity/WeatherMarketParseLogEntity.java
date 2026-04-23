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
@TableName("weather_market_parse_log")
public class WeatherMarketParseLogEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String marketId;
    private String question;
    private String parseStatus;
    private String reason;
    private BigDecimal confidence;
    private Date createdAt;
}
