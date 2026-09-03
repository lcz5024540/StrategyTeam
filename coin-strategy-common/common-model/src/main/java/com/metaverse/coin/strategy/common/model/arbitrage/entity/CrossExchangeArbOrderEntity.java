package com.metaverse.coin.strategy.common.model.arbitrage.entity;
import com.baomidou.mybatisplus.annotation.*; import lombok.Data; import lombok.experimental.Accessors;
import java.math.BigDecimal; import java.util.Date;
@Data @Accessors(chain=true) @TableName("cross_exchange_arb_order")
public class CrossExchangeArbOrderEntity {
 @TableId(type=IdType.AUTO) private Long id; private String positionCode; private String clientOrderId; private String exchangeName;
 private String symbol; private String legSide; private Boolean reduceOnly; private BigDecimal requestedQuantity; private BigDecimal filledQuantity;
 private BigDecimal averagePrice; private String status; private String message; private Date createdAt; private Date updatedAt;
 private BigDecimal feeAmount;
}
