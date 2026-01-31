package com.metaverse.coin.strategy.common.model.sunwk.entity;

import lombok.Data;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.math.BigDecimal;
import java.util.List;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class SunwkBalance {
    
    @JsonProperty("state")
    private String state;
    
    @JsonProperty("equity")
    private BigDecimal equity;
    
    @JsonProperty("initial_margin")
    private BigDecimal initialMargin;
    
    @JsonProperty("maintenance_margin")
    private BigDecimal maintenanceMargin;
    
    @JsonProperty("maintenance_margin_rate")
    private BigDecimal maintenanceMarginRate;
    
    @JsonProperty("profit_unreal")
    private BigDecimal profitUnreal;
    
    @JsonProperty("available_margin")
    private BigDecimal availableMargin;
    
    @JsonProperty("voucher_value")
    private BigDecimal voucherValue;
    
    @JsonProperty("created_time")
    private Long createdTime;
    
    @JsonProperty("updated_time")
    private Long updatedTime;
    
    @JsonProperty("details")
    private List<SunwkBalanceDetail> details;
    
    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class SunwkBalanceDetail {
        @JsonProperty("currency")
        private String currency;
        
        @JsonProperty("equity")
        private BigDecimal equity;
        
        @JsonProperty("available")
        private BigDecimal available;
        
        @JsonProperty("withdraw_available")
        private BigDecimal withdrawAvailable;
        
        @JsonProperty("profit_unreal")
        private BigDecimal profitUnreal;
        
        @JsonProperty("initial_margin")
        private BigDecimal initialMargin;
        
        @JsonProperty("maintenance_margin")
        private BigDecimal maintenanceMargin;
        
        @JsonProperty("maintenance_margin_rate")
        private BigDecimal maintenanceMarginRate;
        
        @JsonProperty("initial_margin_rate")
        private BigDecimal initialMarginRate;
        
        @JsonProperty("voucher")
        private BigDecimal voucher;
        
        @JsonProperty("voucher_value")
        private BigDecimal voucherValue;
        
        @JsonProperty("created_time")
        private Long createdTime;
        
        @JsonProperty("updated_time")
        private Long updatedTime;
    }
}
