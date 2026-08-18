package com.metaverse.coin.strategy.core.arbitrage.execution;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;

/**
 * 成交后实际滑点超阈告警记录（P0-3）。
 *
 * <p>开/平仓成交后，用真实成交均价 {@code avgPrice} 与计划参考价 {@code referencePrice} 计算实际滑点
 * {@code |avgPrice - referencePrice| / referencePrice}；当其超过配置阈值 {@code thresholdRate}
 * （{@code arbitrage.spot-futures.estimated-slippage-rate}）时，由执行器产出一条本告警并落结构化日志，
 * 供上层做止盈/熔断依据与事后对账。参考价缺失时不产出告警（见执行器 evaluateFillSlippage 注释）。
 */
@Data
@Builder
public class SingleExchangeSlippageAlert {

    private String symbol;
    private String hedgeSide;
    private String tradePhase;
    private String side;
    private BigDecimal referencePrice;
    private BigDecimal avgPrice;
    private BigDecimal actualSlippageRate;
    private BigDecimal thresholdRate;
}
