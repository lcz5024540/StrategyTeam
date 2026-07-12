package com.metaverse.coin.strategy.common.model.arbitrage.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.experimental.Accessors;

import java.util.Date;

@Data
@Accessors(chain = true)
@TableName("strategy_experiment")
public class StrategyExperimentEntity {

    @TableId(type = IdType.INPUT)
    private String id;
    private String strategyKey;
    private String datasetVersion;
    private String featureVersion;
    private String modelVersion;
    private String championPolicyTag;
    private String candidatePolicyJson;
    private String costScenarioJson;
    private Long randomSeed;
    private String status;
    private String specJson;
    private String resultJson;
    private String resultHash;
    private Date createdAt;
    private Date startedAt;
    private Date completedAt;
}
