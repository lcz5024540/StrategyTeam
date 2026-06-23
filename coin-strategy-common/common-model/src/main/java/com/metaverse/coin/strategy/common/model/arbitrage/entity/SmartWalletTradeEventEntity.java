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
@TableName("smart_wallet_trade_event")
public class SmartWalletTradeEventEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String walletAddress;
    private String chain;
    private String source;
    private String externalTradeId;

    /**
     * 跟单链路追踪 ID, 派生自本表 id (格式: tev_%012d). 由
     * {@code PolymarketSmartWalletMonitorService} 在 ingestTradeEvent 时回填,
     * 并作为 follow_task / order / trade_order / position 的链路源头.
     * 详见 docs/polymarket/copy-trading-improvement-decision-2026-05-16.md (W1-T3).
     */
    private String traceId;

    private String marketId;
    private String question;
    private String outcome;
    private String side;
    private BigDecimal price;
    private BigDecimal sizeShares;
    private BigDecimal sizeUsdc;
    private Date openedAt;
    private Boolean processed;
    private Date createdAt;
    private Date updatedAt;
}
