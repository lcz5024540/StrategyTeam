package com.metaverse.coin.strategy.common.model.okx.entity.rest.market;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.metaverse.coin.strategy.common.model.okx.entity.rest.IOkxRestRsp;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
@JsonDeserialize(using = OkxCandleDeserializer.class)
public class OkxCandle implements IOkxRestRsp {
    /**
     * Timestamp
     */
    private Long ts;
    /**
     * Open price
     */
    private BigDecimal o;
    /**
     * High price
     */
    private BigDecimal h;
    /**
     * Low price
     */
    private BigDecimal l;
    /**
     * Close price
     */
    private BigDecimal c;
    /**
     * Volume
     */
    private BigDecimal vol;
    /**
     * Volume in currency
     */
    private BigDecimal volCcy;
    /**
     * Volume in quote currency
     */
    private BigDecimal volCcyQuote;
    /**
     * Confirmation status (0 or 1)
     */
    private String confirm;
}
