package com.metaverse.coin.strategy.common.model.arbitrage.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.experimental.Accessors;

import java.util.Date;

@Data
@Accessors(chain = true)
@TableName("strategy_runtime_config")
public class StrategyRuntimeConfigEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String strategyCode;
    private String configKey;
    private String configValue;
    private String valueType;
    private String groupName;
    private Boolean editable;
    private String description;
    private String updatedBy;
    private Date createdAt;
    private Date updatedAt;
}
