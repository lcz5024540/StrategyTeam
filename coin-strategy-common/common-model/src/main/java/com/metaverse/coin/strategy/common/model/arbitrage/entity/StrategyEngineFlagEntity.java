package com.metaverse.coin.strategy.common.model.arbitrage.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.experimental.Accessors;

import java.util.Date;

/**
 * 策略引擎全局标志位实体（kill_switch 等）。
 *
 * <p>通过 flag_key 唯一约束实现简单的 KV：</p>
 * <ul>
 *   <li>kill_switch — flag_value="ACTIVE"/"INACTIVE"</li>
 * </ul>
 */
@Data
@Accessors(chain = true)
@TableName("strategy_engine_flag")
public class StrategyEngineFlagEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 标志位 key（如 kill_switch） */
    private String flagKey;

    /** 标志位值（如 ACTIVE/INACTIVE） */
    private String flagValue;

    /** 激活原因 */
    private String reason;

    /** 激活时间 */
    private Date activatedAt;

    /** 激活操作人 */
    private String activatedBy;

    /** 更新时间 */
    private Date updatedAt;
}
