package com.metaverse.coin.strategy.job.smartwallet.service;

import com.metaverse.coin.strategy.common.model.arbitrage.entity.SmartWalletPositionEntity;

public interface SmartWalletOnChainClaimGateway {

    ClaimSubmission submitClaim(SmartWalletPositionEntity position);

    record ClaimSubmission(Status status, String message, String txHash) {

        public static ClaimSubmission claimed(String message, String txHash) {
            return new ClaimSubmission(Status.CLAIMED, message, txHash);
        }

        public static ClaimSubmission submitted(String message, String txHash) {
            return new ClaimSubmission(Status.SUBMITTED, message, txHash);
        }

        public static ClaimSubmission manualConfirmRequired(String message) {
            return new ClaimSubmission(Status.MANUAL_CONFIRM_REQUIRED, message, null);
        }

        public static ClaimSubmission retryableFailure(String message) {
            return new ClaimSubmission(Status.RETRYABLE_FAILURE, message, null);
        }

        public static ClaimSubmission permanentFailure(String message) {
            return new ClaimSubmission(Status.PERMANENT_FAILURE, message, null);
        }
    }

    enum Status {
        CLAIMED,
        SUBMITTED,
        MANUAL_CONFIRM_REQUIRED,
        RETRYABLE_FAILURE,
        PERMANENT_FAILURE
    }
}
