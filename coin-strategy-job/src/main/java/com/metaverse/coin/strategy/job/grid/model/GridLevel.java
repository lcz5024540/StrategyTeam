package com.metaverse.coin.strategy.job.grid.model;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;

/**
 * 网格中的一个价格层。
 */
@Data
@Builder
public class GridLevel {

    /** 层级编号（负数=买单层，正数=卖单层，0=中心） */
    private int levelIndex;

    /** 挂单价格 */
    private BigDecimal price;

    /** 挂单方向: BUY / SELL */
    private String side;

    /** 挂单数量（shares） */
    private BigDecimal quantity;

    /** 挂单金额（USDC） */
    private BigDecimal amountUsdc;

    /** 对应的 CLOB 订单 ID（如果已挂出） */
    private String orderId;

    /** 层级状态: PENDING / LIVE / FILLED / CANCELLED */
    private String status;
}
