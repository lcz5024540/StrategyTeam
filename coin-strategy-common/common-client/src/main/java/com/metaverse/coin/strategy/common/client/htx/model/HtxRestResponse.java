package com.metaverse.coin.strategy.common.client.htx.model;

import com.alibaba.fastjson2.annotation.JSONField;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;

/**
 * Uniform envelope for legacy status/data, public tick and V5 code/message/data HTX responses.
 */
@Data
@Builder
@AllArgsConstructor
public class HtxRestResponse<T> {
    /** V5 envelope success code. Legacy endpoints leave it null. */
    private Integer code;
    /** V5 envelope message. Legacy endpoints use errMsg for failures. */
    private String message;
    private String status;
    private String ch;
    private Long ts;
    /** For trade endpoints (POST /v1/order/orders/place etc). */
    private T data;
    /** For market data endpoints (GET /market/depth etc). Server returns "tick"; we normalize to data upstream. */
    private T tick;
    @JSONField(name = "err-code", alternateNames = {"err_code", "errCode"})
    private String errCode;
    @JSONField(name = "err-msg", alternateNames = {"err_msg", "errMsg"})
    private String errMsg;

    public HtxRestResponse() {
    }

    public boolean ok() {
        return "ok".equalsIgnoreCase(status) || Integer.valueOf(200).equals(code);
    }

    public T payload() {
        return data != null ? data : tick;
    }
}
