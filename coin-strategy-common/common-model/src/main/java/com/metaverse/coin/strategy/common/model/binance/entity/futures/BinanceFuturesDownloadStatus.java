package com.metaverse.coin.strategy.common.model.binance.entity.futures;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class BinanceFuturesDownloadStatus {
    private String downloadId;
    private String status;
    private String url;
    private Long notifiedTime;
    private Long expirationTime;
    private Boolean isExpired;
}
