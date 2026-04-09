package com.metaverse.coin.strategy.job.smartwallet.service;

import com.metaverse.coin.strategy.common.model.arbitrage.entity.SmartWalletPositionEntity;
import com.metaverse.coin.strategy.job.smartwallet.config.SmartWalletProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
@RequiredArgsConstructor
public class SmartWalletConfiguredClaimGateway implements SmartWalletOnChainClaimGateway {

    private final SmartWalletProperties properties;

    @Override
    public ClaimSubmission submitClaim(SmartWalletPositionEntity position) {
        String mode = properties.getClaim().getLiveGatewayMode();
        if (!StringUtils.hasText(mode) || "MANUAL_CONFIRM".equalsIgnoreCase(mode)) {
            return ClaimSubmission.manualConfirmRequired(
                    "Live claim is waiting for external on-chain confirmation because smart-wallet.claim.live-gateway-mode=MANUAL_CONFIRM."
            );
        }
        if ("SIMULATED_SUCCESS".equalsIgnoreCase(mode)) {
            String txHash = "simulated-claim-" + position.getId();
            return ClaimSubmission.claimed("Live claim completed through simulated gateway mode.", txHash);
        }
        if ("SIMULATED_SUBMITTED".equalsIgnoreCase(mode)) {
            String txHash = "submitted-claim-" + position.getId();
            return ClaimSubmission.submitted("Live claim was submitted and is waiting for confirmation.", txHash);
        }
        return ClaimSubmission.retryableFailure("Unsupported live claim gateway mode: " + mode);
    }
}
