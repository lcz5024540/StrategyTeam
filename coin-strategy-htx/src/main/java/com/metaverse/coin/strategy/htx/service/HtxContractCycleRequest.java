package com.metaverse.coin.strategy.htx.service;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/** One isolated cycle. Dual mode uses one open price and one close price for both position sides. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class HtxContractCycleRequest {
    private String contractCode;
    /** Number of contracts, not base-asset quantity. */
    private Long volume;
    private BigDecimal openPrice;
    private BigDecimal closePrice;
    /** Optional; falls back to htx.contract.leverage. */
    private Integer leverage;
    /** Required for live trading. Dual legs reserve this id through id + 3. */
    private Long clientOrderId;
    /** Optional; falls back to htx.contract.fill-timeout-ms. */
    private Long waitFillTimeoutMs;
}
