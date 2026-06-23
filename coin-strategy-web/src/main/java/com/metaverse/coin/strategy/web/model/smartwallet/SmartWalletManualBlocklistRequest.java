package com.metaverse.coin.strategy.web.model.smartwallet;

import lombok.Data;

import java.util.List;

@Data
public class SmartWalletManualBlocklistRequest {

    private String walletAddress;
    private List<String> walletAddresses;
    private String status;
    private String reason;
    private String createdBy;
}
