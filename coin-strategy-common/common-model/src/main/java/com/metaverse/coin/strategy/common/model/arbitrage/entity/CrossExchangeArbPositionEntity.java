package com.metaverse.coin.strategy.common.model.arbitrage.entity;
import com.baomidou.mybatisplus.annotation.*; import lombok.Data; import lombok.experimental.Accessors;
import java.math.BigDecimal; import java.util.Date;
@Data @Accessors(chain=true) @TableName("cross_exchange_arb_position")
public class CrossExchangeArbPositionEntity {
 @TableId(type=IdType.AUTO) private Long id; private String positionCode; private String opportunityCode; private String symbol;
 private String longExchange; private String shortExchange; private BigDecimal hedgedQuantity; private String status;
 private Date openedAt; private Date closedAt; private String message; private Date createdAt; private Date updatedAt;
}
