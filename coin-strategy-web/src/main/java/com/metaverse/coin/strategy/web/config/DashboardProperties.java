package com.metaverse.coin.strategy.web.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "dashboard")
public class DashboardProperties {

    /**
     * @deprecated 保留旧配置名兼容已有环境；新配置请使用 job-base-url。
     */
    @Deprecated
    private String smartWalletBaseUrl = "http://127.0.0.1:8081";
    private String jobBaseUrl = "http://127.0.0.1:8081";

    public String getSmartWalletBaseUrl() {
        return smartWalletBaseUrl;
    }

    public void setSmartWalletBaseUrl(String smartWalletBaseUrl) {
        this.smartWalletBaseUrl = smartWalletBaseUrl;
    }

    public String getJobBaseUrl() {
        if (jobBaseUrl != null && !jobBaseUrl.isBlank()) {
            return jobBaseUrl;
        }
        return smartWalletBaseUrl;
    }

    public void setJobBaseUrl(String jobBaseUrl) {
        this.jobBaseUrl = jobBaseUrl;
    }
}
