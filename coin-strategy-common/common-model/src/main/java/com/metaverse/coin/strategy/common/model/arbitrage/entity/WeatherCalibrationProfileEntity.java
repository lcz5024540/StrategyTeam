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
@TableName("weather_calibration_profile")
public class WeatherCalibrationProfileEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String city;
    private Integer month;
    private Integer leadHourBucket;
    private BigDecimal sigmaMaxF;
    private BigDecimal sigmaMinF;
    private Integer sampleSize;
    private String version;
    private Date updatedAt;
    private Date createdAt;
}
