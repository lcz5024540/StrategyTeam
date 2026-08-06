package com.metaverse.coin.strategy.htx.strategy;

import com.metaverse.coin.strategy.htx.persistence.entity.HtxLockedPairEntity;
import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 一笔做市 cell 的内存模型。开仓瞬间锁定 buy/sell 价，此后不动（网格 cell）。
 * 从 htx_locked_pair 行重建，用于状态机推进与重启恢复。
 */
@Data
@Builder
public class MarketMakerCell {
    private long pairId;
    private String clientId;
    private String symbol;
    private BigDecimal buyPrice;
    private BigDecimal sellPrice;
    private BigDecimal qty;
    private String state;
    private Long buyOrderId;
    private Long sellOrderId;
    private LocalDateTime createdAt;

    public static MarketMakerCell fromEntity(HtxLockedPairEntity e) {
        return MarketMakerCell.builder()
                .pairId(e.getId())
                .clientId(e.getClientOrderId())
                .symbol(e.getSymbol())
                .buyPrice(e.getBuyPrice())
                .sellPrice(e.getSellPrice())
                .qty(e.getReqQuantity())
                .state(e.getStatus())
                .buyOrderId(e.getBuyOrderId())
                .sellOrderId(e.getSellOrderId())
                .createdAt(e.getCreatedAt())
                .build();
    }
}
