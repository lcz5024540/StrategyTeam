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
public class OkxSavingsPurchaseRedemptReq {
    /**
     * Currency, e.g. BTC
     */
    private String ccy;

    /**
     * Amount
     */
    private BigDecimal amt;

    /**
     * Action side: purchase, redempt
     */
    private String side;

    /**
     * Purchase rate (optional)
     */
    private BigDecimal rate;
}
