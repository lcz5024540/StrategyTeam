package com.metaverse.coin.strategy.common.client.gate.service;

import io.gate.gateapi.ApiException;
import io.gate.gateapi.api.FuturesApi;
import io.gate.gateapi.api.SpotApi;
import io.gate.gateapi.models.FuturesAccount;
import io.gate.gateapi.models.SpotAccount;
import lombok.extern.slf4j.Slf4j;

import java.util.List;

/**
 * Gate.io Account Service
 * <p>
 * Handles account-related operations for Spot and Futures.
 * </p>
 */
@Slf4j
public class GateAccountService extends GateService {

    private final SpotApi spotApi;
    private final FuturesApi futuresApi;

    public GateAccountService(SpotApi spotApi, FuturesApi futuresApi) {
        this.spotApi = spotApi;
        this.futuresApi = futuresApi;
    }

    /**
     * Get Spot Accounts (Balances).
     *
     * @param currency Filter by currency (e.g., "BTC"). Optional.
     * @return List of Spot Accounts
     */
    public List<SpotAccount> getSpotAccounts(String currency) {
        try {
            return spotApi.listSpotAccounts().currency(currency).execute();
        } catch (ApiException e) {
            log.error("Gate API Error: {} {}", e.getCode(), e.getMessage());
            throw new RuntimeException("Gate API Error", e);
        }
    }

    /**
     * Get Futures Accounts.
     *
     * @param settle Settle currency (e.g., "usdt").
     * @return Futures Account details
     */
    public FuturesAccount getFuturesAccounts(String settle) {
        try {
            return futuresApi.listFuturesAccounts(settle);
        } catch (ApiException e) {
            log.error("Gate API Error: {} {}", e.getCode(), e.getMessage());
            throw new RuntimeException("Gate API Error", e);
        }
    }
}
