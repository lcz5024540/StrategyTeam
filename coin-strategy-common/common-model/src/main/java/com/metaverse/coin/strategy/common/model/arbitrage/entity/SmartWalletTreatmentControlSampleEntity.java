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
@TableName("smart_wallet_treatment_control_sample")
public class SmartWalletTreatmentControlSampleEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long sourceEventId;
    private String controlMarketId;
    private String controlOutcome;
    private Date controlSnapshotAt;
    private BigDecimal matchDistance;
    private String covariatesJson;
    private Date createdAt;
}
