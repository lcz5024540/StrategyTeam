package com.metaverse.coin.strategy.web.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "dashboard")
public class DashboardProperties {

    private String smartWalletBaseUrl = "http://127.0.0.1:8081";

    public String getSmartWalletBaseUrl() {
        return smartWalletBaseUrl;
    }

    public void setSmartWalletBaseUrl(String smartWalletBaseUrl) {
        this.smartWalletBaseUrl = smartWalletBaseUrl;
    }
}
