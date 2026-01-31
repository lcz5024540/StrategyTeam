package com.metaverse.coin.strategy.common.client.gate.service;

import io.gate.gateapi.ApiException;
import io.gate.gateapi.api.WalletApi;
import io.gate.gateapi.models.TotalBalance;
import lombok.extern.slf4j.Slf4j;

/**
 * Gate.io Wallet Service
 * <p>
 * Handles wallet operations like querying total balance.
 * </p>
 */
@Slf4j
public class GateWalletService extends GateService {

    private final WalletApi walletApi;

    public GateWalletService(WalletApi walletApi) {
        this.walletApi = walletApi;
    }

    /**
     * Get Total Balance.
     *
     * @param currency Filter by currency (optional).
     * @return Total Balance Details
     */
    public TotalBalance getTotalBalance(String currency) {
        try {
            return walletApi.getTotalBalance().currency(currency).execute();
        } catch (ApiException e) {
            log.error("Gate API Error: {} {}", e.getCode(), e.getMessage());
            throw new RuntimeException("Gate API Error", e);
        }
    }
}
