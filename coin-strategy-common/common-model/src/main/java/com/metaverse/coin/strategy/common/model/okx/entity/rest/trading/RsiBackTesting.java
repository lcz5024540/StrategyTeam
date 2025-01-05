package com.metaverse.coin.strategy.common.model.okx.entity.rest.trading;

import com.alibaba.fastjson2.annotation.JSONField;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.metaverse.coin.strategy.common.model.okx.entity.rest.IOkxRestRsp;
import com.metaverse.coin.strategy.common.model.okx.entity.rest.IRestEntity;
import lombok.*;

/**
 * @author Forest Wang
 * @package xyz.felh.okx.v5.entity.rest.trading
 * @class RsiBackTesting
 * @email forestwanglin@gmail.cn
 * @date 2024/7/25
 */
@ToString
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RsiBackTesting implements IRestEntity, IOkxRestRsp {

    /**
     * 触发次数
     */
    @JSONField(name = "triggerNum")
    @JsonProperty("triggerNum")
    private Integer triggerNum;

}