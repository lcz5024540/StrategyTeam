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
@TableName("weather_paper_position")
public class WeatherPaperPositionEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String marketId;
    private String tokenId;
    private String city;
    private String question;
    private String side;
    private BigDecimal entryPrice;
    private BigDecimal quantity;
    private BigDecimal stakeUsdc;
    private BigDecimal fairValue;
    private BigDecimal confidence;
    private Date marketEndAt;
    private String status;
    private Date openedAt;
    private Date closedAt;
    private String exitReason;
    private Date createdAt;
    private Date updatedAt;
}
