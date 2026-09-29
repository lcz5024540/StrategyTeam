package com.metaverse.coin.strategy.htx.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** Immutable market classification evidence used by a research run. */
@Data
@TableName("htx_trend_market_regime_snapshot")
public class HtxTrendMarketRegimeSnapshotEntity {
    @TableId(type = IdType.AUTO) private Long id;
    private String snapshotKey;
    private String datasetKey;
    private String scope;
    private String contractCode;
    private Long barTime;
    private String stateCode;
    private String dailyDirection;
    private String fourHourDirection;
    private String oneHourDirection;
    private BigDecimal breadthRatio;
    private BigDecimal volatilityScore;
    private BigDecimal crowdingScore;
    private String evidenceJson;
    private String dataIntegrity;
    private LocalDateTime createdAt;
}
