package com.metaverse.coin.strategy.common.model.arbitrage.entity;
import com.baomidou.mybatisplus.annotation.*; import lombok.Data; import lombok.experimental.Accessors;
import java.math.BigDecimal; import java.util.Date;
@Data @Accessors(chain=true) @TableName("cross_exchange_risk_snapshot")
public class CrossExchangeRiskSnapshotEntity {
 @TableId(type=IdType.AUTO) private Long id; private String positionCode; private String opportunityCode; private String checkStage;
 private Boolean passed; private String reason; private BigDecimal nakedNotional; private BigDecimal markPriceDeviation;
 private Long quoteAgeMs; private String detailsJson; private Date createdAt;
}
