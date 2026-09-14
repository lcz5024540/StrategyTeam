package com.metaverse.coin.strategy.htx.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** Durable state for one spot-long plus perpetual-short funding arbitrage cycle. */
@Data
@TableName("htx_funding_arbitrage_cycle")
public class HtxFundingArbitrageCycleEntity {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String cycleKey;
    private String symbol;
    private String contractCode;
    private String status;
    private BigDecimal targetQuoteNotional;
    private BigDecimal targetBaseAmount;
    private BigDecimal contractSize;
    private Long contractVolume;
    private Integer leverage;
    private BigDecimal entryFundingRate;
    private BigDecimal latestFundingRate;
    private BigDecimal entrySpotPrice;
    private BigDecimal entrySwapPrice;
    private String spotEntryClientOrderId;
    private Long spotEntryOrderId;
    private BigDecimal spotEntryFilledBase;
    private BigDecimal spotEntryFilledQuote;
    private BigDecimal spotEntryFeeBase;
    private String shortEntryClientOrderId;
    private Long shortEntryOrderId;
    private BigDecimal shortEntryFilledContracts;
    private BigDecimal shortEntryAvgPrice;
    private BigDecimal shortEntryFee;
    private String shortCloseClientOrderId;
    private Long shortCloseOrderId;
    private BigDecimal shortCloseFilledContracts;
    private BigDecimal shortCloseAvgPrice;
    private BigDecimal shortCloseFee;
    private String spotExitClientOrderId;
    private Long spotExitOrderId;
    private BigDecimal spotExitFilledBase;
    private BigDecimal spotExitFilledQuote;
    private BigDecimal spotExitFeeQuote;
    private String exitReason;
    private String message;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
