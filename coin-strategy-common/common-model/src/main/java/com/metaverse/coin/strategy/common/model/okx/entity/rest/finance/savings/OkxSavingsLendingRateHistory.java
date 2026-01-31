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
public class OkxSavingsLendingRateHistory {
    private String ccy;
    private BigDecimal rate;
    private Long ts;
}
