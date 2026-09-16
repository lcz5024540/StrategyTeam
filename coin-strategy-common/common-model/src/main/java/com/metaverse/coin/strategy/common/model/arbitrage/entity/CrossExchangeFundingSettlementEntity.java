package com.metaverse.coin.strategy.common.model.arbitrage.entity;
import com.baomidou.mybatisplus.annotation.*; import lombok.Data; import lombok.experimental.Accessors;
import java.math.BigDecimal; import java.util.Date;
@Data @Accessors(chain=true) @TableName("cross_exchange_funding_settlement")
public class CrossExchangeFundingSettlementEntity {
 @TableId(type=IdType.AUTO) private Long id; private String positionCode; private String exchangeName; private String symbol;
 private BigDecimal fundingRate; private BigDecimal expectedAmount; private BigDecimal actualAmount; private String settlementSource;
 private Date observedAt; private Date settlementTime; private Date createdAt;
}
