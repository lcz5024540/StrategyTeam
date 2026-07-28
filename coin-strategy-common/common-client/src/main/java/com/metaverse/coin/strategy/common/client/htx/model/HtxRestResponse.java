package com.metaverse.coin.strategy.common.client.htx.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;

/**
 * Uniform envelope for HTX REST responses ({@code {"status":"ok","data":...}} or {@code {"status":"error","err-code":...,"err-msg":...}}).
 * We fold both spot-trade shape (status/data) and market-data shape (status/tick) into one type for simplicity.
 */
@Data
@Builder
@AllArgsConstructor
public class HtxRestResponse<T> {
    private String status;
    private String ch;
    private Long ts;
    /** For trade endpoints (POST /v1/order/orders/place etc). */
    private T data;
    /** For market data endpoints (GET /market/depth etc). Server returns "tick"; we normalize to data upstream. */
    private T tick;
    private String errCode;
    private String errMsg;

    public HtxRestResponse() {
    }

    public boolean ok() {
        return "ok".equalsIgnoreCase(status);
    }

    public T payload() {
        return data != null ? data : tick;
    }
}
