package com.metaverse.coin.strategy.common.model.okx.entity.rest.finance.savings;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class OkxSavingsBalance {
    /**
     * Currency, e.g. BTC
     */
    private String ccy;

    /**
     * Amount
     */
    private BigDecimal amt;

    /**
     * Earnings
     */
    private BigDecimal earnings;

    /**
     * Redemption amount
     */
    private BigDecimal redemptAmt;

    /**
     * Lending rate
     */
    private BigDecimal rate;

    /**
     * Loan amount
     */
    private BigDecimal loanAmt;

    /**
     * Pending amount
     */
    private BigDecimal pendingAmt;
}
