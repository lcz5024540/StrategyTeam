package com.metaverse.coin.strategy.common.client.htx.model.v5;

import com.alibaba.fastjson2.annotation.JSONField;
import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

/** Wire model for GET /v5/account/balance. */
@Data
public class HtxV5Balance {
    private String state;
    @JSONField(name = "available_margin")
    private BigDecimal availableMargin;
    private List<Detail> details;

    @Data
    public static class Detail {
        private String currency;
        private BigDecimal available;
        @JSONField(name = "available_margin")
        private BigDecimal availableMargin;
        @JSONField(name = "isolated_equity")
        private BigDecimal isolatedEquity;
    }
}
