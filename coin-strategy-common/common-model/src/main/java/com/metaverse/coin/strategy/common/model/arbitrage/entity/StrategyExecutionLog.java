package com.metaverse.coin.strategy.common.model.arbitrage.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.experimental.Accessors;

import java.math.BigDecimal;
import java.util.Date;

/**
 * 策略运行日志表
 */
@Data
@Accessors(chain = true)
@TableName("strategy_execution_log")
public class StrategyExecutionLog {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String symbol;

    private Date checkTime;

    private BigDecimal currentFundingRate;

    /**
     * 执行动作: OPEN, CLOSE, SKIP, WAIT
     */
    private String actionTaken;

    /**
     * 详细日志信息
     */
    private String message;
}
