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
@TableName("weather_observation")
public class WeatherObservationEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String city;
    private Date targetDate;
    private BigDecimal actualMaxF;
    private BigDecimal actualMinF;
    private String source;
    private Date resolvedAt;
    private Date createdAt;
    private Date updatedAt;
}
