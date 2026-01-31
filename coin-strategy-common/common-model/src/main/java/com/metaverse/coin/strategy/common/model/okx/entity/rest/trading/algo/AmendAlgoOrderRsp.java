package com.metaverse.coin.strategy.common.model.okx.entity.rest.trading.algo;

import com.alibaba.fastjson2.annotation.JSONField;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.metaverse.coin.strategy.common.model.okx.entity.rest.IOkxRestRsp;
import lombok.Data;
import lombok.ToString;

@ToString
@Data
public class AmendAlgoOrderRsp implements IOkxRestRsp {

    /**
     * Algo ID
     */
    @JSONField(name = "algoId")
    @JsonProperty("algoId")
    private String algoId;

    /**
     * Client-supplied Algo ID
     */
    @JSONField(name = "algoClOrdId")
    @JsonProperty("algoClOrdId")
    private String algoClOrdId;

    /**
     * Request ID
     */
    @JSONField(name = "reqId")
    @JsonProperty("reqId")
    private String reqId;

    /**
     * Response code
     */
    @JSONField(name = "sCode")
    @JsonProperty("sCode")
    private String sCode;

    /**
     * Response message
     */
    @JSONField(name = "sMsg")
    @JsonProperty("sMsg")
    private String sMsg;
}
