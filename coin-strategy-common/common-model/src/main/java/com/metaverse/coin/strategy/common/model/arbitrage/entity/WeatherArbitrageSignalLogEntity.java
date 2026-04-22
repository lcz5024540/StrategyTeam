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
@TableName("weather_arbitrage_signal_log")
public class WeatherArbitrageSignalLogEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String marketId;
    private String city;
    private BigDecimal marketPrice;
    private BigDecimal fairValue;
    private BigDecimal edge;
    /** PASS / BUY_YES / BUY_NO */
    private String action;
    private String reason;
    private Date scannedAt;
    private Date createdAt;
}
