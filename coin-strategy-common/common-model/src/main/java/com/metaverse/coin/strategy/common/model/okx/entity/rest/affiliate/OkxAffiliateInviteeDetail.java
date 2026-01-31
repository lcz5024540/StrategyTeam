package com.metaverse.coin.strategy.common.model.okx.entity.rest.affiliate;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.math.BigDecimal;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class OkxAffiliateInviteeDetail {

    /**
     * Invitee User ID
     */
    @JsonProperty("uid")
    private String uid;

    /**
     * Affiliate level of the invitee
     */
    @JsonProperty("inviteeLv")
    private String inviteeLv;

    /**
     * Commission rate
     */
    @JsonProperty("commissionRate")
    private BigDecimal commissionRate;

    /**
     * Total commission amount
     */
    @JsonProperty("totalCommission")
    private BigDecimal totalCommission;

    /**
     * Total rebate amount
     */
    @JsonProperty("totalRebate")
    private BigDecimal totalRebate;

    /**
     * Registration time (timestamp)
     */
    @JsonProperty("joinTime")
    private Long joinTime;
    
    /**
     * Invitee level
     */
    @JsonProperty("level")
    private String level;
    
    /**
     * Rebate rate
     */
    @JsonProperty("rebateRate")
    private BigDecimal rebateRate;
}
