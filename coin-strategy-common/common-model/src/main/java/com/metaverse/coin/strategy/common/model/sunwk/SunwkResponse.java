package com.metaverse.coin.strategy.common.model.sunwk;

import lombok.Data;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class SunwkResponse<T> {
    private Integer code;
    private String msg;
    private T data;
    
    public boolean isSuccess() {
        return code != null && code == 200;
    }
}
