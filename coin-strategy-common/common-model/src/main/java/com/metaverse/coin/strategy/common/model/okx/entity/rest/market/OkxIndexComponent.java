package com.metaverse.coin.strategy.common.model.okx.entity.rest.market;

import com.alibaba.fastjson2.annotation.JSONField;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.metaverse.coin.strategy.common.model.okx.entity.rest.IOkxRestRsp;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.ToString;

import java.math.BigDecimal;
import java.util.List;

@Data
@ToString
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class OkxIndexComponent implements IOkxRestRsp {

    @JSONField(name = "index")
    @JsonProperty("index")
    private String index;

    @JSONField(name = "last")
    @JsonProperty("last")
    private BigDecimal last;

    @JSONField(name = "ts")
    @JsonProperty("ts")
    private Long ts;

    @JSONField(name = "constituents")
    @JsonProperty("constituents")
    private List<OkxIndexConstituent> constituents;

    @Data
    @ToString
    @Builder
    @AllArgsConstructor
    @NoArgsConstructor
    public static class OkxIndexConstituent {
        @JSONField(name = "symbol")
        @JsonProperty("symbol")
        private String symbol;

        @JSONField(name = "symbolPx")
        @JsonProperty("symbolPx")
        private BigDecimal symbolPx;

        @JSONField(name = "wgt")
        @JsonProperty("wgt")
        private BigDecimal wgt;

        @JSONField(name = "ccy")
        @JsonProperty("ccy")
        private String ccy;

        @JSONField(name = "ccyPx")
        @JsonProperty("ccyPx")
        private BigDecimal ccyPx;
    }
}
