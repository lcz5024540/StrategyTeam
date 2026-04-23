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
@TableName("weather_forecast_snapshot")
public class WeatherForecastSnapshotEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String city;
    private Date forecastDate;
    private Date targetDate;
    private BigDecimal tempMaxF;
    private BigDecimal tempMinF;
    private BigDecimal tempAvgF;
    private String provider;
    private Integer leadHours;
    private Integer dataSourceCount;
    private Date fetchedAt;
    private Date createdAt;
    private Date updatedAt;
}
