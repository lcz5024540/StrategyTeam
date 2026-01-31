package com.metaverse.coin.strategy.common.model.okx.entity.rest.trading.order.booking;

import com.alibaba.fastjson2.annotation.JSONField;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.metaverse.coin.strategy.common.model.okx.entity.rest.IOkxRestReq;
import lombok.*;

@Data
@ToString
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class MassCancelOrderReq implements IOkxRestReq {

    /**
     * 产品类型
     * SPOT: 币币
     * MARGIN: 币币杠杆
     * SWAP: 永续合约
     * FUTURES: 交割合约
     * OPTION: 期权
     */
    @NonNull
    @JSONField(name = "instType")
    @JsonProperty("instType")
    private String instType;

    /**
     * 标的指数
     */
    @JSONField(name = "uly")
    @JsonProperty("uly")
    private String uly;

    /**
     * 交易品种
     */
    @JSONField(name = "instFamily")
    @JsonProperty("instFamily")
    private String instFamily;

    /**
     * 产品ID
     */
    @JSONField(name = "instId")
    @JsonProperty("instId")
    private String instId;

    /**
     * 锁定间隔，单位秒。
     * 0表示不锁定，直接撤单。
     */
    @JSONField(name = "lockInterval")
    @JsonProperty("lockInterval")
    private String lockInterval;
}
