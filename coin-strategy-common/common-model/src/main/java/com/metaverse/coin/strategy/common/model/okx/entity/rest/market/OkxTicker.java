package com.metaverse.coin.strategy.common.model.okx.entity.rest.market;

import com.alibaba.fastjson2.annotation.JSONField;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.metaverse.coin.strategy.common.model.okx.entity.rest.IOkxRestRsp;
import com.metaverse.coin.strategy.common.model.okx.enums.InstrumentType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.ToString;

import java.math.BigDecimal;

@Data
@ToString
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class OkxTicker implements IOkxRestRsp {

    @JSONField(name = "instType")
    @JsonProperty("instType")
    private InstrumentType instType;

    @JSONField(name = "instId")
    @JsonProperty("instId")
    private String instId;

    @JSONField(name = "last")
    @JsonProperty("last")
    private BigDecimal last;

    @JSONField(name = "lastSz")
    @JsonProperty("lastSz")
    private BigDecimal lastSz;

    @JSONField(name = "askPx")
    @JsonProperty("askPx")
    private BigDecimal askPx;

    @JSONField(name = "askSz")
    @JsonProperty("askSz")
    private BigDecimal askSz;

    @JSONField(name = "bidPx")
    @JsonProperty("bidPx")
    private BigDecimal bidPx;

    @JSONField(name = "bidSz")
    @JsonProperty("bidSz")
    private BigDecimal bidSz;

    @JSONField(name = "open24h")
    @JsonProperty("open24h")
    private BigDecimal open24h;

    @JSONField(name = "high24h")
    @JsonProperty("high24h")
    private BigDecimal high24h;

    @JSONField(name = "low24h")
    @JsonProperty("low24h")
    private BigDecimal low24h;

    @JSONField(name = "volCcy24h")
    @JsonProperty("volCcy24h")
    private BigDecimal volCcy24h;

    @JSONField(name = "vol24h")
    @JsonProperty("vol24h")
    private BigDecimal vol24h;

    @JSONField(name = "ts")
    @JsonProperty("ts")
    private Long ts;

    @JSONField(name = "sodUtc0")
    @JsonProperty("sodUtc0")
    private BigDecimal sodUtc0;

    @JSONField(name = "sodUtc8")
    @JsonProperty("sodUtc8")
    private BigDecimal sodUtc8;
}
